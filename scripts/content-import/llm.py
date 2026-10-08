#!/usr/bin/env python3
"""Import question-shaped Markdown sections from a local LLM interview snapshot.

This tool performs no network requests. Source text and generated packs belong
under .cache/content/, which is ignored by Git.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote

from devops import mask_fenced_code, snapshot_commit, visible_text


REPOSITORY = "https://github.com/wdndev/llm_interview_note"
SOURCE_NAME = "LLM Interview Note"
HEADING = re.compile(r"(?m)^[ \t]{0,3}(#{1,6})[ \t]+(.+?)[ \t]*#*[ \t]*$")
QUESTION = re.compile(r"[?？]|什么|为何|为什么|如何|怎么|区别|比较|优缺点|优点|缺点|是否|怎样|多少|有哪些|是啥|简述|解释|对比")
NUMBERED = re.compile(r"^\s*(?:\d+[.、．:)）]\s*)+")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sections(markdown: str) -> list[tuple[str, str]]:
    """Keep nested answer headings and ignore headings inside fenced code."""
    headings = list(HEADING.finditer(mask_fenced_code(markdown)))
    result: list[tuple[str, str]] = []
    for index, heading in enumerate(headings):
        title = heading.group(2).strip()
        level = len(heading.group(1))
        # A file/chapter title and a numbered subsection are not automatically
        # questions. Import only question-shaped level 2/3 headings.
        if level < 2 or level > 3 or not QUESTION.search(title):
            continue
        end = len(markdown)
        for following in headings[index + 1:]:
            if len(following.group(1)) <= level:
                end = following.start()
                break
        answer = markdown[heading.end():end].strip()
        # Ignore headings with no answer and navigation-only sections. The
        # retained reference answer remains source text, without translation.
        if len(visible_text(answer)) < 40:
            continue
        result.append((title, answer))
    return result


def build_pack(source_root: Path, revision: str) -> tuple[dict, dict]:
    topics: list[dict] = []
    questions: list[dict] = []
    reports: list[dict] = []
    seen: set[tuple[str, str]] = set()
    duplicate_count = 0

    for source_file in sorted(source_root.rglob("*.md")):
        relative = source_file.relative_to(source_root).as_posix()
        if not re.match(r"^(?:0[1-9]|10)\.", relative):
            continue
        raw = source_file.read_bytes()
        text = raw.decode("utf-8-sig")
        file_hash = sha256(raw)
        topic_id = "llm-topic-" + sha256(relative.encode("utf-8"))[:16]
        file_questions: list[dict] = []
        for title, answer in sections(text):
            pair = (title, answer)
            if pair in seen:
                duplicate_count += 1
                continue
            seen.add(pair)
            identity = "\0".join((relative, title, answer)).encode("utf-8")
            file_questions.append({
                "id": "llm-" + sha256(identity)[:20],
                "point": topic_id,
                "type": "short_answer",
                "stem": title,
                "options": [],
                "answer": answer,
                "explanation": "本地导入的原文参考答案，尚未经过本项目人工审核。请结合来源核对。",
                "rubric": [],
                "assessment_mode": "reference",
                "content_format": "markdown",
                "status": "imported",
                "source_material_id": None,
                "source_name": SOURCE_NAME,
                "source_url": f"{REPOSITORY}/blob/{revision}/{quote(relative, safe='/')}",
                "source_path": relative,
                "source_license": "LicenseRef-Unspecified",
                "source_attribution": "wdndev/llm_interview_note contributors; see item references",
                "source_revision": revision,
                "source_sha256": file_hash,
                "source_locale": "zh-CN",
            })
        if file_questions:
            title = NUMBERED.sub("", source_file.stem)
            chapter = NUMBERED.sub("", source_file.relative_to(source_root).parts[0])
            domain = "Agent 与大模型应用" if relative.startswith("10.") else "AI 与大模型"
            topics.append({
                "id": topic_id,
                "domain": domain,
                "title": title,
                "summary": f"{SOURCE_NAME} · {chapter}；原文参考题，待核对。",
                "prerequisites": [],
            })
            questions.extend(file_questions)
        reports.append({"path": relative, "sha256": file_hash, "question_count": len(file_questions)})

    if not questions:
        raise ValueError("no question/answer sections found; check the Markdown snapshot")
    return {"topics": topics, "questions": questions}, {
        "repository": REPOSITORY,
        "revision": revision,
        "license": "LicenseRef-Unspecified",
        "topic_count": len(topics),
        "question_count": len(questions),
        "skipped_exact_duplicates": duplicate_count,
        "files": reports,
    }


def main() -> int:
    cache = Path(__file__).resolve().parents[2] / ".cache" / "content"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=cache / "llm-interview-note")
    parser.add_argument("--output", type=Path, default=cache / "llm.learning.json")
    parser.add_argument("--audit-output", type=Path, default=cache / "llm.audit.json")
    args = parser.parse_args()
    try:
        source_root = args.source_root.resolve()
        revision = snapshot_commit(source_root)
        pack, audit = build_pack(source_root, revision)
        for destination, data in ((args.output, pack), (args.audit_output, audit)):
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({key: value for key, value in audit.items() if key != "files"}, ensure_ascii=False))
    except (OSError, UnicodeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"LLM import failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
