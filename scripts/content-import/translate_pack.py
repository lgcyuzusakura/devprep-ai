#!/usr/bin/env python3
"""Translate an ignored learning pack to Simplified Chinese through an OpenAI-compatible gateway.

The source pack and the generated pack are deliberately kept under ``.cache``
(``.gitignore`` excludes that directory).  This script never sends source URLs,
licenses or other provenance fields to the provider.  A question id is sent as
an opaque correlation key alongside the learner-visible text.  The generated pack keeps the original
``source_locale`` and adds ``content_locale=zh-CN`` so a translated copy can be
identified without pretending that the upstream source was Chinese.

Translation is resumable: every translated question carries a hash of the
source text used for the request.  Re-running the command skips entries whose
hash is still current.  Writes are atomic after each batch, so an interrupted
run can continue from the last completed batch.

The default endpoint/model are read from OPENAI_API_BASE, OPENAI_API_KEY and
OPENAI_MODEL.  If these are absent, the selected provider in
``$CODEX_HOME/config.toml`` (or ``~/.codex/config.toml``) is used.  Only that
allowlisted base URL and model are read; auth.json is never inspected.
"""

from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Iterable


DEFAULT_INSTRUCTIONS = """你是计算机面试题本地化编辑。把给定的软件工程题目完整翻译成简体中文。
所有自然语言都要翻译成中文；Java、JavaScript、Python、React、Go、AWS、Kubernetes、
API、类名、函数名、命令、协议、标准、产品名和其他专有名词保留原文（必要时在中文后
保留英文）。代码块、行内代码、Markdown/HTML 标签、链接 URL、LaTeX 公式、表格结构、
列表编号和占位符必须保留；不要改写或翻译代码、标识符和命令。不要增加、删减或臆造
事实。答案是原文参考答案时，仍然只翻译自然语言，不改变事实、代码和格式。

只返回一个 JSON 对象，不要 Markdown 代码围栏或解释文字。对象必须含有：
{"items":[{"id":"原 id","stem":"中文题干","options":["中文选项"],"answer":"中文答案",
"explanation":"中文解析","rubric":["中文评分要点"]}]}
每个输入 id 必须恰好返回一次；空数组和空字符串按原样返回。
DPKEEP000000DP 一类占位符表示原始代码、公式或链接，必须原样保留在原来的字段内，
不得删除、重复、移动到另一个字段或改写占位符。不得把原文中文改回英文。
即使题干有少量中文，也要翻译题干及答案中尚未翻译的英文自然语言。"""

TEXT_FIELDS = ("stem", "options", "answer", "explanation", "rubric")
TOPIC_FIELDS = ("domain", "title", "summary")
MAX_RESPONSE_BYTES = 8_000_000
MAX_RETRIES = 4
TRANSLATION_VERSION = "zh-v2-protected-code"
PROTECTED = re.compile(
    r"(?m)^[ \t]*(?P<fence>`{3,})[^\n]*\n[\s\S]*?^[ \t]*(?P=fence)`*[ \t]*\r?$"
    r"|^[ \t]*(?P<tilde>~{3,})[^\n]*\n[\s\S]*?^[ \t]*(?P=tilde)~*[ \t]*\r?$"
    r"|<pre\b[^>]*>[\s\S]*?</pre>"
    r"|<code\b[^>]*>[\s\S]*?</code>"
    r"|(?P<inline>`+)(?!`)[\s\S]*?(?<!`)(?P=inline)(?!`)"
    r"|\$\$[\s\S]*?\$\$"
    r"|\$[^$\n]+\$"
    r"|\\\[[\s\S]*?\\\]"
    r"|\\\([^\n]*?\\\)"
    r"|(?<=\]\()[^\s)]+(?=\))"
    r"|https?://[^\s<>\"\')\]]+"
    r"|<[^>\n]+>", re.I | re.M)
BARE_COMMAND = re.compile(
    r"(?m)^[ \t]*(?:[$>]\s*)?(?:git|kubectl|docker|helm|curl|wget|npm|npx|pip|pip3|"
    r"python|python3|java|javac|mvn|gradle|bash|sh|ssh|scp|sudo|chmod|chown|systemctl|"
    r"apt|apt-get|yum|dnf|ls|cat|tail|head|sed|awk|grep|find|echo|printf|rm|cp|mv|"
    r"terraform|ansible|redis-cli|mongosh|mysql|psql)[ \t]+(?!is\b|are\b|can\b|allows\b|"
    r"provides\b|supports\b|uses\b|has\b)[^\r\n]+$")
