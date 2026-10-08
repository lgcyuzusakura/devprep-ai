#!/usr/bin/env python3
"""Build an ignored local learning pack from checked-out or archived Markdown sources."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote, urlsplit

from devops import build_learning_data, discover_source_files, extract_file, snapshot_commit


@dataclass(frozen=True)
class Source:
    key: str
    name: str
    directory: str
    repository: str
    branch: str
    license: str
    attribution: str
    parser: str


SOURCES = (
    Source("javaguide", "JavaGuide", "devprep-java/JavaGuide-main",
           "https://github.com/Snailclimb/JavaGuide", "main", "Apache-2.0",
           "Snailclimb/JavaGuide contributors", "javaguide"),
    Source("javascript", "JavaScript Interview Questions", "devprep-javascript/javascript-interview-questions-master",
           "https://github.com/sudheerj/javascript-interview-questions", "master", "LicenseRef-Unspecified",
           "Sudheer Jonna and javascript-interview-questions contributors", "javascript"),
    Source("react", "React Interview Questions", "devprep-react/reactjs-interview-questions-master",
           "https://github.com/sudheerj/reactjs-interview-questions", "master", "MIT",
           "Copyright (c) 2017-Present Sudheer Jonna; reactjs-interview-questions contributors", "react"),
    Source("go", "Golang 面试题搜集", "devprep-go/interview-go-master",
           "https://github.com/lifei6671/interview-go", "master", "Apache-2.0",
           "lifei6671/interview-go contributors", "go"),
    Source("python", "Python Interview Questions", "python-questions",
           "https://github.com/learning-zone/python-interview-questions", "master", "LicenseRef-Unspecified",
           "learning-zone/python-interview-questions contributors", "python"),
)

HEADING = re.compile(r"^(?P<indent>[ \t]*)(?:(?P<number>\d+)[.)][ \t]+)?(?P<marks>#{1,6})[ \t]+(?P<title>[^\r\n]+)")
FENCE = re.compile(r"^[ \t]*(`{3,}|~{3,})")
QUESTION_WORDS = re.compile(r"[?？]|(?:什么|为何|为什么|如何|怎么|哪些|是否|区别|请解释|请说明|说说|写出|找出|下面.*代码|以下.*代码)")
ANSWER_MARKER = re.compile(
    r"(?mi)^[ \t]*(?:#{1,6}[ \t]+)?(?:\*\*)?(?:正确答案|参考答案|答案|解题思路|源码参考|官方解析|解析|Answer\b)[^\r\n]*(?:\r?\n|$)"
)
GITHUB_TO_TOP = re.compile(r"(?mi)^[ \t]*\*\*\[⬆ Back to Top\]\(#table-of-contents\)\*\*[ \t]*$")
JAVAGUIDE_DIRS = {"java", "cs-basics", "database", "system-design", "distributed-system", "high-performance", "high-availability", "ai"}
CONTENT_WORD = re.compile(r"[\w\u4e00-\u9fff]", re.UNICODE)
REACT_SECTIONS = {"Core React", "React Router", "React Internationalization", "React Testing", "React Redux",
                  "React Native", "React supported libraries & Integration", "Miscellaneous",
                  "Modern React Features (React 18/19)", "Old Q&A", "Disclaimer"}
TOPIC_LABELS = {"basis": "基础", "collection": "集合", "concurrent": "并发", "io": "输入输出", "jvm": "JVM",
                "new-features": "新特性", "algorithms": "算法", "data-structure": "数据结构", "network": "计算机网络",
                "operating-system": "操作系统", "database": "数据库基础", "mongodb": "MongoDB", "mysql": "MySQL",
                "redis": "Redis", "sql": "SQL", "distributed-system": "分布式基础", "zookeeper": "ZooKeeper",
                "protocol": "协议", "rpc": "RPC", "high-availability": "高可用", "high-performance": "高性能",
                "message-queue": "消息队列", "mybatis": "MyBatis", "spring": "Spring", "ai": "AI 概念",
                "interview-questions": "项目面试", "llm-basis": "大模型基础", "rag": "检索增强生成",
                "agent": "Agent 工程", "system-design": "系统设计", "framework": "后端框架"}


@dataclass(frozen=True)
class Heading:
    start: int
    body_start: int
    level: int
    title: str
    numbered: bool
    separator: str = "\n"


def headings(source: str) -> list[Heading]:
    result = []
    fence_char = None
    fence_length = 0
    offset = 0
    for line in source.splitlines(keepends=True):
        body = line.rstrip("\r\n")
        fence = FENCE.match(body)
        if fence_char is not None:
            if re.match(rf"^[ \t]*{re.escape(fence_char)}{{{fence_length},}}[ \t]*$", body):
                fence_char = None
                fence_length = 0
            offset += len(line)
            continue
        if fence:
            info = body[fence.end():]
            # Backtick fence info cannot contain another backtick. Inline
            # snippets such as ```wc -l``` are not an opening block fence.
            if fence.group(1)[0] != "`" or "`" not in info:
                fence_char = fence.group(1)[0]
                fence_length = len(fence.group(1))
                offset += len(line)
                continue
        match = HEADING.match(body)
        if match:
            result.append(Heading(offset, offset + len(line), len(match.group("marks")),
                                  match.group("title"), match.group("number") is not None, line[len(body):]))
        offset += len(line)
    return result


def is_section_heading(heading: Heading, parser: str) -> bool:
    if parser == "javascript":
        return heading.title in {"Coding Exercise", "Disclaimer"}
    if parser == "react":
        return heading.level <= 2 and heading.title in REACT_SECTIONS
    if parser == "python":
        return heading.level <= 2 and re.match(r"#\s*\d+[.]", heading.title) is not None
    return heading.level <= 2


def section_end(items: list[Heading], index: int, source: str, parser: str, relative: Path) -> int:
    heading = items[index]
    for later in items[index + 1:]:
        if parser in {"javascript", "react", "python"}:
            if candidate_heading(later, parser, relative) or is_section_heading(later, parser):
                return later.start
        elif later.level <= heading.level:
            return later.start
    return len(source)


def evidence_text(value: str) -> str:
    """Remove presentation-only tokens for validation, leaving exports untouched."""
    text = re.sub(r"<!--.*?-->", "", value, flags=re.S)
    text = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", text)
    text = re.sub(r"\[[^\]]*\]\([^)]*\)", "", text)
    text = re.sub(r"<[^>]*>", "", text)
    text = re.sub(r"^[ \t]*#{1,6}[^\r\n]*$", "", text, flags=re.M)
    text = re.sub(r"[\s`*_#>|:;\-]+", "", text)
    return text


def has_reference_answer(value: str) -> bool:
    meaningful = evidence_text(value)
    if not CONTENT_WORD.search(meaningful) or len(meaningful) < 8:
        return False
    lines = [line.strip() for line in value.splitlines() if line.strip() and not line.lstrip().startswith("<!--")]
    if not lines:
        return False
    if len(lines) >= 2 and all(re.match(r"^(?:\*\*)?[A-DＡ-Ｄ](?:\*\*)?\s*[:：.]", line) for line in lines):
        return False
    # Avoid turning navigation and lists of unanswered questions into answers.
    if all(re.match(r"^(?:[-*]|\d+[.)])\s+", line) and re.search(r"[?？]$", line) for line in lines):
        return False
    return True


def first_code_block_end(body: str) -> int | None:
    offset = 0
    char = None
    length = 0
    for line in body.splitlines(keepends=True):
        line_body = line.rstrip("\r\n")
        if char is not None:
            if re.match(rf"^[ \t]*{re.escape(char)}{{{length},}}[ \t]*$", line_body):
                return offset + len(line)
        else:
            match = FENCE.match(line_body)
            if match and (match.group(1)[0] != "`" or "`" not in line_body[match.end():]):
                char = match.group(1)[0]
                length = len(match.group(1))
        offset += len(line)
    return None


def split_prompt_and_answer(title: str, body: str, parser: str, separator: str = "\n") -> tuple[str, str]:
    # Details-wrapped coding answers have the complete prompt before <details>.
    answer_details = re.search(r"<details[^>]*>\s*<summary[^>]*>\s*(?:<b>)?Answer(?:</b>)?\s*</summary>", body, flags=re.I)
    if answer_details and parser == "javascript":
        return title + separator + body[:answer_details.start()], body[answer_details.start():]
    marker = ANSWER_MARKER.search(body) if parser in {"go", "python"} else None
    if marker and marker.start() > 0:
        return title + separator + body[:marker.start()], body[marker.start():]
    output_question = re.search(r"(?i)(?:output.*(?:code|following)|following.*output|写出.*(?:打印|代码)|找出.*代码|下面.*代码|以下.*代码)", title)
    if output_question and parser in {"go", "python", "javascript"}:
        code_end = first_code_block_end(body)
        if code_end is not None and has_reference_answer(body[code_end:]):
            return title + separator + body[:code_end], body[code_end:]
    return title, body


def candidate_heading(heading: Heading, parser: str, relative: Path, section: str = "") -> bool:
    title = heading.title
    if parser in {"javascript", "react"}:
        return (heading.numbered
                or (parser == "javascript" and heading.level == 4 and re.match(r"\d+\.\s", title) is not None)
                or (heading.level == 3 and re.match(r"(?i)(?:what|why|how|when|is |can |should )", title) is not None))
    if parser == "python":
        return re.match(r"Q\.\s", title) is not None or (heading.level == 2 and re.match(r"(?i)(?:what|why|how|when|is |can |should )", title) is not None)
    if parser == "go" and relative.parts[0] == "question":
        if re.search(r"答案|解析|answer|solution", section, re.I):
            return False
        # Choices such as `### 1. D` under a solution heading are not new questions.
        if re.fullmatch(r"\d+[.)、．]\s*[A-DＡ-Ｄ]", title.strip(), re.I):
            return False
        return QUESTION_WORDS.search(title) is not None or re.match(r"\d+[、.．)]", title) is not None
    return QUESTION_WORDS.search(title) is not None and "面试" != title.strip()


def source_files(source: Source, source_root: Path) -> list[Path]:
    if source.parser in {"javascript", "react", "python"}:
        return [source_root / "README.md"]
    if source.parser == "javaguide":
        return sorted(path for path in (source_root / "docs").rglob("*.md")
                      if path.relative_to(source_root / "docs").parts[0] in JAVAGUIDE_DIRS
                      and path.name not in {"README.md", "TODO.md"})
    return sorted(path for directory in ("question", "base", "mysql", "redis", "llm")
                  for path in (source_root / directory).rglob("*.md") if path.name != "README.md")


def revision_for(source_root: Path) -> str:
    if not (source_root / ".git").exists():
        return "archive-unknown"
    return snapshot_commit(source_root)


def slug(value: str) -> str:
    cleaned = re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")
    return cleaned or hashlib.sha256(value.encode("utf-8")).hexdigest()[:12]


def grouping(source: Source, relative: Path, section: str) -> tuple[str, str, str]:
    if source.parser == "javaguide":
        parts = relative.parts
        group = "/".join(parts[1:-1])
        top = parts[1]
        if top == "java":
            domain = "Java"
        elif "spring" in parts:
            domain = "Spring Boot"
        elif top == "ai":
            domain = "Agent" if len(parts) > 2 and parts[2] == "agent" else "AI"
        else:
            domain = {"cs-basics": "计算机基础", "database": "数据库", "system-design": "系统设计",
                      "distributed-system": "分布式", "high-performance": "后端", "high-availability": "后端"}[top]
        topic_label = TOPIC_LABELS.get(group.split("/")[-1], group.split("/")[-1])
        return group, domain, f"{domain} / {topic_label}"
    if source.parser == "go":
        group = relative.parts[0]
        if group == "llm":
            return group, "AI", "大模型基础"
        if group in {"mysql", "redis"} or "redis" in relative.stem:
            return group + "/" + relative.stem, "数据库", relative.stem
        return group, "Go", {"question": "Go 编程与语法", "base": "Go 运行机制"}.get(group, group)
    if source.parser == "javascript":
        coding = section == "JavaScript 代码题"
        return "javascript-coding" if coding else "javascript-core", "前端", section
    if source.parser == "react":
        return section or source.key, "前端", section or source.name
    return section or "python", "Python", section or "Python 基础"


def extract_source(source: Source, source_root: Path) -> tuple[dict, dict]:
    revision = revision_for(source_root)
    ref = source.branch if revision == "archive-unknown" else revision
    topics = {}
    questions = {}
    reports = []
    skipped = Counter()
    for path in source_files(source, source_root):
        raw = path.read_bytes()
        content = raw.decode("utf-8-sig")
        relative = path.relative_to(source_root)
        file_sha = hashlib.sha256(raw).hexdigest()
        items = headings(content)
        count = 0
        section = ""
        for index, heading in enumerate(items):
            if is_section_heading(heading, source.parser) and not candidate_heading(heading, source.parser, relative, section):
                section = heading.title
            is_candidate = candidate_heading(heading, source.parser, relative, section)
            end = section_end(items, index, content, source.parser, relative)
            if (not is_candidate and source.parser == "go" and relative.parts[0] == "question" and index == 0
                    and ANSWER_MARKER.search(content[heading.body_start:end])):
                is_candidate = True
            if not is_candidate:
                continue
            body = content[heading.body_start:end]
            nav = GITHUB_TO_TOP.search(body)
            if nav:
                body = body[:nav.start()]
            stem, answer = split_prompt_and_answer(heading.title, body, source.parser, heading.separator)
            if not has_reference_answer(answer):
                skipped["empty_or_navigation_answer"] += 1
                continue
            # A source may explicitly say this is a question list with no answers.
            if "常见面试题：" in answer and answer.count("？") > 2 and len(answer) < 2000:
                skipped["question_list"] += 1
                continue
            question_section = section
            if source.parser == "javascript":
                question_section = "JavaScript 代码题" if heading.level == 4 else "JavaScript 基础"
            group, domain, title = grouping(source, relative, question_section)
            point = f"local-{source.key}-{slug(group)}"
            topics.setdefault(point, {"id": point, "domain": domain, "title": title,
                                      "summary": f"{source.name} 本地快照中的原文参考问答。", "prerequisites": []})
            identity = f"{relative.as_posix()}\0{stem}\0{answer}".encode("utf-8")
            question_id = f"local-{source.key}-" + hashlib.sha256(identity).hexdigest()[:24]
            if question_id in questions:
                skipped["duplicate_in_file"] += 1
                continue
            questions[question_id] = {
                "id": question_id, "point": point, "type": "short_answer", "stem": stem,
                "options": [], "answer": answer,
                "explanation": "原文参考答案，请结合原始来源核对并自评。", "rubric": [],
                "assessment_mode": "reference", "content_format": "verbatim-html-markdown",
                "source_name": source.name,
                "source_url": f"{source.repository}/blob/{ref}/{quote(relative.as_posix(), safe='/')}",
                "source_path": relative.as_posix(), "source_license": source.license,
                "source_attribution": source.attribution, "source_revision": revision,
                "source_sha256": file_sha, "source_material_id": None,
                "source_locale": "zh-CN" if source.parser in {"javaguide", "go"} else "en",
            }
            count += 1
        reports.append({"path": relative.as_posix(), "sha256": file_sha, "question_count": count})
    pack = {"topics": list(topics.values()), "questions": list(questions.values())}
    audit = {"source_key": source.key, "source_name": source.name, "repository": source.repository,
             "source_revision": revision, "source_license": source.license,
             "question_count": len(questions), "topic_count": len(topics), "skipped": dict(skipped), "files": reports,
             "notice": "本地归档未知提交号；来源链接指向当前分支，文件 SHA-256 用于复核本地内容。" if revision == "archive-unknown" else "来源链接固定到 Git 快照提交。"}
    return pack, audit


def validate_pack(pack: dict) -> None:
    if not isinstance(pack, dict) or not isinstance(pack.get("topics"), list) or not isinstance(pack.get("questions"), list):
        raise ValueError("learning pack must contain topics and questions arrays")
    points = {item["id"] for item in pack["topics"]}
    if len(points) != len(pack["topics"]):
        raise ValueError("duplicate topic id")
    for topic in pack["topics"]:
        for field in ("id", "domain", "title", "summary"):
            if not isinstance(topic.get(field), str) or not topic[field].strip():
                raise ValueError(f"missing topic text field {field}")
        prerequisites = topic.get("prerequisites")
        if not isinstance(prerequisites, list) or any(item not in points or item == topic["id"] for item in prerequisites):
            raise ValueError("invalid prerequisite topic reference")
    ids = set()
    for question in pack["questions"]:
        if question["id"] in ids or question["point"] not in points:
            raise ValueError("duplicate question id or missing topic reference")
        ids.add(question["id"])
        if question["type"] == "short_answer":
            if question["options"] or question["assessment_mode"] not in {"reference", "rubric"}:
                raise ValueError("short-answer assessment schema is invalid")
            if question["assessment_mode"] == "reference" and question["rubric"]:
                raise ValueError("reference assessment cannot claim a rubric")
            if question["assessment_mode"] == "rubric" and not question["rubric"]:
                raise ValueError("rubric assessment must provide self-assessment items")
        elif question["type"] == "single_choice":
            options = question["options"]
            if (question["assessment_mode"] != "rubric" or not isinstance(options, list)
                    or len(options) < 2 or len(set(options)) != len(options)
                    or question["answer"] not in options):
                raise ValueError("single-choice assessment schema is invalid")
        else:
            raise ValueError("unsupported question type")
        for field in ("stem", "answer", "explanation", "source_name", "source_url", "source_path", "source_license", "source_attribution", "source_revision", "source_sha256"):
            if not isinstance(question.get(field), str) or not question[field].strip():
                raise ValueError(f"missing text field {field}")
        source_url = urlsplit(question["source_url"])
        if source_url.scheme not in {"http", "https"} or not source_url.hostname or source_url.username is not None or source_url.password is not None:
            raise ValueError("source_url must be an http(s) URL without credentials")
        if not re.fullmatch(r"[0-9a-fA-F]{64}", question["source_sha256"]):
            raise ValueError("source_sha256 must contain 64 hexadecimal characters")
        for field in ("options", "rubric"):
            if not isinstance(question[field], list) or any(not isinstance(item, str) or not item.strip() for item in question[field]):
                raise ValueError(f"{field} must contain non-empty strings")


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def read_errata(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    value = json.loads(path.read_text(encoding="utf-8"))
    ids = value.get("excluded_question_ids")
    reasons = value.get("reason", {})
    if not isinstance(ids, list) or any(not isinstance(item, str) or not item.strip() for item in ids):
        raise ValueError("errata excluded_question_ids must be an array of non-empty IDs")
    if isinstance(reasons, str):
        reasons = {question_id: reasons for question_id in ids}
    if not isinstance(reasons, dict):
        raise ValueError("errata reason must be text or an ID-to-reason object")
    result = {}
    for question_id in ids:
        reason = reasons.get(question_id, "Locally excluded by a known erratum.")
        if not isinstance(reason, str) or not reason.strip():
            raise ValueError("errata reasons must be non-empty text")
        result[question_id] = reason
    return result


def apply_errata(pack: dict, errata: dict[str, str]) -> tuple[dict, set[str]]:
    skipped = {item["id"] for item in pack["questions"] if item["id"] in errata}
    filtered = {"topics": pack["topics"], "questions": [item for item in pack["questions"] if item["id"] not in skipped]}
    return filtered, skipped


def main() -> int:
    project_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-root", type=Path, default=project_root / ".cache" / "content")
    parser.add_argument("--output", type=Path, default=project_root / ".cache" / "content" / "local.learning.json")
    parser.add_argument("--extra-pack", "--additional-pack", type=Path, action="append", default=[],
                        help="Additional local learning pack to merge; may be repeated")
    parser.add_argument("--errata-file", type=Path, help="Local known-errata JSON; defaults to cache-root/known-errata.json")
    args = parser.parse_args()
    cache = args.cache_root.resolve()
    packs = []
    audits = []
    matched_errata = set()
    try:
        errata = read_errata(args.errata_file.resolve() if args.errata_file else cache / "known-errata.json")
        for source in SOURCES:
            source_root = cache / source.directory
            if not source_root.is_dir():
                audits.append({"source_key": source.key, "status": "missing local snapshot", "question_count": 0})
                continue
            pack, audit = extract_source(source, source_root)
            validate_pack(pack)
            pack, excluded = apply_errata(pack, errata)
            matched_errata.update(excluded)
            audit["extracted_question_count"] = audit["question_count"]
            audit["question_count"] = len(pack["questions"])
            audit["known_errata_skipped"] = len(excluded)
            write_json(cache / f"{source.key}.learning.json", pack)
            packs.append(pack)
            audits.append(audit)
            print(f"{source.key}: {len(pack['topics'])} topics, {len(pack['questions'])} questions, {source.license}")
        devops_root = cache / "devops-exercises"
        if devops_root.is_dir():
            commit = snapshot_commit(devops_root)
            records = []
            devops_files = []
            for path in discover_source_files(devops_root):
                extracted, report = extract_file(path, devops_root, commit)
                records.extend(extracted)
                devops_files.append(report)
            pack = build_learning_data(records)
            validate_pack(pack)
            pack, excluded = apply_errata(pack, errata)
            matched_errata.update(excluded)
            write_json(cache / "devops.learning.json", pack)
            packs.append(pack)
            audits.append({"source_key": "devops", "source_name": "DevOps Exercises", "source_revision": commit,
                           "source_license": "CC-BY-NC-ND-3.0", "question_count": len(pack["questions"]),
                           "extracted_question_count": len(records), "known_errata_skipped": len(excluded), "files": devops_files})
            print(f"devops: {len(pack['topics'])} topics, {len(pack['questions'])} questions, CC-BY-NC-ND-3.0")
        extra_paths = [cache / "llm.learning.json", cache / "exams.learning.json", *args.extra_pack]
        loaded_paths = set()
        for extra_path in extra_paths:
            extra_path = extra_path.resolve()
            if extra_path in loaded_paths or not extra_path.is_file():
                continue
            loaded_paths.add(extra_path)
            pack = json.loads(extra_path.read_text(encoding="utf-8"))
            validate_pack(pack)
            extracted_count = len(pack["questions"])
            pack, excluded = apply_errata(pack, errata)
            matched_errata.update(excluded)
            packs.append(pack)
            audits.append({"source_key": "extra-" + extra_path.stem, "pack_path": extra_path.name,
                           "pack_sha256": hashlib.sha256(extra_path.read_bytes()).hexdigest(),
                           "question_count": len(pack["questions"]), "topic_count": len(pack["topics"]),
                           "extracted_question_count": extracted_count, "known_errata_skipped": len(excluded)})
            print(f"{extra_path.name}: {len(pack['topics'])} topics, {len(pack['questions'])} questions")
        merged = {"topics": [item for pack in packs for item in pack["topics"]],
                  "questions": [item for pack in packs for item in pack["questions"]]}
        if not merged["topics"] or not merged["questions"]:
            raise ValueError("no eligible local questions found; download source snapshots before importing")
        validate_pack(merged)
        write_json(args.output.resolve(), merged)
        domains = Counter(item["domain"] for item in merged["topics"])
        topic_domain = {item["id"]: item["domain"] for item in merged["topics"]}
        question_domains = Counter(topic_domain[item["point"]] for item in merged["questions"])
        write_json(cache / "local.audit.json", {"sources": audits, "topic_count": len(merged["topics"]),
                   "question_count": len(merged["questions"]), "topics_by_domain": dict(domains),
                   "questions_by_domain": dict(question_domains),
                   "known_errata_skipped": len(matched_errata),
                   "excluded_question_ids": sorted(matched_errata),
                   "known_errata_reasons": {item: errata[item] for item in sorted(matched_errata)},
                   "unmatched_errata_ids": sorted(set(errata) - matched_errata),
                   "notice": "本地提取原文参考答案；未做事实审核。未知许可证不构成公开分发授权，所有内容留在 ignored cache。"})
    except (OSError, UnicodeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"local import failed: {error}", file=sys.stderr)
        return 1
    print(f"merged: {len(merged['topics'])} topics, {len(merged['questions'])} questions")
    print(f"output: {args.output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
