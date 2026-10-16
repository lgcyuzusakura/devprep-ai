#!/usr/bin/env python3
"""Import public Xiaolin Coding interview articles into an ignored personal pack.

No model calls, login, paywall handling, or image mirroring. Requests run serially.
Requires beautifulsoup4; fetched HTML and source material stay outside Git.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote, urljoin, urlsplit
from urllib.request import Request, urlopen

from bs4 import BeautifulSoup, NavigableString, Tag
from multi_source import validate_pack, write_json

BASE = "https://xiaolincoding.com"
GROUPS = {
    "java": ("Java", "Java基础"), "collections": ("Java", "Java集合"),
    "juc": ("Java", "Java并发"), "jvm": ("Java", "JVM"), "spring": ("Java", "Spring"),
    "cpp": ("C++", "C++"), "golang": ("Go", "Go"), "python": ("Python", "Python"),
    "mysql": ("数据库", "MySQL"), "redis": ("数据库", "Redis"),
    "os": ("操作系统", "操作系统"), "network": ("计算机网络", "计算机网络"),
    "data": ("算法", "数据结构与算法"), "mq": ("后端", "消息队列"),
    "cap": ("后端", "分布式"), "systemdesign": ("后端", "系统设计"),
    "linux": ("后端", "Linux命令"), "git": ("后端", "Git命令"), "docker": ("后端", "Docker"),
    "business_testing": ("测试开发", "业务测试"), "python_automation": ("测试开发", "Python自动化测试"),
    "java_automation": ("测试开发", "Java自动化测试"), "performance_testing": ("测试开发", "性能测试"),
}


def clean_title(node: Tag) -> str:
    copy = BeautifulSoup(str(node), "html.parser")
    for item in copy.select(".header-anchor, .badge"):
        item.decompose()
    return re.sub(r"\s+", " ", copy.get_text(" ", strip=True)).strip("# ")


def render(node, base: str, depth: int = 0) -> str:
    if isinstance(node, NavigableString):
        return str(node)
    if not isinstance(node, Tag):
        return ""
    name = node.name
    if name in {"script", "style", "iframe", "button", "svg", "noscript"} or "header-anchor" in node.get("class", []):
        return ""
    if name == "pre":
        code = node.find("code") or node
        value = code.get_text().rstrip()
        classes = " ".join(node.get("class", []) + code.get("class", []))
        language = re.search(r"language-([a-zA-Z0-9_+-]+)", classes)
        runs = [len(x) for x in re.findall(r"`+", value)]
        fence = "`" * max([3] + [x + 1 for x in runs])
        return f"\n\n{fence}{language.group(1) if language else ''}\n{value}\n{fence}\n\n"
    if name == "code":
        value = node.get_text(); fence = "`" * max([1] + [len(x) + 1 for x in re.findall(r"`+", value)])
        return fence + value + fence
    if name == "img":
        source = node.get("src") or node.get("data-src") or ""
        if not source or not urlsplit(urljoin(base, source)).scheme in {"https", "http"}:
            return ""
        return f"![{node.get('alt', '').replace(']', '')}]({urljoin(base, source).replace(' ', '%20')})"
    if name == "table":
        rows = []
        for row in node.find_all("tr"):
            cells = [" ".join(render(cell, base).split()).replace("|", "\\|") for cell in row.find_all(["td", "th"], recursive=False)]
            if cells:
                rows.append(cells)
        if not rows:
            return ""
        width = max(map(len, rows)); rows = [row + [""] * (width - len(row)) for row in rows]
        lines = ["| " + " | ".join(row) + " |" for row in rows]
        lines.insert(1, "| " + " | ".join(["---"] * width) + " |")
        return "\n\n" + "\n".join(lines) + "\n\n"
    if name in {"ul", "ol"}:
        lines = []
        for index, item in enumerate(node.find_all("li", recursive=False), 1):
            body = "".join(render(child, base, depth + 1) for child in item.children).strip()
            marker = f"{index}. " if name == "ol" else "- "
            chunks = body.splitlines()
            lines.append(marker + (chunks[0] if chunks else "") + "".join("\n  " + line for line in chunks[1:]))
        return "\n\n" + "\n".join(lines) + "\n\n"
    value = "".join(render(child, base, depth) for child in node.children)
    if name == "a":
        href = urljoin(base, node.get("href", ""))
        if value.strip() and urlsplit(href).scheme in {"http", "https"}:
            return f"[{value.strip()}]({href.replace(' ', '%20')})"
        return value
    if name in {"strong", "b"}:
        return "**" + value.strip() + "**" if value.strip() else ""
    if name in {"em", "i"}:
        return "*" + value.strip() + "*" if value.strip() else ""
    if name == "br":
        return "\n"
    if re.fullmatch(r"h[1-6]", name):
        return "\n\n" + "#" * int(name[1]) + " " + value.strip() + "\n\n"
    if name == "blockquote":
        return "\n\n" + "\n".join("> " + line for line in value.strip().splitlines()) + "\n\n"
    if name in {"p", "div", "section", "details", "summary"}:
        return "\n\n" + value.strip() + "\n\n" if value.strip() else ""
    if name == "hr":
        return "\n\n---\n\n"
    return value


def extract(html: str, key: str, source_sha: str, fetched_at: str) -> tuple[list[dict], list[dict]]:
    soup = BeautifulSoup(html, "html.parser")
    main = soup.select_one(".theme-default-content")
    if main is None:
        raise ValueError(f"{key}: missing public article body")
    url = f"{BASE}/interview/{key}.html"
    domain, label = GROUPS[key]
    direct = list(main.children)
    headings = [(i, n) for i, n in enumerate(direct) if isinstance(n, Tag) and re.fullmatch("h[1-6]", n.name)]
    # Public question collections generally use h3; tools collections may use h2.
    levels = [int(n.name[1]) for _, n in headings if re.search(r"[?？]|怎么|如何|什么|说一下|区别|解释|介绍|哪些", clean_title(n))]
    question_level = min(levels) if levels else 3
    if question_level < 2:
        question_level = 3
    topics, questions = {}, []
    section = label
    for position, (start, heading) in enumerate(headings):
        level = int(heading.name[1]); title = clean_title(heading)
        if level < question_level:
            if level >= 2:
                section = title
            continue
        if level != question_level or not re.search(r"[\u3400-\u9fff]", title):
            continue
        if title in {"总结", "参考资料", "参考文献", "小结", "推荐阅读", "小林", "前言"}:
            continue
        end = len(direct)
        for next_start, next_heading in headings[position + 1:]:
            if int(next_heading.name[1]) <= question_level:
                end = next_start; break
        body = "".join(render(item, url) for item in direct[start + 1:end]).strip()
        meaningful = re.sub(r"!\[[^]]*]\([^)]*\)|[#*`\s]", "", body)
        if len(meaningful) < 30 or not re.search(r"[\u3400-\u9fff]", meaningful):
            continue
        point = f"local-xiaolin-{key}-" + hashlib.sha256(section.encode()).hexdigest()[:10]
        topics.setdefault(point, {"id": point, "domain": domain, "title": f"小林 / {label} / {section}" if section != label else f"小林 / {label}",
                                  "summary": "小林coding公开面试题正文，本地学习快照；保留作者署名与原文来源。", "prerequisites": [], "content_locale": "zh-CN"})
        anchor = heading.get("id", "")
        identity = f"{key}\0{anchor or title}"
        identifier = "local-xiaolin-" + hashlib.sha256(identity.encode()).hexdigest()[:24]
        if any(item["id"] == identifier for item in questions):
            continue
        questions.append({"id": identifier, "point": point, "type": "short_answer", "stem": title,
                          "options": [], "answer": body, "explanation": "小林coding公开原文参考答案；HTML转为Markdown，未调用模型改写。请结合原文核对并自评。",
                          "rubric": [], "assessment_mode": "reference", "content_format": "markdown", "status": "imported",
                          "source_name": "小林coding · " + label,
                          "source_url": url + ("#" + quote(anchor, safe="-_") if anchor else ""), "source_path": f"interview/{key}.html",
                          "source_license": "LicenseRef-All-Rights-Reserved", "source_attribution": "Copyright ©2020-2026 小林coding；作者保留版权，仅个人本地学习快照，不公开分发原文。",
                          "source_revision": fetched_at, "source_sha256": source_sha,
                          "source_locale": "zh-CN", "content_locale": "zh-CN", "translation_status": "original-zh-CN", "source_material_id": None})
    return list(topics.values()), questions


def fetch(url: str, target: Path) -> None:
    request = Request(url, headers={"User-Agent": "DevPrep-Personal-Learning/1.0", "Accept": "text/html"})
    with urlopen(request, timeout=25) as response:
        if urlsplit(response.url).hostname != "xiaolincoding.com":
            raise ValueError("Unexpected redirect outside original site")
        data = response.read(8_000_001)
        if len(data) > 8_000_000:
            raise ValueError("Article is oversized")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path(".cache/content/xiaolin"))
    parser.add_argument("--fetch", action="store_true", help="Download only the public, explicitly listed question articles, one at a time")
    parser.add_argument("--interval", type=float, default=1.5)
    parser.add_argument("--merge", type=Path, help="Merge into an existing local pack, preserving its questions and translation metadata")
    args = parser.parse_args()
    directory = args.directory
    if args.interval < 1:
        parser.error("Minimum request interval is one second")
    if directory.resolve().is_relative_to(Path("data/seed").resolve()):
        parser.error("Third-party originals must stay in an ignored local directory")
    directory.mkdir(parents=True, exist_ok=True)
    fetched = datetime.now(timezone.utc).isoformat()
    reports, topics, questions, failures = [], [], [], []
    for key in GROUPS:
        path = directory / "html" / f"{key}.html"
        try:
            if not path.exists() and args.fetch:
                fetch(f"{BASE}/interview/{key}.html", path)
                time.sleep(args.interval)
            if not path.exists():
                raise ValueError("No cached public article; use --fetch")
            raw = path.read_bytes(); sha = hashlib.sha256(raw).hexdigest()
            stamp = datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat()
            article_topics, article_questions = extract(raw.decode("utf-8"), key, sha, stamp)
            if len(article_questions) < 3:
                raise ValueError("Too few parsed questions; inspect the cached article before importing")
            topics.extend(article_topics); questions.extend(article_questions)
            reports.append({"key": key, "url": f"{BASE}/interview/{key}.html", "sha256": sha, "fetched_at": stamp, "questions": len(article_questions), "topics": len(article_topics)})
            print(f"{key}: {len(article_questions)} questions", flush=True)
        except Exception as ex:
            failures.append({"key": key, "error": str(ex)})
            print(f"{key}: skipped ({type(ex).__name__})", flush=True)
    pack = {"content_locale": "zh-CN", "topics": topics, "questions": questions}
    validate_pack(pack)
    write_json(directory / "xiaolin.learning.json", pack)
    write_json(directory / "xiaolin.audit.json", {"created_at": fetched, "source": BASE, "articles": reports, "failures": failures, "questions": len(questions), "topics": len(topics),
               "notice": "Only public article HTML fetched; no login/paywall access, no model calls. Image links require access to the original image hosts. Author copyright retained; local use only."})
    if failures:
        raise SystemExit("Some articles could not be imported. Successful pages are cached; inspect failures and rerun before merging.")
    if args.merge:
        original = args.merge.read_bytes(); merged = json.loads(original)
        validate_pack(merged)
        for name in ("topics", "questions"):
            kept = [item for item in merged[name] if not item["id"].startswith("local-xiaolin-")]
            merged[name] = kept + pack[name]
        merged.setdefault("local_imports", {})["xiaolin"] = {"snapshot": fetched, "articles": len(reports), "questions": len(questions), "topics": len(topics), "license": "LicenseRef-All-Rights-Reserved", "local_only": True}
        validate_pack(merged)
        backup = args.merge.with_name(args.merge.stem + ".before-xiaolin-" + hashlib.sha256(original).hexdigest()[:12] + ".json")
        if not backup.exists():
            backup.write_bytes(original)
        write_json(args.merge, merged)
        print(f"merged: {len(merged['questions'])} questions / {len(merged['topics'])} topics; backup: {backup}", flush=True)
    print(f"Xiaolin total: {len(questions)} questions / {len(topics)} topics", flush=True)


if __name__ == "__main__":
    main()
