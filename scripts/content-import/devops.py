#!/usr/bin/env python3
"""Extract verbatim question/answer blocks from a local DevOps Exercises snapshot."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path


REPOSITORY_URL = "https://github.com/bregman-arie/devops-exercises"
LICENSE_NAME = "Creative Commons Attribution-NonCommercial-NoDerivs 3.0 Unported"
LICENSE_ID = "CC-BY-NC-ND-3.0"
LICENSE_URL = "https://creativecommons.org/licenses/by-nc-nd/3.0/"
SOURCE_NAME = "DevOps Exercises"
FENCE_START = re.compile(r"^[ \t]{0,3}(`{3,}|~{3,})")
WORD_OR_NUMBER = re.compile(r"[\w]", re.UNICODE)
BLOCK_TAGS = {"br", "div", "p", "li", "pre", "tr", "h1", "h2", "h3", "h4"}


@dataclass
class DetailBlock:
    start: int
    nested: bool
    summary_start: int | None = None
    summary_end: int | None = None
    summary_active: bool = False
    answer_start: int | None = None
    answer_end: int | None = None
    answer_depth: int = 0
    end: int | None = None


class DetailParser(HTMLParser):
    """Collect source spans without rewriting markup or fenced code blocks."""

    def __init__(self, source: str, masked_source: str):
        super().__init__(convert_charrefs=False)
        self.source = source
        self.line_offsets = [0]
        self.line_offsets.extend(index + 1 for index, char in enumerate(masked_source) if char == "\n")
        self.stack: list[DetailBlock] = []
        self.blocks: list[DetailBlock] = []
        self.details_opened = 0
        self.details_closed = 0
        self.unmatched_closing = 0

    def _offset(self) -> int:
        line, column = self.getpos()
        return self.line_offsets[line - 1] + column

    def _start_tag_end(self) -> int:
        return self._offset() + len(self.get_starttag_text() or "")

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "details":
            self.details_opened += 1
            self.stack.append(DetailBlock(start=self._offset(), nested=bool(self.stack)))
            return
        if not self.stack:
            return

        block = self.stack[-1]
        if tag == "summary" and block.summary_start is None:
            block.summary_start = self._start_tag_end()
            block.summary_active = True
        elif tag == "b" and block.summary_end is not None:
            if block.answer_start is None:
                block.answer_start = self._start_tag_end()
                block.answer_depth = 1
            elif block.answer_depth:
                block.answer_depth += 1

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)
        self.handle_endtag(tag)

    def handle_endtag(self, tag: str) -> None:
        offset = self._offset()
        if tag == "summary" and self.stack:
            block = self.stack[-1]
            if block.summary_active:
                block.summary_end = offset
                block.summary_active = False
        elif tag == "b" and self.stack:
            block = self.stack[-1]
            if block.answer_depth:
                block.answer_depth -= 1
                if block.answer_depth == 0:
                    block.answer_end = offset
        elif tag == "details":
            if not self.stack:
                self.unmatched_closing += 1
                return
            block = self.stack.pop()
            block.end = offset
            self.details_closed += 1
            if not block.nested:
                self.blocks.append(block)


class VisibleTextParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in BLOCK_TAGS:
            self.parts.append(" ")
        elif tag == "img":
            alt = dict(attrs).get("alt")
            if alt:
                self.parts.append(alt)

    def handle_endtag(self, tag: str) -> None:
        if tag in BLOCK_TAGS:
            self.parts.append(" ")

    def handle_data(self, data: str) -> None:
        self.parts.append(data)

    def handle_entityref(self, name: str) -> None:
        self.parts.append(html.unescape(f"&{name};"))

    def handle_charref(self, name: str) -> None:
        self.parts.append(html.unescape(f"&#{name};"))


def mask_fenced_code(source: str) -> str:
    """Hide fenced-code contents from HTML tokenization while preserving offsets."""
    masked = list(source)
    fence_char: str | None = None
    fence_length = 0
    offset = 0

    for line in source.splitlines(keepends=True):
        line_body = line.rstrip("\r\n")
        match = FENCE_START.match(line_body)
        if fence_char is None and match:
            fence_char = match.group(1)[0]
            fence_length = len(match.group(1))
        elif fence_char is not None:
            close = re.match(rf"^[ \t]{{0,3}}{re.escape(fence_char)}{{{fence_length},}}[ \t]*$", line_body)
            if close:
                fence_char = None
                fence_length = 0
            else:
                for index in range(offset, offset + len(line_body)):
                    masked[index] = " "
        offset += len(line)

    return "".join(masked)


def visible_text(value: str) -> str:
    parser = VisibleTextParser()
    parser.feed(value)
    parser.close()
    return " ".join("".join(parser.parts).split())


def has_content(value: str) -> bool:
    return WORD_OR_NUMBER.search(visible_text(value)) is not None


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def discover_source_files(source_root: Path) -> list[Path]:
    files = [source_root / "README.md", source_root / "README-zh_CN.md"]
    files.extend((source_root / "topics").rglob("*.md"))
    files.extend((source_root / "certificates").rglob("*.md"))
    return sorted(path for path in files if path.is_file())


def locale_for(path: Path, source_root: Path) -> str:
    if path.name == "README-zh_CN.md":
        return "zh-CN"
    return "en"


def topic_for(path: Path, source_root: Path) -> str:
    relative = path.relative_to(source_root)
    parts = relative.parts
    if parts[0] == "topics" and len(parts) > 1:
        return parts[1]
    if parts[0] == "certificates":
        return "certificates"
    return "general"


def learning_topic_for(question: dict) -> tuple[str, str, str]:
    """Return topic id, display title, and source-path grouping for the learning schema."""
    source_path = Path(question["source_path"])
    parts = source_path.parts
    if parts[0] == "topics" and len(parts) > 1:
        slug = parts[1]
        source_group = f"topics/{slug}"
    elif parts[0] == "certificates" and len(parts) > 1:
        slug = f"certificate-{source_path.stem}"
        source_group = f"certificates/{source_path.stem}"
    else:
        slug = "general"
        source_group = "README"

    normalized = re.sub(r"[^a-z0-9]+", "-", slug.lower()).strip("-") or "general"
    topic_id = f"devops-{normalized}"
    title_slug = slug.removeprefix("certificate-")
    title = title_slug.replace("_", " ").replace("-", " ").title()
    if title_slug.lower() in {"aws", "gcp", "cka", "ckad", "dns", "sre", "cicd"}:
        title = title_slug.upper()
    if slug.startswith("certificate-"):
        title = f"Certificate: {title}"
    elif slug == "general":
        title = "General"
    return topic_id, title, source_group


def build_learning_data(questions: list[dict]) -> dict:
    topics: dict[str, dict] = {}
    learning_questions: list[dict] = []

    for question in questions:
        topic_id, title, source_group = learning_topic_for(question)
        topics.setdefault(topic_id, {
            "id": topic_id,
            "domain": "DevOps & SRE",
            "title": title,
            "summary": f"Verbatim question and answer material from {source_group}.",
            "prerequisites": [],
        })
        learning_questions.append({
            "id": question["id"],
            "point": topic_id,
            "type": "short_answer",
            "stem": question["question"],
            "options": [],
            "answer": question["answer"],
            "explanation": "Reference answer from the source snapshot; compare your response with it.",
            "rubric": [],
            "assessment_mode": "reference",
            "content_format": question["content_format"],
            "source_name": SOURCE_NAME,
            "source_url": question["source_url"],
            "source_path": question["source_path"],
            "source_license": question["license"],
            "source_attribution": question["attribution"],
            "source_revision": question["source_url"].split("/blob/", 1)[1].split("/", 1)[0],
            "source_sha256": question["source_sha256"],
            "source_material_id": None,
            "source_locale": question["locale"],
        })

    return {"topics": list(topics.values()), "questions": learning_questions}


def extract_file(path: Path, source_root: Path, commit: str) -> tuple[list[dict], dict]:
    raw = path.read_bytes()
    source = raw.decode("utf-8-sig")
    masked_source = mask_fenced_code(source)
    parser = DetailParser(source, masked_source)
    parser.feed(masked_source)
    parser.close()

    relative = path.relative_to(source_root).as_posix()
    file_hash = sha256(raw)
    questions: list[dict] = []
    skipped_invalid = 0

    for block in parser.blocks:
        if (
            block.summary_start is None
            or block.summary_end is None
            or block.answer_start is None
            or block.answer_end is None
            or block.end is None
        ):
            skipped_invalid += 1
            continue

        question = source[block.summary_start:block.summary_end]
        answer = source[block.answer_start:block.answer_end]
        if not has_content(question) or not has_content(answer):
            skipped_invalid += 1
            continue

        identity = f"{relative}\0{file_hash}\0{block.start}\0{question}\0{answer}".encode("utf-8")
        questions.append({
            "id": "devops-" + hashlib.sha256(identity).hexdigest()[:20],
            "question": question,
            "answer": answer,
            "content_format": "verbatim-html-markdown",
            "topic": topic_for(path, source_root),
            "locale": locale_for(path, source_root),
            "source_path": relative,
            "source_url": f"{REPOSITORY_URL}/blob/{commit}/{relative}",
            "source_sha256": file_hash,
            "license": LICENSE_ID,
            "attribution": "bregman-arie/devops-exercises contributors",
        })

    file_report = {
        "path": relative,
        "sha256": file_hash,
        "details_opened": parser.details_opened,
        "details_closed": parser.details_closed,
        "question_count": len(questions),
        "skipped_invalid": skipped_invalid,
        "unclosed_details": len(parser.stack),
        "unmatched_closing_details": parser.unmatched_closing,
    }
    return questions, file_report


def snapshot_commit(source_root: Path) -> str:
    result = subprocess.run(
        ["git", "-C", str(source_root), "rev-parse", "--show-prefix"],
        check=True,
        capture_output=True,
        text=True,
    )
    if result.stdout.strip():
        raise ValueError("source snapshot path is inside another Git worktree, not its root")
    commit_result = subprocess.run(
        ["git", "-C", str(source_root), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    )
    return commit_result.stdout.strip()


def main() -> int:
    project_root = Path(__file__).resolve().parents[2]
    default_source = project_root / ".cache" / "content" / "devops-exercises"
    default_output = project_root / ".cache" / "content" / "devops.questions.json"
    default_learning_output = project_root / ".cache" / "content" / "devops.learning.json"

    argument_parser = argparse.ArgumentParser(description=__doc__)
    argument_parser.add_argument("--source-root", type=Path, default=default_source)
    argument_parser.add_argument("--output", type=Path, default=default_output)
    argument_parser.add_argument("--learning-output", type=Path, default=default_learning_output)
    args = argument_parser.parse_args()

    source_root = args.source_root.resolve()
    output = args.output.resolve()
    learning_output = args.learning_output.resolve()
    if not source_root.is_dir():
        argument_parser.error(f"source snapshot directory does not exist: {source_root}")

    try:
        commit = snapshot_commit(source_root)
        files = discover_source_files(source_root)
        if not files:
            raise ValueError("no eligible README, topic, or certificate Markdown files found")

        questions: list[dict] = []
        file_reports: list[dict] = []
        for source_file in files:
            extracted, file_report = extract_file(source_file, source_root, commit)
            questions.extend(extracted)
            file_reports.append(file_report)

        exact_pairs: dict[tuple[str, str], int] = {}
        for question in questions:
            pair = (question["question"], question["answer"])
            exact_pairs[pair] = exact_pairs.get(pair, 0) + 1
        duplicate_pair_count = sum(count > 1 for count in exact_pairs.values())
        duplicate_row_count = sum(count - 1 for count in exact_pairs.values() if count > 1)

        result = {
            "schema_version": 1,
            "source": {
                "repository": REPOSITORY_URL,
                "snapshot_commit": commit,
                "license": {
                    "id": LICENSE_ID,
                    "name": LICENSE_NAME,
                    "url": LICENSE_URL,
                    "declared_by_upstream": True,
                    "notice": (
                        "The upstream README declares CC BY-NC-ND 3.0. Attribution is required, "
                        "commercial use is prohibited, and adaptations are not permitted. Verify "
                        "item-specific rights before public redistribution; this export is a local snapshot."
                    ),
                },
            },
            "statistics": {
                "source_file_count": len(file_reports),
                "question_count": len(questions),
                "details_opened": sum(report["details_opened"] for report in file_reports),
                "details_closed": sum(report["details_closed"] for report in file_reports),
                "skipped_invalid": sum(report["skipped_invalid"] for report in file_reports),
                "unclosed_details": sum(report["unclosed_details"] for report in file_reports),
                "unmatched_closing_details": sum(report["unmatched_closing_details"] for report in file_reports),
                "exact_duplicate_pair_count": duplicate_pair_count,
                "duplicate_question_answer_rows": duplicate_row_count,
            },
            "files": file_reports,
            "questions": questions,
        }
        learning_data = build_learning_data(questions)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        learning_output.parent.mkdir(parents=True, exist_ok=True)
        learning_output.write_text(json.dumps(learning_data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, UnicodeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"devops import failed: {error}", file=sys.stderr)
        return 1

    print(json.dumps(result["statistics"], ensure_ascii=False))
    print(f"output: {output}")
    print(f"learning output: {learning_output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