PLACEHOLDER = re.compile(r"DPKEEP\d{6}DP")
ENGLISH_PROSE = re.compile(
    r"\b(?:what|which|why|how|where|when|who|explain|describe|compare|difference|"
    r"the|this|that|these|those|is|are|was|were|has|have|had|does|can|cannot|"
    r"will|would|should|must|because|however|although|therefore|using|used|"
    r"allows?|provides?|means?|refers?|returns?|enables?|supports?|"
    r"verbatim|reference answer|question and answer|material from|never|avoid|always|"
    r"and|of|to|for|from|with|into|then|each)\b", re.I)


class TranslationError(RuntimeError):
    """A provider response could not be used safely."""


def has_han(value: str) -> bool:
    return bool(re.search(r"[\u3400-\u9fff]", value or ""))


def contains_latin(value: str) -> bool:
    return bool(re.search(r"[A-Za-z]", value or ""))


def _normalise_url(value: str) -> str:
    value = value.strip().rstrip("/")
    if not value.startswith(("http://", "https://")):
        raise ValueError("OPENAI_API_BASE 必须是 http(s) URL")
    return value


def load_endpoint(args: argparse.Namespace) -> tuple[str, str, str]:
    """Return (base_url, model, wire_api), without reading any token file."""
    base = args.base_url or os.getenv("OPENAI_API_BASE") or os.getenv("OPENAI_BASE_URL")
    model = args.model or os.getenv("OPENAI_MODEL") or os.getenv("OPENAI_API_MODEL")
    wire = args.wire_api or os.getenv("OPENAI_WIRE_API")
    codex_path = Path(os.getenv("CODEX_HOME", str(Path.home() / ".codex"))) / "config.toml"
    if codex_path.is_file() and (not base or not model or not wire):
        try:
            import tomllib

            config = tomllib.loads(codex_path.read_text(encoding="utf-8"))
            provider_id = str(config.get("model_provider", ""))
            provider = config.get("model_providers", {}).get(provider_id, {})
            if not base:
                base = provider.get("base_url")
            if not model:
                model = config.get("model")
            if not wire:
                wire = provider.get("wire_api")
        except (OSError, ValueError, TypeError):
            # Explicit environment values still work when the optional Codex
            # TOML is malformed.  The actionable error is emitted below.
            pass
    base = _normalise_url(str(base or ""))
    model = str(model or "").strip()
    if not model:
        raise ValueError("没有模型名；请设置 OPENAI_MODEL 或 --model")
    wire = (wire or "responses").strip().lower().replace("-", "_")
    if wire == "chat":
        wire = "chat_completions"
    if wire not in {"responses", "chat_completions"}:
        raise ValueError("OPENAI_WIRE_API 必须是 responses 或 chat_completions")
    return base, model, wire


def api_key(args: argparse.Namespace) -> str:
    value = args.api_key or os.getenv("OPENAI_API_KEY")
    if not value:
        raise ValueError("没有 API Key；请设置 OPENAI_API_KEY 或 --api-key")
    return value


