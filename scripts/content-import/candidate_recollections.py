"""Local-only question extraction from two pinned candidate-recollection collections.

Supports 0voice/interview_experience text and bkfish/2020-Interview-experience
PDFs. No AI calls. PDF import requires the optional pypdf package.
"""

import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import unicodedata
from urllib.parse import quote

spec = importlib.util.spec_from_file_location("offerseed_import", Path(__file__).with_name("offerseed_interview_experiences.py"))
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)

SOURCES = {
    "https://github.com/0voice/interview_experience": "0voice 候选人逐轮面经",
    "https://github.com/bkfish/2020-Interview-experience": "bkfish 同学实习面经（PDF）",
}
COMPANIES = ("微软", "华为", "腾讯", "字节", "阿里", "蚂蚁", "美团", "百度", "京东", "网易", "滴滴", "小红书", "快手", "拼多多", "搜狗", "搜狐", "携程", "富途", "招银", "盛趣", "深信服", "奇安信", "安恒", "千丁互联", "清华同方", "爱奇艺", "猫眼", "吉比特", "好未来", "360", "TP-LINK", "CVTE", "SHEIN", "VIVO", "OPPO", "WPS")
TECHNICAL = re.compile(r"虚函数|关键字|作用|特性|代码题|消息队列|死锁|区别|原理|实现|机制|如何|怎么|为什么|什么|了解|说说|解释|介绍|比较|不同|优点|缺点|设计|优化|算法|手写|手撕|复杂度|项目|线程|进程|索引|事务|缓存|网络|路由|多态|指针|继承|内存|锁|隔离|绑定|协议|排序|链表|二叉树|堆|栈|中断|状态码|可靠|拥塞|持久化|重载|调度|同步|异步|http|tcp|udp|sql|redis|java|spring|c\+\+|vector|map|stl|linux|vue|react|proxy|es6|js\b|dfs|bfs|const|define|new|malloc|gmp|goroutine", re.IGNORECASE)
EXCLUDED = shared.EXCLUDED + ("反问", "你有什么想问", "你还有什么问题", "你有没有什么问题", "你为什么三年", "哪个专业", "个人规划", "职业规划", "评价", "薪水", "面试时间", "面试感受", "心得", "我说", "我觉得", "我感觉", "我不知道", "回答", "微信", "QQ", "感谢信", "没记录到", "没学", "我表示", "面试官说", "解释了一下", "记不清", "这个都可以", "(提示)")


def normalize(text):
    return unicodedata.normalize("NFKC", text).replace("\u200b", "")


def company_of(name):
    normalized = name.lower().replace("tplink", "tp-link")
    return next((name for name in COMPANIES if name.lower() in normalized), "未披露公司")


def extract_questions(text, initial_round="未注明"):
    lines = normalize(text).splitlines()
    round_name = initial_round
    result = []
    numbered = re.compile(r"^\s*(?:#{1,6}\s*)?(?:\d{1,3}[.、)）]|[•])\s*(.+)$")
    for number, line in enumerate(lines, 1):
        cleaned = line.strip().strip("# *")
        # Keep the full short heading, including department or timing if stated.
        if len(cleaned) <= 40 and re.search(r"(?:^|\d[.、]|\s)(?:[一二三四五六1-6]+面|(?:技术|主管|hr|HR)面|[一二三四五六]+轮)", cleaned) and not numbered.match(cleaned) and not re.search(r"我|猜测|没想到|收到消息", cleaned):
            round_name = cleaned.rstrip("：:")
            continue
        match = numbered.match(line)
        if not match or "hr" in round_name.lower():
            continue
        prompt = shared.clean(match.group(1)).strip(";；")
        # Two-line questions occur in PDF exports; only join clearly unfinished text.
        if number < len(lines) and len(prompt) < 90 and prompt.endswith(("的", "和", "与", "如何", "怎么", ",", "，")):
            continuation = lines[number].strip()
            if continuation and not numbered.match(continuation) and not continuation.startswith(("答", "我", "面", "A:", "A：")):
                prompt += continuation
        if not 5 <= len(prompt) <= 240 or len(re.findall(r"[\u4e00-\u9fff]", prompt)) < 2:
            continue
        if any(word in prompt for word in EXCLUDED) or not TECHNICAL.search(prompt):
            continue
        if prompt.startswith(("答", "我", "感觉", "因为", "然后", "比如", "其中", "如果有资料")):
            continue
        result.append((number, prompt, round_name))
    return result


