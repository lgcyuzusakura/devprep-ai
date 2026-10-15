"""Extract local study questions from a pinned, user-supplied checkout. No model calls.

Text is NOT bundled with public code. The output retains source, author and license.
Usage: python scripts/content-import/interview_experiences.py --source PATH --output .cache/content/interview-experiences.json
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import quote

REPOSITORY = "https://github.com/0voice/Campus_recruitment_interview_questions"
LICENSE = "CC-BY-NC-SA-4.0 (upstream declaration; original authors retain rights)"
EXCLUDED = ("自我介绍", "反问", "职业规划", "薪资", "离职原因", "作者：", "面试官", "面试感受", "编程语言是", "有没有offer")


def clean(line):
    line = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", line)
    line = re.sub(r"<[^>]+>", "", line)
    line = re.sub(r"^[#\s\d.、)）(（•*\-\u200b\ufeff:：]+", "", line)
    return line.strip(" *`：:\u00a0\u200b")


def classify(prompt, context=""):
    text = (prompt + " " + context).lower()
    tags = set()
    groups = {
        "java": ("java", "jvm", "spring", "juc", "synchronized", "hashmap", "arraylist", "redisson"),
        "c++": ("c++", "智能指针", "虚函数", "shared_ptr", "unique_ptr", "构造函数", "析构函数", "malloc"),
        "go": ("golang", "goroutine", "go语言", "go 语言", "channel", "gmp"),
        "python": ("python", "gil", "迭代器", "装饰器"),
        "前端": ("前端", "react", "vue", "css", "html", "javascript", "promise", "浏览器", "js ", "js实现"),
        "ai": ("llm", "大模型", "rag", "sft", "dpo", "ppo", "grpo", "机器学习", "迁移学习", "模型", "agent", "word2vec"),
        "数据库": ("mysql", "redis", "sql", "数据库", "索引", "事务", "缓存"),
        "网络": ("tcp", "http", "网络", "udp", "socket", "cdn", "websocket", "sse"),
        "并发": ("并发", "锁", "线程", "原子", "竞态", "goroutine"),
        "系统设计": ("分布式", "高可用", "系统设计", "一致性", "容灾", "负载均衡"),
        "排障": ("排查", "排障", "故障", "慢查询", "定位"),
        "原理": ("原理", "底层", "机制"),
        "项目": ("项目", "实习难点", "本人", "你负责", "你做过"),
        "算法": ("算法", "手写", "手撕", "三数之和", "快排", "链表", "二叉树", "lca", "dfs", "bfs"),
    }
    for tag, words in groups.items():
        if any(word in text for word in words):
            tags.add(tag)
    # Shared systems foundations match back-end roles, but not unrelated front-end/AI-only items.
    if tags & {"java", "go", "python", "数据库", "并发"}:
        tags.add("后端")
    if not tags or tags <= {"原理", "网络", "排障", "系统设计", "项目", "算法"}:
        tags.add("通用")
    kind = "project" if "项目" in tags else "algorithm" if "算法" in tags else "knowledge"
    if any(word in prompt.lower() for word in ("分布式", "容灾", "系统设计", "高可用", "十倍", "百万", "容量", "grpo", "ppo", "fiber", "无锁", "一致性", "故障恢复")):
        difficulty = "advanced"
    elif any(word in prompt.lower() for word in ("原理", "底层", "机制", "优化", "设计", "排查", "索引失效", "为什么", "如何实现", "怎么实现", "取舍", "dpo", "迁移学习")):
        difficulty = "intermediate"
    else:
        difficulty = "basic"
    return kind, difficulty, sorted(tags)


def extract(source, revision):
    result, seen = [], set()
    for file in sorted(source.rglob("*")):
        relative = file.relative_to(source)
        if file.suffix.lower() not in (".md", ".txt") or len(relative.parts) != 2:
            continue
        recollection = relative.parts[0] == "面经"
        company = re.sub(r"面试题视频讲解$", "", relative.parts[0])
        if not recollection and any(word in company.lower() for word in ("相关", "技术分享", "简历", "私募", "算法", "面试题合集")):
            continue
        if not recollection and ("面试题视频讲解" not in relative.parts[0] and company not in ("阿里", "腾讯", "百度", "京东", "网易", "搜狐")):
            continue
        content = file.read_text(encoding="utf-8-sig")
        author = re.search(r"作者[：:]\s*(.+)", content)
        author = author.group(1).strip() if author else "未署名（上游整理：0voice）"
        if recollection:
            company = next((name for name in ("腾讯", "美团", "百度", "华为", "安克创新", "多益网络", "饿了么", "好未来", "科大讯飞", "淘天", "搜狐", "字节", "小红书", "阿里", "京东", "数字马力") if name in file.stem), "未披露公司")
        round_name = "未注明"
        for number, line in enumerate(content.splitlines(), 1):
            title = clean(line)
            if re.fullmatch(r"(?:第?[一二三四五12345]+[轮面](?:技术面|面试)?|[一二三四五]+面|第[一二三]+轮技术面|[一二三四五]+面[（(].*[）)])\s*", title):
                round_name = title
                continue
            if recollection:
                if not (re.match(r"^\s*(?:#{1,6}\s*)?(?:\d+[.、)）]|[•\-])", line) or any(word in title.lower() for word in ("？", "?", "原理", "区别", "机制", "实现", "优化", "算法", "手写", "手撕", "复杂度"))):
                    continue
                if "hr" in round_name.lower():
                    continue
            elif not re.match(r"^#{2,6}\s+\d+[.、]", line):
                continue
            if not 7 <= len(title) <= 240 or len(re.findall(r"[\u4e00-\u9fff]", title)) < 4 or any(word in title for word in EXCLUDED + ("动画演示", "视频讲解", "一起学习", "简历修改")):
                continue
            title = title.replace("`", "")
            key = (company, re.sub(r"\W+", "", title).lower())
            if key in seen:
                continue
            seen.add(key)
            kind, difficulty, tags = classify(title, file.stem if recollection else "")
            source_url = f"{REPOSITORY}/blob/{revision}/{quote(relative.as_posix(), safe='/')}#L{number}"
            digest = hashlib.sha256((relative.as_posix() + ":" + title).encode()).hexdigest()[:16]
            result.append({
                "id": "exp-" + digest, "company": company, "round": round_name,
                "kind": kind, "difficulty": difficulty, "tags": tags,
                "title": title[:110], "prompt": title,
                "reference": "来源只作为出题参考，原文及旧版本答案未逐题核验。以当前技术版本的原理、边界和候选人回答为依据；不假定候选人经历与原作者相同。",
                "rubric": ["解释核心概念及适用边界", "给出符合目标难度的理由和可验证例子"],
                "source_kind": "candidate_recollection" if recollection else "company_collection",
                "source_name": "0voice 校招面经（候选人回忆）" if recollection else "0voice 公司面试题合集",
                "source_url": source_url, "source_revision": revision, "source_document": relative.as_posix(),
                "attribution": author, "license": LICENSE, "difficulty_basis": "产品关键词规则估计，非原作者标注",
                "modifications": "抽取问题、去除链接和排版、估计难度与技术标签；未导入答案、视频或个人联系方式。",
            })
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    remote = subprocess.check_output(["git", "-C", str(args.source), "remote", "get-url", "origin"], text=True).strip().removesuffix(".git")
    if remote != REPOSITORY:
        parser.error("请使用说明中指定的来源仓库；其他格式需要单独适配。")
    revision = subprocess.check_output(["git", "-C", str(args.source), "rev-parse", "HEAD"], text=True).strip()
    license_text = (args.source / "LICENSE.md").read_text(encoding="utf-8-sig")
    if "CC BY-NC-SA 4.0" not in license_text:
        parser.error("来源许可证已变化，请先核对。")
    entries = extract(args.source, revision)
    pack = {"schema_version": 1, "repository": REPOSITORY, "revision": revision,
            "license": LICENSE, "license_url": "https://creativecommons.org/licenses/by-nc-sa/4.0/",
            "notice": "仅本地个人学习；候选人回忆与合集未经逐题核验，不是企业官方题库。保留原作者权利，不可据此宣称拥有全部内容授权。",
            "experiences": entries}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(pack, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.output.with_suffix(".LICENSE.md").write_text(license_text, encoding="utf-8")
    print(json.dumps({"total": len(entries), "kinds": Counter(e["source_kind"] for e in entries), "difficulty": Counter(e["difficulty"] for e in entries), "companies": len({e["company"] for e in entries}), "documents": len({e["source_document"] for e in entries})}, ensure_ascii=False))


if __name__ == "__main__":
    main()