def sha_payload(value: Any) -> str:
    data = json.dumps({"version": TRANSLATION_VERSION, "text": value}, ensure_ascii=False,
                      sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def natural_prose(value: str) -> str:
    return BARE_COMMAND.sub(" ", PROTECTED.sub(" ", value or ""))


def needs_translation(value: str) -> bool:
    text = natural_prose(value)
    if not contains_latin(text):
        return False
    # Count English inside continuous runs instead of across an entire Chinese
    # answer.  Several unrelated terms (rwnd, persist timer, socket buffer...)
    # are not an untranslated sentence merely because there are many of them.
    runs = re.findall(r"[A-Za-z][A-Za-z0-9'’._+-]*(?:[ \t]+[A-Za-z][A-Za-z0-9'’._+-]*)*", text)
    for run in runs:
        if not re.search(r"[ \t]", run):
            # Stop-The-World and similar identifiers are single terms.
            continue
        words = re.findall(r"\b[A-Za-z]{2,}\b", run)
        if len(words) >= 2 and re.match(r"(?:what|which|why|how|where|when|who|"
                                       r"explain|describe|compare|define|list|find|avoid|never|"
                                       r"disable|enable|remove|return|create|delete|configure|install|"
                                       r"update|prevent|ensure|check|verify|use|add|read|write)\b", run, re.I):
            return True
        if len(words) >= 3 and ENGLISH_PROSE.search(run):
            return True
        if len(re.findall(r"\b[a-z]{2,}\b", run)) >= 4:
            return True
    return False


def protected_payload(items: list[dict[str, Any]], fields: Iterable[str]) -> tuple[list[dict[str, Any]], dict[str, str]]:
    """Mask code/URLs before the provider sees them; restore them byte for byte."""
    originals: dict[str, str] = {}

    def mask(value: str) -> str:
        def preserve(raw: str) -> str:
            token = f"DPKEEP{len(originals):06d}DP"
            originals[token] = raw
            return token

        def replace(match: re.Match[str]) -> str:
            return preserve(match.group(0))

        value = PROTECTED.sub(replace, value)
        value = BARE_COMMAND.sub(replace, value)
        # Already-Chinese paragraphs should remain untouched.  This also avoids
        # asking a model to regenerate thousands of Chinese characters around
        # one small English quote.  Nested placeholders are restored below.
        return "".join(preserve(line) if has_han(line) and not needs_translation(line) else line
                       for line in value.splitlines(keepends=True))

    encoded: list[dict[str, Any]] = []
    for item in items:
        row: dict[str, Any] = {"id": item["id"]}
        for field in fields:
            value = item[field]
            row[field] = [mask(x) for x in value] if isinstance(value, list) else mask(value)
        encoded.append(row)
    return encoded, originals


def restore_payload(items: list[dict[str, Any]], translated: dict[str, dict[str, Any]],
                    originals: dict[str, str], fields: Iterable[str]) -> dict[str, dict[str, Any]]:
    fields = tuple(fields)
    for source in items:
        row = translated[source["id"]]
        for field in fields:
            expected = source[field]
            actual = row[field]
            if isinstance(expected, list):
                if len(expected) != len(actual):
                    raise TranslationError(f"{source['id']} 的 {field} 数量被改变")
                pairs = zip(expected, actual)
            else:
                pairs = [(expected, actual)]
            restored: list[str] = []
            for before, after in pairs:
                expected_tokens = PLACEHOLDER.findall(before)
                actual_tokens = PLACEHOLDER.findall(after)
                if expected_tokens != actual_tokens:
                    raise TranslationError(f"{source['id']} 的 {field} 代码/链接占位符被改变")
                while PLACEHOLDER.search(after):
                    after = PLACEHOLDER.sub(lambda match: originals[match.group(0)], after)
                restored.append(after)
            row[field] = restored if isinstance(expected, list) else restored[0]
    return translated


def output_text(decoded: dict[str, Any], wire_api: str) -> str:
    if wire_api == "responses":
        value = decoded.get("output_text")
        if isinstance(value, str) and value.strip():
            return value.strip()
        parts: list[str] = []
        for output in decoded.get("output", []) or []:
            if not isinstance(output, dict):
                continue
            for item in output.get("content", []) or []:
                if isinstance(item, dict) and isinstance(item.get("text"), str):
                    parts.append(item["text"])
        if parts:
            return "\n".join(parts).strip()
        raise TranslationError("Responses API 没有返回 output_text")
    choices = decoded.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
        raise TranslationError("Chat Completions 没有返回 choices")
    message = choices[0].get("message") or {}
    text = message.get("content") if isinstance(message, dict) else None
    if isinstance(text, list):
        text = "\n".join(str(item.get("text", "")) for item in text if isinstance(item, dict))
    if not isinstance(text, str) or not text.strip():
        raise TranslationError("Chat Completions 没有返回文本")
    return text.strip()


def parse_json_text(text: str) -> dict[str, Any]:
    text = text.strip()
    # Some compatible gateways wrap JSON despite the instruction.  Removing a
    # single fence is safe; arbitrary prose is rejected to avoid silent loss.
    if text.startswith("```") and text.endswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.I)
        text = re.sub(r"\s*```$", "", text)
    try:
        value = json.loads(text)
    except json.JSONDecodeError as exc:
        raise TranslationError(f"模型返回的不是 JSON：{exc.msg}") from exc
    if not isinstance(value, dict) or not isinstance(value.get("items"), list):
        raise TranslationError("模型 JSON 必须包含 items 数组")
    return value