def extract(source, repository, revision):
    if repository not in SOURCES:
        raise ValueError("不支持的来源")
    result, seen = [], set()
    for file in sorted(source.rglob("*")):
        relative = file.relative_to(source)
        if not file.is_file() or ".git" in relative.parts or len(relative.parts) != 2:
            continue
        is_pdf = file.suffix.lower() == ".pdf"
        if repository.endswith("2020-Interview-experience") != is_pdf:
            continue
        if is_pdf:
            from pypdf import PdfReader
            pages = [(i, page.extract_text() or "") for i, page in enumerate(PdfReader(file).pages, 1)]
        else:
            if file.suffix.lower() not in ("", ".txt", ".md"):
                continue
            pages = [(None, file.read_text(encoding="utf-8-sig"))]
        company = company_of(str(relative))
        round_match = re.search(r"[一二三四五六]+面", file.stem)
        initial_round = round_match.group(0) if round_match else "未注明"
        role = relative.parts[0] if is_pdf else "后端研发（来源合集方向，语言未统一注明）"
        for page, text in pages:
            for line, prompt, round_name in extract_questions(text, initial_round):
                key = (company, re.sub(r"\W+", "", prompt).lower())
                if key in seen:
                    continue
                seen.add(key)
                kind, difficulty, tags = shared.classify(prompt, "", role)
                url = f"{repository}/blob/{revision}/{quote(relative.as_posix(), safe='/')}"
                url += f"#page={page}" if is_pdf else f"#L{line}"
                digest = hashlib.sha256((repository + relative.as_posix() + ":" + prompt).encode()).hexdigest()[:16]
                result.append({
                    "id": "exp-rec-" + digest, "company": company, "role": role,
                    "round": round_name, "interview_date": "2020 年（合集年份）" if is_pdf else "未统一标注，见原文（主要为 2021–2022 届）",
                    "title": prompt[:110], "prompt": prompt, "kind": kind, "difficulty": difficulty, "tags": tags,
                    "reference": "候选人记录仅提供问题线索，不把其现场回答视为标准答案；技术版本和本人经历须另行核验。",
                    "rubric": ["解释问题对应的概念与实现", "给出边界、取舍和可验证例子"],
                    "source_kind": "candidate_recollection", "source_name": SOURCES[repository],
                    "source_url": url, "source_revision": revision,
                    "source_document": repository.rsplit("/", 2)[-2] + "/" + relative.as_posix(),
                    "source_page": page, "source_line": line,
                    "attribution": "原帖作者未署名（整理：" + repository.split("/")[-2] + "）",
                    "license": shared.LICENSE, "difficulty_basis": "按问题关键词估计，非原作者标注",
                    "modifications": "仅提取编号技术问题，PDF 字形转为常规文字；未导入答案、代码、联系方式或个人履历。",
                })
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    remote = subprocess.check_output(["git", "-C", str(args.source), "remote", "get-url", "origin"], text=True).strip().removesuffix(".git")
    if remote not in SOURCES:
        parser.error("来源必须是脚本声明的两个仓库之一。")
    revision = subprocess.check_output(["git", "-C", str(args.source), "rev-parse", "HEAD"], text=True).strip()
    entries = extract(args.source, remote, revision)
    pack = {"schema_version": 1, "repository": remote, "revision": revision, "license": shared.LICENSE,
            "notice": "上游未声明开源许可证，仅本地个人学习；保留作者权利、固定版本链接，不上传公开仓库。", "experiences": entries}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(pack, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"total": len(entries), "documents": len({e["source_document"] for e in entries}), "companies": len({e["company"] for e in entries}), "difficulty": Counter(e["difficulty"] for e in entries)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
