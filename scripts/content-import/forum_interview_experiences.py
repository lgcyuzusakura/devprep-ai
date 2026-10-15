"""Extract question headings from a pinned, locally cloned forum-memo archive.

No network or model calls. Optional --verified supplies locally checked original
post metadata; archive explanations are never imported as candidate answers.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import quote

spec = importlib.util.spec_from_file_location("candidate_import", Path(__file__).with_name("candidate_recollections.py"))
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)
REPOSITORY = "https://github.com/aqjsp/Interview-experience"
ORIGINAL = re.compile(r"https://www\.nowcoder\.com/(?:discuss/\d+|feed/main/detail/[a-f0-9]+)")


def extract(source, revision, verified=()):
    checked = {record["repository_document"]: record for record in verified if record.get("content")}
    result, seen = [], set()
    for file in sorted(source.rglob("*.md")):
        relative = file.relative_to(source).as_posix()
        if len(file.relative_to(source).parts) != 3:
            continue
        content = file.read_text(encoding="utf-8-sig")
        link = ORIGINAL.search(content)
        if not link:
            continue
        role = relative.split("/", 1)[0] + " 开发"
        company = shared.company_of(relative)
        if company == "未披露公司":
            company = file.parent.name.removesuffix("面经")
        if company in {"其它", "其他", "其他游戏公司"}:
            company = "未披露公司"
        record = checked.get(relative, {})
        published = record.get("published_at")
        date = datetime.fromtimestamp(published / 1000, timezone.utc).strftime("%Y-%m-%d") if published else "未核对"
        round_match = re.search(r"[一二三四五六]+面", file.stem)
        round_name = round_match.group(0) if round_match else "未注明（见原帖）"
        for line, value in enumerate(content.splitlines(), 1):
            # Only top-level numbered question headings, never numbered answers.
            heading = re.match(r"^#{2,4}\s+(?:\*\*)?\d+[、.．)）:：]\s*(.+)$", value)
            if not heading:
                if re.match(r"^#{1,3}\s+(?:[一二三四五六]+面|第[一二三四五六]+轮)", value):
                    round_name = shared.shared.clean(value)
                continue
            prompt = shared.shared.clean(heading.group(1))
            if not shared.extract_questions("1、" + prompt):
                continue
            key = (company, re.sub(r"\W+", "", prompt).lower())
            if key in seen:
                continue
            seen.add(key)
            kind, difficulty, tags = shared.shared.classify(prompt, "", role)
            digest = hashlib.sha256((relative + ":" + prompt).encode()).hexdigest()[:16]
            result.append({
                "id": "exp-forum-" + digest, "company": company, "role": role,
                "round": round_name, "source_date": date,
                "title": prompt[:110], "prompt": prompt, "kind": kind,
                "difficulty": difficulty, "tags": tags,
                "reference": "候选人问题记录，仅供出题参考；不采用整理者补写的答案，不假设候选人拥有原作者经历。",
                "rubric": ["说明原理与适用边界", "结合本人经历给出可验证的推理"],
                "source_kind": "candidate_recollection", "source_name": "牛客面经（aqjsp 归档）",
                "source_url": link.group(0), "source_revision": revision,
                "source_document": link.group(0), "archive_document": "aqjsp/" + relative,
                "archive_url": f"{REPOSITORY}/blob/{revision}/{quote(relative, safe='/')}#L{line}",
                "attribution": record.get("author", "原帖作者未核对；整理：aqjsp"),
                "source_verification": "已打开原帖核对正文与作者；题目取自归档，未逐题核对" if record else "据归档提取，保留原帖链接；未逐帖核对",
                "license": shared.shared.LICENSE,
                "difficulty_basis": "按问题内容估计，非公司或作者评级",
                "modifications": "仅抽取编号问题标题；不导入补写讲解、代码、评论或个人履历。发布日期不作为面试发生日期。",
            })
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--verified", type=Path)
    args = parser.parse_args()
    remote = subprocess.check_output(["git", "-C", str(args.source), "remote", "get-url", "origin"], text=True).strip().removesuffix(".git")
    if remote != REPOSITORY:
        parser.error("请使用 aqjsp/Interview-experience 归档")
    revision = subprocess.check_output(["git", "-C", str(args.source), "rev-parse", "HEAD"], text=True).strip()
    entries = extract(args.source, revision, json.loads(args.verified.read_text(encoding="utf-8")) if args.verified else ())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"schema_version": 1, "repository": remote, "revision": revision,
        "notice": "第三方面经仅保存在本地私人题库，不随公开仓库分发；上游未声明许可证。", "experiences": entries}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"questions": len(entries), "documents": len({e['source_document'] for e in entries})}, ensure_ascii=False))


if __name__ == "__main__":
    main()