def request_batch(base_url: str, key: str, model: str, wire_api: str,
                  items: list[dict[str, Any]], timeout: int, kind: str) -> dict[str, Any]:
    payload = {"items": items}
    input_text = "待翻译 JSON：\n" + json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    if kind == "topic":
        instructions = """你是计算机课程本地化编辑。把知识点的 domain、title、summary 完整翻译成简体中文。
软件名称、协议、标准、产品名、API、类名、函数名和其他专有名词保留原文（必要时在中文后
保留英文）。不要修改 id，不要返回 prerequisites，也不要增加或删减事实。只返回 JSON 对象，
格式为 {\"items\":[{\"id\":\"原 id\",\"domain\":\"中文领域\",\"title\":\"中文标题\",\"summary\":\"中文摘要\"}]}，
每个 id 必须恰好返回一次，不要 Markdown 围栏或解释文字。
DPKEEP000000DP 一类占位符必须原样保留在原来的字段内。"""
    else:
        instructions = DEFAULT_INSTRUCTIONS
    if wire_api == "responses":
        body: dict[str, Any] = {"model": model, "instructions": instructions,
                                "input": input_text, "store": False,
                                "reasoning": {"effort": "low"}}
        endpoint = base_url + "/responses"
    else:
        body = {"model": model, "stream": False,
                "messages": [{"role": "system", "content": instructions},
                             {"role": "user", "content": input_text}]}
        endpoint = base_url + "/chat/completions"
    encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(endpoint, data=encoded, method="POST",
                                     headers={"Accept": "application/json",
                                              "Content-Type": "application/json",
                                              "Authorization": "Bearer " + key})
    last: Exception | None = None
    for attempt in range(MAX_RETRIES):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read(MAX_RESPONSE_BYTES + 1)
                if len(raw) > MAX_RESPONSE_BYTES:
                    raise TranslationError("模型响应超过 8 MB")
            decoded = json.loads(raw.decode("utf-8"))
            if not isinstance(decoded, dict):
                raise TranslationError("模型响应不是 JSON 对象")
            return parse_json_text(output_text(decoded, wire_api))
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError,
                json.JSONDecodeError, UnicodeDecodeError, TranslationError) as exc:
            last = exc
            if isinstance(exc, urllib.error.HTTPError) and exc.code not in {408, 409, 425, 429} and exc.code < 500:
                break
            if attempt + 1 < MAX_RETRIES:
                time.sleep(min(2 ** attempt, 8))
    raise TranslationError(f"翻译请求失败（重试 {MAX_RETRIES} 次）：{last}") from last


def translate_batch(base_url: str, key: str, model: str, wire_api: str,
                    items: list[dict[str, Any]], timeout: int, kind: str) -> dict[str, dict[str, Any]]:
    fields = TOPIC_FIELDS if kind == "topic" else TEXT_FIELDS
    masked, originals = protected_payload(items, fields)
    last: Exception | None = None
    for _ in range(3):
        try:
            returned = request_batch(base_url, key, model, wire_api, masked, timeout, kind)
            checked = validate_items(masked, returned, fields)
            restored = restore_payload(masked, checked, originals, fields)
            for source in items:
                row = restored[source["id"]]
                if kind == "question" and source.get("options"):
                    if len(set(row["options"])) != len(row["options"]):
                        raise TranslationError(f"{source['id']} 译后选项重复")
                    if source.get("answer") in source["options"]:
                        correct_index = source["options"].index(source["answer"])
                        if row["answer"] != row["options"][correct_index]:
                            raise TranslationError(f"{source['id']} 译后答案不对应原正确选项")
                for field in fields:
                    # A factual answer may be nothing but a command or name.
                    # Those strings have no prose and must be preserved.
                    before_values = source[field] if isinstance(source[field], list) else [source[field]]
                    after_values = row[field] if isinstance(row[field], list) else [row[field]]
                    for before, after in zip(before_values, after_values):
                        if needs_translation(before) and needs_translation(after):
                            raise TranslationError(f"{source['id']} 的 {field} 仍有未翻译英文自然语言")
            return restored
        except TranslationError as exc:
            last = exc
    # A malformed multi-item result is recoverable by asking for each item
    # separately; already completed items are still preserved by the caller.
    if len(items) > 1:
        result: dict[str, dict[str, Any]] = {}
        for row in items:
            result.update(translate_batch(base_url, key, model, wire_api, [row], timeout, kind))
        return result
    raise TranslationError(str(last)) from last


