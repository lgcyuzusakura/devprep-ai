import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("offerseed", ROOT / "scripts/content-import/offerseed_interview_experiences.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class OfferseedInterviewImportTest(unittest.TestCase):
    def test_extracts_questions_and_keeps_round_and_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "by-company/tencent/backend/sample.md"
            path.parent.mkdir(parents=True)
            path.write_text(
                "# 腾讯后端面经\n\n"
                "- **公司**：腾讯\n- **岗位**：后端开发\n- **面试轮次**：一面\n- **面试时间**：2026 年 9 月\n\n"
                "## 一面 — 基础与算法\n\n"
                "### Q1：MySQL 的事务隔离级别有哪些？\n\n- 标签：基础\n\n"
                "### Q2：手撕 LRU Cache。\n\n- 标签：核心\n",
                encoding="utf-8",
            )
            entries = module.extract(root, "abc123")
            self.assertEqual(2, len(entries))
            self.assertEqual("腾讯", entries[0]["company"])
            self.assertEqual("一面 — 基础与算法", entries[0]["round"])
            self.assertRegex(entries[0]["source_url"], r"#L\d+$")
            self.assertEqual("未声明（上游仓库未提供 LICENSE；仅本地个人学习）", entries[0]["license"])

    def test_ignores_readmes_and_non_question_headings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "by-company/a/backend/sample.md"
            path.parent.mkdir(parents=True)
            path.write_text("- **公司**：A\n### 经验总结\n不要导入\n", encoding="utf-8")
            (path.parent / "README.md").write_text("### Q1：不要导入", encoding="utf-8")
            self.assertEqual([], module.extract(root, "revision"))

    def test_html_nested_question_does_not_capture_answer_or_hr(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "by-company/sample/agent/experience.html"
            path.parent.mkdir(parents=True)
            path.write_text('<div class="meta-chip">公司：示例</div>'
                '<div class="meta-chip">岗位：AI Agent 开发</div>'
                '<h2 class="round-title">一面</h2>'
                '<div class="question">Q1：<span>RAG 如何验证召回结果？</span></div>'
                '<div class="answer">答案段落不会被导入</div>'
                '<h2 class="round-title">HR面</h2>'
                '<div class="question">Q2：项目有哪些优化？</div>', encoding="utf-8")
            entries = module.extract(root, "fixed")
            self.assertEqual(1, len(entries))
            self.assertEqual("RAG 如何验证召回结果？", entries[0]["prompt"])
            self.assertEqual("一面", entries[0]["round"])
            self.assertNotIn("答案段落", str(entries))


if __name__ == "__main__":
    unittest.main()
