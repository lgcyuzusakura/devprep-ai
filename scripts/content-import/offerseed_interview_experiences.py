"""Extract Chinese interview questions from the public offerseed collection.

The upstream repository is a community-submitted collection and does not ship a
repository license. This importer therefore produces a local-only pack with
attribution and source links; it deliberately keeps questions and provenance,
not the submitted answers or private details.
"""

import argparse
from collections import Counter
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import quote

REPOSITORY = "https://github.com/offerseed/interview-experience"
SOURCE_NAME = "offerseed 公开面经（社区投稿）"
LICENSE = "未声明（上游仓库未提供 LICENSE；仅本地个人学习）"
EXCLUDED = ("自我介绍", "家庭情况", "父母", "对象在", "是否有对象", "实习多久", "入职", "为什么不读", "为什么导师", "薪资", "未来 3", "未来3", "兴趣爱好", "缓解压力", "优势和缺点", "拉家常", "反问", "多了解前沿", "反馈是什么", "长期职业目标")


def clean(value: str) -> str:
    value = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", value)
    value = re.sub(r"[*`>#]", "", value)
    return re.sub(r"\s+", " ", value).strip(" ：:")


def meta(content: str, key: str) -> str:
    match = re.search(rf"^-\s*\*\*{re.escape(key)}\*\*\s*[：:]\s*(.+)$", content, re.MULTILINE)
    return clean(match.group(1)) if match else "未记录"


def classify(prompt: str, label: str, role: str):
    text = f"{prompt} {label} {role}".lower()
    tags = set()
    groups = {
        "java": ("java", "jvm", "spring", "juc"),
        "c++": ("c++", "stl", "虚函数", "指针"),
        "go": ("golang", "go语言", "goroutine", "channel"),
        "python": ("python", "gil", "装饰器"),
        "前端": ("前端", "react", "vue", "javascript", "浏览器", "css"),
        "ai": ("大模型", "llm", "rag", "agent", "多模态", "推理", "模型", "量化", "搜广推", "深度学习", "机器学习"),
        "数据库": ("mysql", "redis", "sql", "数据库", "索引", "事务", "缓存"),
        "网络": ("tcp", "http", "网络", "udp", "socket"),
        "并发": ("并发", "线程", "线程池", "锁", "原子"),
        "系统设计": ("分布式", "系统设计", "高可用", "一致性", "容量", "架构"),
        "项目": ("项目", "实习", "负责", "工作内容", "个人贡献", "事情和产出"),
        "算法": ("手撕", "手写", "链表", "二叉树", "复杂度", "力扣", "leetcode"),
    }
    for tag, words in groups.items():
        target = prompt.lower() if tag in {"算法", "项目"} else text
        if any(word in target for word in words):
            tags.add(tag)
    if tags & {"java", "go", "python", "c++", "数据库", "并发"}:
        tags.add("后端")
    if not tags:
        tags.add("通用")
    if "后端" in role:
        tags.add("后端")
    if re.search(r"算法(?:题|实现|设计)", prompt):
        tags.add("算法")
    if "算法" in tags:
        kind = "algorithm"
    elif "项目" in tags or "项目" in label:
        kind = "project"
    else:
        kind = "knowledge"
    # Role routes retrieval, but must not raise every basic question's difficulty.
    question_text = prompt.lower()
    if any(word in question_text for word in ("系统设计", "高可用", "容量", "分布式", "推理引擎", "架构")):
        difficulty = "advanced"
    elif any(word in question_text for word in ("原理", "机制", "优化", "索引", "事务", "如何", "为什么", "设计")):
        difficulty = "intermediate"
    else:
        difficulty = "basic"
    return kind, difficulty, sorted(tags)


class InterviewHtml(HTMLParser):
    """Read explicit question elements; answers never enter the extracted data."""
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.capture = None
        self.depth = 0
        self.buffer = []
        self.info = {}
        self.round = "未记录"
        self.questions = []

    def handle_starttag(self, tag, attrs):
        if tag in {"br", "img", "meta", "link", "input", "hr", "source", "wbr"}:
            return
        self.stack.append(tag)
        classes = dict(attrs).get("class", "").split()
        if self.capture is None:
            for target in ("meta-chip", "round-title", "question"):
                if target in classes:
                    self.capture, self.depth, self.buffer = target, len(self.stack), []
                    self.line = self.getpos()[0]
                    break

    def handle_data(self, data):
        if self.capture:
            self.buffer.append(data)

    def handle_endtag(self, tag):
        if not self.stack or tag not in self.stack:
            return
        if self.capture and len(self.stack) == self.depth:
            text = clean("".join(self.buffer))
            if self.capture == "meta-chip" and "：" in text:
                key, value = text.split("：", 1)
                self.info[key] = value.strip()
            elif self.capture == "round-title":
                self.round = text
            elif self.capture == "question":
                text = re.sub(r"^Q\s*\d*\s*[：:]\s*", "", text)
                label = re.search(r"\s+(基础|核心|开放题)\s*$", text)
                self.questions.append((self.line, re.sub(r"\s+(基础|核心|开放题)\s*$", "", text), self.round, label.group(1) if label else ""))
            self.capture = None
        while self.stack:
            if self.stack.pop() == tag:
                break