def field_payload(record: dict[str, Any]) -> dict[str, Any]:
    return {"id": record["id"], "stem": record.get("stem", ""),
            "options": list(record.get("options") or []), "answer": record.get("answer", ""),
            "explanation": record.get("explanation", ""), "rubric": list(record.get("rubric") or [])}


def topic_payload(record: dict[str, Any]) -> dict[str, Any]:
    return {"id": record["id"], **{key: record.get(key, "") for key in TOPIC_FIELDS}}


def validate_items(source: list[dict[str, Any]], returned: dict[str, Any], fields: Iterable[str]) -> dict[str, dict[str, Any]]:
    rows = returned.get("items")
    if len(rows) != len(source):
        raise TranslationError(f"模型返回 {len(rows)} 项，但请求了 {len(source)} 项")
    expected = {str(item["id"]) for item in source}
    result: dict[str, dict[str, Any]] = {}
    for item in rows:
        if not isinstance(item, dict) or not isinstance(item.get("id"), str) or item["id"] not in expected:
            raise TranslationError("模型返回了未知或无效 id")
        if item["id"] in result:
            raise TranslationError("模型返回了重复 id")
        for field in fields:
            value = item.get(field)
            if field in {"options", "rubric"}:
                if not isinstance(value, list) or any(not isinstance(x, str) for x in value):
                    raise TranslationError(f"字段 {field} 必须是字符串数组")
            elif not isinstance(value, str):
                raise TranslationError(f"字段 {field} 必须是字符串")
        result[item["id"]] = item
    if set(result) != expected:
        raise TranslationError("模型没有完整返回请求的 id")
    for item in source:
        translated = result[item["id"]]
        natural = " ".join(str(translated.get(field, "")) for field in fields if field not in {"options", "rubric"})
        before = PLACEHOLDER.sub("", " ".join(str(item.get(field, "")) for field in fields if field not in {"options", "rubric"}))
        if needs_translation(before) and not has_han(natural):
            raise TranslationError(f"{item['id']} 返回内容没有中文字符")
    return result


def should_translate_question(question: dict[str, Any]) -> bool:
    if (question.get("source_locale") or question.get("locale")) == "en":
        return True
    # Imported zh-CN material can still include English prose.  A Han character
    # elsewhere does not make that prose Chinese.  Code and names are excluded.
    for field in ("stem", "answer", "explanation"):
        value = question.get(field, "")
        if isinstance(value, str) and needs_translation(value):
            return True
    return any(needs_translation(value) for field in ("options", "rubric")
               for value in question.get(field, []) or [] if isinstance(value, str))


def looks_like_code(value: str) -> bool:
    text = value.strip()
    if not text:
        return False
    if "```" in text or re.search(r"[{};]|^\s*(?:[$>#]|(?:curl|kubectl|docker|git|go|npm|pip|python|java)\s)", text, re.I | re.M):
        return True
    return bool(re.fullmatch(r"[A-Za-z0-9_./:+*#<>()[\]{}=\-\s`'\"\\]+", text)) and not re.search(r"\b(?:what|why|how|the|is|are|use|used|does|can|should)\b", text, re.I)


