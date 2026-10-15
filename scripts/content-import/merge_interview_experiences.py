"""Merge local interview packs in priority order without network/model requests."""
import argparse
import json
from pathlib import Path
import re
import unicodedata


def merge(packs):
    entries, ids, keys, sources = [], set(), set(), []
    for pack in packs:
        if not isinstance(pack.get("experiences"), list):
            raise ValueError("面经包缺少 experiences 数组")
        if pack.get("repository"):
            sources.append({key: pack[key] for key in ("repository", "revision", "license", "notice") if key in pack})
        for entry in pack["experiences"]:
            prompt = entry.get("prompt", "")
            if not entry.get("id") or not re.search(r"[\u4e00-\u9fff]", prompt):
                raise ValueError("条目缺少 ID 或中文题面")
            key = (entry.get("company", ""), re.sub(r"\W+", "", unicodedata.normalize("NFKC", prompt)).casefold())
            if entry["id"] in ids or key in keys:
                continue
            ids.add(entry["id"])
            keys.add(key)
            entries.append(entry)
    return {"schema_version": 1, "notice": "第三方资料仅用于本地私人学习；逐条保留原有来源与权利说明，不上传公开仓库。",
            "sources": sources, "experiences": entries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = merge([json.loads(path.read_text(encoding="utf-8")) for path in args.input])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Write in place so a running Docker single-file bind mount sees the update.
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"questions": len(result["experiences"]), "documents": len({e.get("source_document", e.get("source_url")) for e in result["experiences"]})}))


if __name__ == "__main__":
    main()
