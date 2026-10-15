import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("interview_experiences", ROOT / "scripts/content-import/interview_experiences.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class InterviewExperienceImportTest(unittest.TestCase):
    def test_pinned_links_authors_rounds_dedup_and_no_reconstructed_answers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "面经").mkdir()
            (root / "面经/腾讯后端面经.txt").write_text(
                "一面\n1.自我介绍\n2.Java线程池的拒绝策略有哪些？\n3.Java线程池的拒绝策略有哪些？\n作者：测试候选人\n二面\n1.项目如何保证数据一致性？\n", encoding="utf-8")
            entries = module.extract(root, "abc123")
            self.assertEqual(2, len(entries))
            self.assertEqual("一面", entries[0]["round"])
            self.assertEqual("二面", entries[1]["round"])
            self.assertTrue(all(e["source_kind"] == "candidate_recollection" and e["attribution"] == "测试候选人" for e in entries))
            self.assertIn("/blob/abc123/", entries[0]["source_url"])
            self.assertIn("#L3", entries[0]["source_url"])
            self.assertEqual("advanced", entries[1]["difficulty"])

    def test_company_collection_does_not_import_answers_or_language_tutorials(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "京东").mkdir()
            (root / "京东/readme.md").write_text("## 1. Redis 分布式锁如何实现？\n答案：别导入这段答案\n## 2. Redis 分布式锁如何实现？\n", encoding="utf-8")
            (root / "golang相关面试题视频讲解").mkdir()
            (root / "golang相关面试题视频讲解/readme.md").write_text("#### 1. Go调度器原理是什么？\n", encoding="utf-8")
            entries = module.extract(root, "revision")
            self.assertEqual(1, len(entries))
            self.assertEqual("company_collection", entries[0]["source_kind"])
            self.assertNotIn("别导入", str(entries))

    def test_technical_tags_and_estimated_difficulty_remain_explainable(self):
        kind, level, tags = module.classify("React Fiber 架构的原理和权衡？")
        self.assertEqual("knowledge", kind)
        self.assertEqual("advanced", level)
        self.assertIn("前端", tags)
        self.assertNotIn("后端", tags)


if __name__ == "__main__":
    unittest.main()