def atomic_write(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def load_pack(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or not isinstance(value.get("topics"), list) or not isinstance(value.get("questions"), list):
        raise ValueError("题库必须包含 topics 和 questions 数组")
    return value


def copy_cached_fields(target: dict[str, Any], cached: dict[str, Any], input_hash: str,
                       fields: Iterable[str]) -> bool:
    if cached.get("translation_input_sha256") != input_hash or cached.get("content_locale") != "zh-CN":
        return False
    fields = tuple(fields)
    for field in fields:
        if field not in cached:
            return False
        before = target[field] if isinstance(target[field], list) else [target[field]]
        after = cached[field] if isinstance(cached[field], list) else [cached[field]]
        if len(before) != len(after):
            return False
        for original, translated in zip(before, after):
            if not isinstance(translated, str):
                return False
            if needs_translation(original) and needs_translation(translated):
                return False
            if [match.group(0) for match in PROTECTED.finditer(original)] != [match.group(0) for match in PROTECTED.finditer(translated)]:
                return False
            if [match.group(0) for match in BARE_COMMAND.finditer(original)] != [match.group(0) for match in BARE_COMMAND.finditer(translated)]:
                return False
    for field in fields:
        target[field] = cached[field]
    target["content_locale"] = "zh-CN"
    target["translation_status"] = "translated"
    target["translation_input_sha256"] = input_hash
    return True


def batches(rows: list[dict[str, Any]], batch_size: int, max_chars: int) -> Iterable[list[dict[str, Any]]]:
    current: list[dict[str, Any]] = []
    chars = 0
    for row in rows:
        fields = TOPIC_FIELDS if "domain" in row else TEXT_FIELDS
        masked, _ = protected_payload([row], fields)
        size = len(json.dumps(masked[0], ensure_ascii=False))
        if current and (len(current) >= batch_size or chars + size > max_chars):
            yield current
            current, chars = [], 0
        current.append(row)
        chars += size
    if current:
        yield current


def main() -> int:
    project_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=project_root / ".cache/content/local.learning.json")
    parser.add_argument("--output", type=Path, default=project_root / ".cache/content/local.learning.zh-CN.json")
    parser.add_argument("--base-url", help="OpenAI-compatible base URL, for example http://127.0.0.1:3000/v1")
    parser.add_argument("--api-key", help="API key; prefer OPENAI_API_KEY so it is not saved in shell history")
    parser.add_argument("--model", help="Model name; otherwise OPENAI_MODEL or Codex config")
    parser.add_argument("--wire-api", choices=["responses", "chat_completions"], help="Gateway wire format")
    parser.add_argument("--batch-size", type=int, default=6)
    parser.add_argument("--max-input-chars", type=int, default=12000)
    parser.add_argument("--workers", type=int, default=1, help="Gateway workers (default: one sequential worker)")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--limit", type=int, help="Translate at most this many uncached records (for a sample run)")
    parser.add_argument("--include-topics", action=argparse.BooleanOptionalAction, default=True)
    args = parser.parse_args()
    if args.batch_size < 1 or args.max_input_chars < 1000 or args.timeout < 5 or not 1 <= args.workers <= 32:
        parser.error("batch-size、max-input-chars 或 timeout 太小")
    try:
        source_path = args.input.resolve()
        output_path = args.output.resolve()
        source = load_pack(source_path)
        cached = load_pack(output_path) if output_path.is_file() else {"topics": [], "questions": []}
        cached_topics = {row.get("id"): row for row in cached["topics"] if isinstance(row, dict)}
        cached_questions = {row.get("id"): row for row in cached["questions"] if isinstance(row, dict)}
        result = {key: value for key, value in source.items() if key not in {"topics", "questions"}}
        result["content_locale"] = "und"
        result["translation"] = {"source": source_path.name, "provider": "OpenAI-compatible gateway",
                                  "note": "翻译输出保留 source_locale、source_url 和许可证字段；正文只作本地学习使用。"}
        result["topics"] = []
        result["questions"] = []
        pending_topics: list[dict[str, Any]] = []
        for topic in source["topics"]:
            out = dict(topic)
            payload = topic_payload(topic)
            digest = sha_payload(payload)
            if args.include_topics and any(needs_translation(str(topic.get(k, ""))) for k in TOPIC_FIELDS):
                if not copy_cached_fields(out, cached_topics.get(topic.get("id"), {}), digest, TOPIC_FIELDS):
                    out["content_locale"] = "und"
                    out["translation_status"] = "pending"
                    pending_topics.append(payload)
                    result["topics"].append(out)
                else:
                    result["topics"].append(out)
            else:
                out["content_locale"] = "zh-CN"
                out["translation_status"] = "original-zh-CN"
                result["topics"].append(out)
        question_work: list[dict[str, Any]] = []
        translated_ids: set[str] = set()
        for question in source["questions"]:
            out = dict(question)
            payload = field_payload(question)
            digest = sha_payload(payload)
            if should_translate_question(question):
                if copy_cached_fields(out, cached_questions.get(question.get("id"), {}), digest, TEXT_FIELDS):
                    translated_ids.add(question["id"])
                else:
                    out["content_locale"] = "und"
                    out["translation_status"] = "pending"
                    question_work.append(payload)
            else:
                out["content_locale"] = "zh-CN"
                out["translation_status"] = "original-zh-CN"
            result["questions"].append(out)
        # For a sample run, topics are included first and count toward --limit.
        pending = [("topic", row) for row in pending_topics] + [("question", row) for row in question_work]
        if args.limit is not None:
            if args.limit < 1:
                parser.error("limit 必须大于 0")
            pending = pending[:args.limit]
        if pending:
            base, model, wire = load_endpoint(args)
            key = api_key(args)
            result["translation"]["model"] = model
            result["translation"]["wire_api"] = wire
            result_topic_by_id = {row.get("id"): row for row in result["topics"]}
            result_question_by_id = {row.get("id"): row for row in result["questions"]}
            jobs: list[tuple[str, list[dict[str, Any]]]] = []
            for kind in ("topic", "question"):
                rows = [row for row_kind, row in pending if row_kind == kind]
                jobs.extend((kind, batch) for batch in batches(rows, args.batch_size, args.max_input_chars))
            errors: list[dict[str, Any]] = []
            completed = 0
            started = time.monotonic()
            with ThreadPoolExecutor(max_workers=args.workers) as pool:
                futures = {pool.submit(translate_batch, base, key, model, wire, batch, args.timeout, kind): (kind, batch)
                           for kind, batch in jobs}
                for future in as_completed(futures):
                    kind, batch = futures[future]
                    try:
                        translated = future.result()
                    except Exception as exc:
                        errors.append({"kind": kind, "ids": [row["id"] for row in batch], "error": str(exc)})
                        print(f"batch failed {kind} ids={len(batch)}: {exc}", file=sys.stderr, flush=True)
                        continue
                    for original in batch:
                        translated_row = translated[original["id"]]
                        destination = result_topic_by_id[original["id"]] if kind == "topic" else result_question_by_id[original["id"]]
                        fields = TOPIC_FIELDS if kind == "topic" else TEXT_FIELDS
                        for field in fields:
                            destination[field] = translated_row[field]
                        destination["content_locale"] = "zh-CN"
                        destination["translation_status"] = "translated"
                        destination["translation_input_sha256"] = sha_payload(original)
                    completed += len(batch)
                    result["translation"]["translated_this_run"] = completed
                    result["translation"]["remaining_this_run"] = len(pending) - completed
                    atomic_write(output_path, result)
                    print(f"translated {kind} +{len(batch)} done={completed}/{len(pending)} elapsed={time.monotonic()-started:.1f}s", flush=True)
            if errors:
                atomic_write(output_path.with_suffix(".failures.json"), {"failures": errors})
                atomic_write(output_path, result)
                raise TranslationError(f"{len(errors)} 批失败，已保留成功结果；重新运行即可续跑")
        # Keep translated/cached records in source order and write even when no
        # provider call was needed, so the caller can point Spring Boot at it.
        atomic_write(output_path, result)
        remaining = len(pending_topics) + len(question_work) - min(len(pending_topics) + len(question_work), args.limit or len(pending_topics) + len(question_work))
        result["content_locale"] = "zh-CN" if remaining == 0 else "und"
        result["translation"]["remaining_uncached"] = remaining
        atomic_write(output_path, result)
        print(json.dumps({"output": str(output_path), "topics": len(result["topics"]),
                          "questions": len(result["questions"]), "pending_before_run": len(pending_topics) + len(question_work),
                          "requested_this_run": len(pending), "remaining_uncached": remaining}, ensure_ascii=False))
        return 0
    except (OSError, ValueError, TranslationError, urllib.error.URLError) as exc:
        print(f"translate failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