def extract(source: Path, revision: str):
    result, seen = [], set()
    files = list(source.glob("by-company/**/*.md")) + [file for file in source.glob("by-company/**/*.html") if not file.with_suffix(".md").exists()]
    for file in sorted(files):
        if file.name.lower() == "readme.md":
            continue
        content = file.read_text(encoding="utf-8-sig")
        company = meta(content, "公司")
        role = meta(content, "岗位")
        round_name = meta(content, "面试轮次")
        date = meta(content, "面试时间")
        current_section = round_name
        questions = []
        lines = content.splitlines()
        if file.suffix == ".html":
            parser = InterviewHtml()
            parser.feed(content)
            company = parser.info.get("公司", "未记录")
            role = parser.info.get("岗位", "未记录")
            round_name = parser.info.get("轮次", "未记录")
            date = parser.info.get("日期", "未记录")
            questions = parser.questions
        for line_number, line in enumerate(lines if file.suffix == ".md" else [], 1):
            if line.startswith("## "):
                current_section = clean(line[3:])
            heading = re.match(r"^###\s+Q\s*\d+\s*[：:]\s*(.+)$", line.strip(), re.IGNORECASE)
            if heading:
                block = []
                for following in lines[line_number:]:
                    if following.startswith("#"):
                        break
                    block.append(following)
                label_match = re.search(r"标签\s*[：:]\s*([^\n]+)", "\n".join(block))
                questions.append((line_number, clean(heading.group(1)), current_section, clean(label_match.group(1)) if label_match else ""))
        for line_number, prompt, current_section, label in questions:
            if not (5 <= len(prompt) <= 240 and re.search(r"[\u4e00-\u9fff]", prompt)) or any(word in prompt for word in EXCLUDED):
                continue
            if "hr" in current_section.lower() or "主管面" in current_section:
                continue
            key = (company, role, prompt)
            if key in seen:
                continue
            seen.add(key)
            kind, difficulty, tags = classify(prompt, label, role)
            relative = file.relative_to(source).as_posix()
            digest = hashlib.sha256((relative + ":" + str(line_number) + ":" + prompt).encode()).hexdigest()[:16]
            result.append({
                "id": "exp-os-" + digest,
                "company": company,
                "role": role,
                "round": current_section or round_name,
                "interview_date": date,
                "kind": kind,
                "difficulty": difficulty,
                "tags": tags,
                "title": prompt[:110],
                "prompt": prompt,
                "reference": "保留社区投稿的问题原貌；原文未记录的回答不补写，不把面经当作企业官方题库。",
                "rubric": ["先说明概念或思路，再给出边界和验证方法", "明确区分本人经历、原文记录与推测"],
                "source_kind": "candidate_recollection",
                "source_name": SOURCE_NAME,
                "source_url": f"{REPOSITORY}/blob/{revision}/{quote(relative, safe='/')}#L{line_number}",
                "source_revision": revision,
                "source_document": relative,
                "attribution": "offerseed 投稿者（页面未署名）",
                "license": LICENSE,
                "difficulty_basis": "按问题关键词与岗位信息估计，非作者标注",
                "modifications": "抽取 Q 标题或 HTML 问题元素、去除排版并过滤非技术条目；未导入原文答案、联系方式或非面试讨论。",
            })
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    remote = subprocess.check_output(["git", "-C", str(args.source), "remote", "get-url", "origin"], text=True).strip().removesuffix(".git")
    if remote != REPOSITORY:
        parser.error("请使用指定的 offerseed/interview-experience 来源仓库。")
    revision = subprocess.check_output(["git", "-C", str(args.source), "rev-parse", "HEAD"], text=True).strip()
    entries = extract(args.source, revision)
    pack = {
        "schema_version": 1,
        "repository": REPOSITORY,
        "revision": revision,
        "license": LICENSE,
        "notice": "仅本地个人学习。上游仓库未提供 LICENSE，问题保留署名和来源链接；不得将该 pack 或原文上传到公开仓库。",
        "experiences": entries,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(pack, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.output.with_suffix(".LICENSE.md").write_text(
        "# offerseed 面经来源说明\n\n"
        f"来源：[{REPOSITORY}]({REPOSITORY})\n\n"
        f"固定版本：`{revision}`\n\n"
        "上游仓库未提供 LICENSE。此文件只用于本地个人学习，保留投稿问题的来源链接和统一署名；不上传公开 GitHub，不将其视为企业官方题库。\n",
        encoding="utf-8",
    )
    print(json.dumps({"total": len(entries), "difficulty": Counter(e["difficulty"] for e in entries), "documents": len({e["source_document"] for e in entries}), "companies": len({e["company"] for e in entries})}, ensure_ascii=False))


if __name__ == "__main__":
    main()
