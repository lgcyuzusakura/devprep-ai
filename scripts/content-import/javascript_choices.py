"""Import the existing Chinese edition of javascript-questions, sequentially, without models.

Downloaded question bodies stay in the ignored local pack. No Nowcoder login is used.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

import requests

REPOSITORY = "lydiahallie/javascript-questions"
SOURCE_PATH = "zh-CN/README-zh_CN.md"
TOPIC = "local-javascript-choices"


def parse_questions(markdown, revision):
    questions, skipped = [], []
    digest = hashlib.sha256(markdown.encode("utf-8")).hexdigest()
    sections = re.split(r"(?m)^######\s+(\d+)\.\s*", markdown)
    for index in range(1, len(sections), 2):
        number, block = sections[index:index + 2]
        before, separator, explanation = block.partition("<details>")
        matches = list(re.finditer(r"(?m)^- ([A-H]):\s*", before))
        answer = re.search(r"(?m)^####\s*答案\s*[:：]\s*([A-H])\s*$", explanation)
        if not separator or len(matches) < 2 or not answer:
            skipped.append({"number": int(number), "reason": "not an unambiguous single-choice question"})
            continue
        stem = before[:matches[0].start()].strip()
        options = [before[m.end():matches[n+1].start() if n+1 < len(matches) else len(before)].strip()
                   for n, m in enumerate(matches)]
        labels = [m.group(1) for m in matches]
        if not re.search(r"[\u4e00-\u9fff]", stem) or len(set(options)) != len(options) or answer.group(1) not in labels:
            skipped.append({"number": int(number), "reason": "non-Chinese stem or invalid options"})
            continue
        explanation = explanation[answer.end():].split("</details>", 1)[0]
        explanation = re.sub(r"</?(?:p|b|summary)>|<summary>.*?</summary>", "", explanation, flags=re.S).strip()
        if not explanation:
            skipped.append({"number": int(number), "reason": "missing explanation"})
            continue
        questions.append({
            "id": f"local-js-choice-{int(number):03d}", "point": TOPIC, "type": "single_choice",
            "stem": stem, "options": options, "answer": options[labels.index(answer.group(1))],
            "explanation": explanation, "rubric": [], "assessment_mode": "rubric",
            "content_format": "verbatim-html-markdown", "status": "imported",
            "source_name": "JavaScript Questions（原项目简体中文版）",
            "source_url": f"https://github.com/{REPOSITORY}/blob/{revision}/{SOURCE_PATH}#L" + str(markdown[:markdown.index(f"###### {number}.")].count("\n") + 1),
            "source_path": SOURCE_PATH, "source_license": "MIT",
            "source_attribution": "Lydia Hallie 与 javascript-questions 简体中文译者；MIT License",
            "source_revision": revision, "source_sha256": digest,
            "source_locale": "zh-CN", "content_locale": "zh-CN",
        })
    return questions, skipped


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--merge", type=Path, required=True, help="Existing private learning pack")
    parser.add_argument("--output", type=Path, required=True, help="New private learning pack")
    parser.add_argument("--cache-dir", type=Path, default=Path(".cache/content/javascript-choices"))
    args = parser.parse_args()
    session = requests.Session()
    response = session.get(f"https://api.github.com/repos/{REPOSITORY}/commits/master", timeout=30)
    response.raise_for_status()
    revision = response.json()["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Invalid source revision")
    response = session.get(f"https://raw.githubusercontent.com/{REPOSITORY}/{revision}/{SOURCE_PATH}", timeout=30)
    response.raise_for_status()
    response.encoding = "utf-8"
    markdown = response.text
    license_response = session.get(f"https://raw.githubusercontent.com/{REPOSITORY}/{revision}/LICENSE", timeout=30)
    license_response.raise_for_status()
    if "Permission is hereby granted" not in license_response.text:
        raise ValueError("Expected MIT license")
    questions, skipped = parse_questions(markdown, revision)
    if len(questions) < 100:
        raise ValueError("Unexpected source shape; refusing partial import")
    pack = json.loads(args.merge.read_text(encoding="utf-8"))
    pack["topics"] = [t for t in pack["topics"] if t["id"] != TOPIC] + [{
        "id": TOPIC, "domain": "前端", "title": "JavaScript 选择题",
        "summary": "覆盖 JavaScript 类型、作用域、异步、对象与运行机制；保留原项目中文解释。", "prerequisites": [],
    }]
    # Stable IDs make repeated imports idempotent without replacing unrelated translations.
    pack["questions"] = [q for q in pack["questions"] if not q["id"].startswith("local-js-choice-")] + questions
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    (args.cache_dir / "source.md").write_text(markdown, encoding="utf-8")
    (args.cache_dir / "LICENSE").write_text(license_response.text, encoding="utf-8")
    audit = {"repository": REPOSITORY, "revision": revision, "imported": len(questions), "skipped": skipped,
             "model_calls": 0, "total_questions": len(pack["questions"])}
    (args.cache_dir / "audit.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(args.output.name + ".tmp")
    temporary.write_text(json.dumps(pack, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(args.output)
    print(json.dumps(audit, ensure_ascii=False))


if __name__ == "__main__":
    main()
