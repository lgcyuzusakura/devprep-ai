#!/usr/bin/env python3
"""Import text exam questions into an ignored, local-only learning pack."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import unicodedata
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CACHE_ROOT = PROJECT_ROOT / ".cache" / "content"


@dataclass(frozen=True)
class Source:
    key: str
    name: str
    repository: str
    revision: str
    branch: str
    files: tuple[str, ...]
    data_file: str
    code_license: str
    content_license: str
    attribution: str
    notice: str


SOURCES = (
    Source(
        "architect", "软考系统架构设计师练题台",
        "Zhang-986/ruankao-architect-practice",
        "51217a582b93e4266eae8ec745fb308e55259f82", "main",
        ("LICENSE", "NOTICE.md", "README.md", "data/bank.json"), "data/bank.json",
        "MIT", "LicenseRef-Exam-Study-Only",
        "Zhang-986 contributors; wujiaming88/awesome-ruankao contributors; original exam and study-material right holders",
        "NOTICE separates MIT application code from question data. The upstream collection declares CC BY-SA 4.0; original exam rights remain with their right holders, for personal study and exchange only.",
    ),
    Source(
        "cs408", "408 考研刷题库",
        "lij768423-svg/408-",
        "267d0d815b1eee770dad12e88bbd560d08ba3f66", "main",
        ("LICENSE", "README.md", "data.json"), "data.json",
        "ISC", "LicenseRef-Unspecified",
        "lij768423-svg/408- contributors; original exam and textbook right holders",
        "The repository declares ISC, but a separate redistribution license for its third-party exam and textbook question content has not been verified. Keep question content local.",
    ),
)

MODULE_TITLES = {
    "computer_foundation": "计算机基础", "network": "计算机网络",
    "project_management": "项目管理", "software_engineering": "软件工程",
    "database": "数据库", "architecture": "系统架构", "legal_ip": "法律与知识产权",
    "security": "信息安全", "other": "综合知识", "new_technology": "新技术",
    "english": "专业英语", "embedded": "嵌入式系统",
}
IMAGE_MARKUP = re.compile(r"!\[[^\]]*\]\s*\(|<img\b", re.I)
IMAGE_REFERENCE = re.compile(
    r"如[下上左右]?图|[下上左右]图|见图|图中|下列(?:各)?图|"
    r"图\s*\d+(?:[.．-]\d+)*\s*(?:所示|中|的|为)"
)
TABLE_REFERENCE = re.compile(r"如[下上]?表|[下上]表|见表|表\s*\d+\s*(?:所示|中)")
TABLE_CONTENT = re.compile(r"<table\b|(?m:^[ \t]*\|[^\r\n]+\|)", re.I)
PLACEHOLDER = re.compile(r"题干.*(?:OCR.*(?:丢失|还原)|缺失)|(?:待补充|暂缺题干|暂无答案)", re.I)
FENCE = re.compile(r"^[ \t]{0,3}(`{3,}|~{3,})")


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical(value: str) -> str:
    """Only presentation differences are ignored when detecting duplicates."""
    value = unicodedata.normalize("NFKC", value)
    value = re.sub(r"\\(?:mathrm|mathsf|mathbf|mathit|operatorname|text|left|right)\b", "", value)
    value = re.sub(r"\\([_{}^*])", r"\1", value)
    return re.sub(r"[\s${}]", "", value)


def nonempty(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def plain_evidence(value: str) -> str:
    value = re.sub(r"<[^>]*>|\([^)]*分\)|（[^）]*分）", "", value)
    value = re.sub(r"(?:参考答案|答案|解析|考点分析|问题\s*\d+)[:：]?", "", value)
    return re.sub(r"[\s#*_`|\-:：]+", "", value)


def readable_text(value: object) -> bool:
    return nonempty(value) and len(plain_evidence(value)) >= 8


def display_dependencies(*values: str) -> str | None:
    """Missing figures are not silently converted into text-only exercises."""
    for value in values:
        if IMAGE_MARKUP.search(value) or IMAGE_REFERENCE.search(value):
            return "figure_not_imported"
        if TABLE_REFERENCE.search(value) and not TABLE_CONTENT.search(value):
            return "missing_table"
        if PLACEHOLDER.search(value):
            return "reconstructed_or_placeholder_content"
    return None


def protected_code(value: str) -> str:
    """Hide code so answer section markers inside examples cannot split it."""
    masked = []
    char = None
    length = 0
    for line in value.splitlines(keepends=True):
        body = line.rstrip("\r\n")
        fence = FENCE.match(body)
        if char is None and fence:
            char, length = fence.group(1)[0], len(fence.group(1))
            masked.append(" " * len(body) + line[len(body):])
        elif char is not None:
            if re.match(rf"^[ \t]{{0,3}}{re.escape(char)}{{{length},}}[ \t]*$", body):
                char = None
            masked.append(" " * len(body) + line[len(body):])
        else:
            masked.append(line)
    return "".join(masked)


def trim_following_answer(value: str, question_label: str = "") -> str:
    # Some upstream subquestion answers include the next subquestion's answer.
    # Preserve this answer's prefix and stop at an explicit new section boundary.
    own_number = re.search(r"问题\s*(\d+)", question_label)
    markers = re.finditer(
        r"(?m)^[ \t]*#{1,6}[ \t]+(?:【)?(?:问题\s*(?P<number>\d+)|考点分析|备考建议|题型分析)",
        protected_code(value),
    )
    for marker in markers:
        number = marker.group("number")
        if number and own_number and number == own_number.group(1):
            continue
        if marker.start() == 0 and number and own_number is None:
            continue
        return value[:marker.start()].rstrip()
    return value.strip()


def require_local_output(path: Path) -> Path:
    resolved = path.resolve()
    if not resolved.is_relative_to(CACHE_ROOT.resolve()):
        raise ValueError("External question content must be written inside .cache/content/.")
    return resolved


def write_json(path: Path, data: object) -> None:
    path = require_local_output(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def raw_url(source: Source, path: str, revision: str) -> str:
    return f"https://raw.githubusercontent.com/{source.repository}/{revision}/{quote(path, safe='/')}"


def download_source(source: Source, directory: Path) -> None:
    directory = require_local_output(directory)

    def fetch(relative: str) -> dict:
        url = raw_url(source, relative, source.revision)
        request = Request(url, headers={"User-Agent": "devprep-ai-local-exam-import"})
        with urlopen(request, timeout=45) as response:
            content = response.read()
        target = directory / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".part")
        temporary.write_bytes(content)
        temporary.replace(target)
        return {"path": relative, "url": url, "sha256": sha256(content), "bytes": len(content)}

    with ThreadPoolExecutor(max_workers=4) as executor:
        files = list(executor.map(fetch, source.files))
    write_json(directory / "snapshot.json", {
        "repository": source.repository, "revision": source.revision, "files": files,
        "notice": "Only the listed source files were downloaded at this exact commit.",
    })


def revision_for(source: Source, directory: Path, file_sha: str) -> str:
    # Check for a Git repository rooted at the snapshot itself, not the parent
    # application's checkout. Zip archives otherwise keep an unknown revision.
    if (directory / ".git").exists():
        result = subprocess.run(["git", "-C", str(directory), "rev-parse", "HEAD"],
                                capture_output=True, text=True, check=False)
        if result.returncode == 0 and re.fullmatch(r"[0-9a-f]{40}", result.stdout.strip()):
            return result.stdout.strip()
    manifest_path = directory / "snapshot.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
        revision = manifest.get("revision", "")
        if manifest.get("repository") == source.repository and re.fullmatch(r"[0-9a-f]{40}", revision):
            for item in manifest.get("files", []):
                if (item.get("path") == source.data_file and item.get("sha256") == file_sha
                        and item.get("url") == raw_url(source, source.data_file, revision)):
                    return revision
    return "unknown"


def metadata(source: Source, revision: str, file_sha: str, original: dict) -> dict:
    ref = source.branch if revision == "unknown" else revision
    return {
        "source_name": source.name,
        "source_url": f"https://github.com/{source.repository}/blob/{ref}/{quote(source.data_file, safe='/')}",
        "source_path": source.data_file,
        "source_license": source.content_license,
        "source_attribution": source.attribution,
        "source_revision": revision,
        "source_sha256": file_sha,
        "source_locale": "zh-CN",
        "source_material_id": None,
        "status": "imported",
        "content_format": "verbatim-html-markdown",
        "source_question_id": original.get("id", ""),
        "source_original_path": original.get("sourceFile", original.get("source_file", "")),
        "source_content_notice": source.notice,
    }


def question_id(source: Source, original_id: str, stem: str, options: list[str], answer: str) -> str:
    identity = json.dumps([source.key, original_id, stem, options, answer], ensure_ascii=False, separators=(",", ":"))
    return f"local-exams-{source.key}-" + sha256(identity.encode("utf-8"))[:24]


def fingerprint(stem: str, options: list[str], answer: str) -> str:
    return json.dumps([canonical(stem), [canonical(option) for option in options], canonical(answer)], ensure_ascii=False)


def single_choice(source: Source, original: dict, stem: str, options: object, answer: object,
                  explanation: object, point: str, label: str, source_metadata: dict) -> tuple[dict | None, str | None, str | None]:
    if not nonempty(stem):
        return None, "missing_stem", None
    if not isinstance(options, dict) or set(options) != {"A", "B", "C", "D"}:
        return None, "incomplete_options", None
    choices = [options[key].strip() if isinstance(options[key], str) else "" for key in "ABCD"]
    if any(not option for option in choices):
        return None, "empty_option", None
    if len({canonical(option) for option in choices}) != len(choices):
        return None, "duplicate_option_text", None
    if isinstance(answer, list):
        if len(answer) != 1:
            return None, "multiple_or_missing_answers", None
        answer = answer[0]
    if not isinstance(answer, str) or answer not in options:
        return None, "invalid_answer", None
    if not readable_text(explanation):
        return None, "missing_reference_explanation", None
    dependency = display_dependencies(stem, *choices)
    if dependency:
        return None, dependency, None
    # A figure in an explanation also carries information not in this pack.
    if IMAGE_MARKUP.search(explanation):
        return None, "explanation_figure_not_imported", None
    correct = choices["ABCD".index(answer)]
    full_stem = label + "\n\n" + stem.strip()
    question = {
        "id": question_id(source, str(original.get("id", "")), full_stem, choices, correct),
        "point": point, "type": "single_choice", "stem": full_stem,
        "options": choices, "answer": correct, "explanation": explanation.strip(),
        "rubric": [], "assessment_mode": "rubric", **source_metadata,
    }
    return question, None, fingerprint(stem, choices, correct)


def extract_architect(source: Source, data: dict, revision: str, file_sha: str) -> tuple[list[dict], dict, list[dict]]:
    topics = {}
    questions = []
    audit = []
    seen = set()

    def topic(module: str, case: bool = False) -> str:
        module = module if module in MODULE_TITLES else "other"
        point = f"local-exams-architect-{'case-' if case else ''}{module.replace('_', '-')}"
        topics.setdefault(point, {
            "id": point, "domain": "软考", "title": f"系统架构设计师 / {MODULE_TITLES[module]}{'案例' if case else ''}",
            "summary": "系统架构设计师真题与模拟题的本地参考练习，原答案需结合考纲和原始资料核对。",
            "prerequisites": [],
        })
        return point

    for original in data.get("choices", []):
        original_id = str(original.get("id", ""))
        label = f"【{original.get('term', '软考')} / 第 {original.get('questionNo', '?')} 题】"
        if original.get("sourceType") == "mock":
            label = f"【模拟题 / {original.get('term', '')} / 第 {original.get('questionNo', '?')} 题】"
        question, skipped, identity = single_choice(
            source, original, original.get("stem", ""), original.get("options"), original.get("answer"),
            original.get("analysis"), topic(original.get("module", "other")), label,
            metadata(source, revision, file_sha, original),
        )
        if question and identity in seen:
            question, skipped = None, "duplicate_question"
        if question:
            seen.add(identity)
            question["source_exam_kind"] = original.get("sourceType", "unknown")
            questions.append(question)
        audit.append({"original_id": original_id, "kind": "choice", "result": skipped or "imported",
                      "question_id": question["id"] if question else None})

    for original in data.get("cases", []):
        original_id = str(original.get("id", ""))
        description = original.get("description", "")
        parts = original.get("subQuestions", [])
        skipped = None
        question = None
        if not readable_text(description) or not isinstance(parts, list) or not parts:
            skipped = "case_missing_background_or_subquestions"
        elif any(not readable_text(part.get("prompt")) or not readable_text(trim_following_answer(part.get("reference_answer", ""), part.get("question_label", ""))) for part in parts):
            skipped = "case_missing_reference_answer"
        else:
            prompts = "\n\n".join(f"### {part.get('question_label', '问题')}\n\n{part['prompt'].strip()}" for part in parts)
            answers = "\n\n".join(f"### {part.get('question_label', '问题')}\n\n{trim_following_answer(part['reference_answer'], part.get('question_label', ''))}" for part in parts)
            skipped = display_dependencies(description, prompts)
            if not skipped and IMAGE_MARKUP.search(answers):
                skipped = "explanation_figure_not_imported"
            if not skipped:
                stem = f"【{original.get('term', '软考')} / 案例分析 / {original.get('title', '案例')}】\n\n{description.strip()}\n\n{prompts}"
                identity = fingerprint(description + prompts, [], answers)
                if identity in seen:
                    skipped = "duplicate_question"
                else:
                    seen.add(identity)
                    question = {
                        "id": question_id(source, original_id, stem, [], answers),
                        "point": topic(original.get("module", "architecture"), case=True),
                        "type": "short_answer", "stem": stem, "options": [], "answer": answers,
                        "explanation": "案例各小问保留原文参考答案；对照原始资料核对并自评。",
                        "rubric": [], "assessment_mode": "reference",
                        "source_exam_kind": original.get("sourceType", "unknown"),
                        **metadata(source, revision, file_sha, original),
                    }
                    questions.append(question)
        audit.append({"original_id": original_id, "kind": "case", "result": skipped or "imported",
                      "question_id": question["id"] if question else None})

    # Writing outlines are retained in the downloaded snapshot, but are not
    # reported as answered exercises.
    audit.extend({"original_id": str(item.get("id", "")), "kind": "essay",
                  "result": "writing_outline_only", "question_id": None} for item in data.get("essays", []))
    used = {question["point"] for question in questions}
    return questions, {key: value for key, value in topics.items() if key in used}, audit


def extract_cs408(source: Source, data: dict, revision: str, file_sha: str) -> tuple[list[dict], dict, list[dict]]:
    questions = []
    topics = {}
    audit = []
    seen = set()
    for original in data.get("questions", []):
        original_id = str(original.get("id", ""))
        skipped = None
        question = None
        if original.get("hidden"):
            skipped = "hidden_by_upstream"
        elif original.get("type") != "single_choice":
            skipped = "unsupported_multiple_choice"
        else:
            book = original.get("book", "计算机基础")
            chapter = str(original.get("chapter", "unknown"))
            group = sha256(f"{book}\0{chapter}".encode("utf-8"))[:12]
            point = f"local-exams-cs408-{group}"
            topics.setdefault(point, {
                "id": point, "domain": "考研 408",
                "title": f"{book} / {original.get('chapter_title') or '第 ' + chapter + ' 章'}",
                "summary": "408 选择题的本地原文练习；教材与统考资料解析需自行核对版本和勘误。",
                "prerequisites": [],
            })
            label = f"【408 / {book} / {original.get('section', '')} / 第 {original.get('num', '?')} 题】"
            question, skipped, identity = single_choice(
                source, original, original.get("question", ""), original.get("options"), original.get("answer"),
                original.get("explanation"), point, label, metadata(source, revision, file_sha, original),
            )
            if question and identity in seen:
                question, skipped = None, "duplicate_question"
            if question:
                seen.add(identity)
                questions.append(question)
        audit.append({"original_id": original_id, "kind": "choice", "result": skipped or "imported",
                      "question_id": question["id"] if question else None})
    used = {question["point"] for question in questions}
    return questions, {key: value for key, value in topics.items() if key in used}, audit


def validate_pack(pack: dict) -> None:
    points = set()
    for topic in pack["topics"]:
        if topic["id"] in points or not all(nonempty(topic.get(field)) for field in ("id", "domain", "title", "summary")):
            raise ValueError("Invalid or duplicate topic")
        if not isinstance(topic.get("prerequisites"), list):
            raise ValueError("Missing topic prerequisites")
        points.add(topic["id"])
    ids = set()
    for question in pack["questions"]:
        if question["id"] in ids or question["point"] not in points:
            raise ValueError("Duplicate question or missing topic")
        ids.add(question["id"])
        required = ("stem", "answer", "explanation", "source_name", "source_url", "source_path",
                    "source_license", "source_attribution", "source_revision", "source_sha256", "source_locale")
        if not all(nonempty(question.get(field)) for field in required):
            raise ValueError("Missing question content or provenance: " + question["id"])
        url = urlsplit(question["source_url"])
        if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password:
            raise ValueError("Invalid source URL")
        if not re.fullmatch(r"[0-9a-f]{64}", question["source_sha256"]):
            raise ValueError("Invalid source SHA-256")
        if question["status"] != "imported" or question["rubric"]:
            raise ValueError("Imported questions must use original reference data")
        if question["type"] == "single_choice":
            if (question["assessment_mode"] != "rubric" or len(question["options"]) != 4
                    or question["answer"] not in question["options"] or len(set(question["options"])) != 4):
                raise ValueError("Invalid single-choice question")
        elif question["type"] == "short_answer":
            if question["assessment_mode"] != "reference" or question["options"]:
                raise ValueError("Invalid reference question")
        else:
            raise ValueError("Unsupported question type")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=CACHE_ROOT / "exam-sources")
    parser.add_argument("--output", type=Path, default=CACHE_ROOT / "exams.learning.json")
    parser.add_argument("--audit", type=Path, default=CACHE_ROOT / "exams.audit.json")
    parser.add_argument("--download", action="store_true", help="Download the listed source files at the pinned revisions")
    args = parser.parse_args()
    try:
        require_local_output(args.output)
        require_local_output(args.audit)
        topics = {}
        questions = []
        reports = []
        for source in SOURCES:
            directory = args.source_root / source.key
            if args.download:
                download_source(source, directory)
            path = directory / source.data_file
            if not path.exists():
                reports.append({"source": source.key, "result": "source_not_downloaded", "question_count": 0})
                print(f"{source.key}: source not downloaded; use --download", file=sys.stderr)
                continue
            raw = path.read_bytes()
            data = json.loads(raw.decode("utf-8-sig"))
            file_sha = sha256(raw)
            revision = revision_for(source, directory, file_sha)
            extraction = extract_architect if source.key == "architect" else extract_cs408
            records, source_topics, records_audit = extraction(source, data, revision, file_sha)
            topics.update(source_topics)
            questions.extend(records)
            skipped = Counter(item["result"] for item in records_audit if item["result"] != "imported")
            reports.append({
                "source": source.key, "name": source.name, "repository": f"https://github.com/{source.repository}",
                "revision": revision, "path": source.data_file, "sha256": file_sha,
                "repository_code_license": source.code_license, "question_content_license": source.content_license,
                "notice": source.notice, "candidate_count": len(records_audit), "question_count": len(records),
                "type_counts": dict(Counter(item["type"] for item in records)),
                "topic_count": len(source_topics), "skipped": dict(skipped), "records": records_audit,
            })
            print(f"{source.key}: {len(records)} questions, {len(source_topics)} topics; skipped {dict(skipped)}")
        if not questions:
            raise ValueError("No answered text questions were imported. Prepare sources or use --download.")
        pack = {"topics": list(topics.values()), "questions": questions}
        validate_pack(pack)
        write_json(args.output, pack)
        write_json(args.audit, {
            "question_count": len(questions), "topic_count": len(topics),
            "type_counts": dict(Counter(item["type"] for item in questions)), "sources": reports,
            "duplicate_definition": "Same question text, option texts and correct answer after normalizing whitespace, Unicode width and math formatting commands.",
            "answer_handling": "Reference answers are imported from existing source text. Explicit following-answer sections are trimmed; no answers are generated.",
            "notice": "External text and snapshots are local only. Counts exclude hidden, unanswered, incomplete and unsupported questions; imported does not mean manually verified.",
        })
        print(f"Total: {len(questions)} questions, {len(topics)} topics -> {args.output}")
        return 0
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(f"Exam import failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
