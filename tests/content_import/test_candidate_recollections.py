import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / f"scripts/content-import/{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


candidate = load("candidate_recollections")
forum = load("forum_interview_experiences")
merger = load("merge_interview_experiences")


class CandidateImportTest(unittest.TestCase):
    def test_round_tracking_pdf_normalization_and_excludes_candidate_answers(self):
        rows = candidate.extract_questions("一面\n1、线程和进程有什么区别？\n2、自我介绍\n3、我不知道原理\n二面\n4、Ｃ＋＋虚函数的作用？\nHR面\n5、项目如何实现？")
        self.assertEqual(2, len(rows))
        self.assertEqual(["一面", "二面"], [r for _, _, r in rows])
        self.assertTrue(rows[1][1].startswith("C++"))
        self.assertEqual(chr(63), rows[0][1][-1])

    def test_role_does_not_turn_basic_question_into_algorithm_or_advanced(self):
        kind, difficulty, tags = candidate.shared.classify("TCP 和 UDP 有什么区别？", "", "搜广推算法架构")
        self.assertEqual("knowledge", kind)
        self.assertEqual("basic", difficulty)
        self.assertNotIn("算法", tags)

    def test_forum_extracts_only_question_headings_and_preserves_original_link(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            file = root / "Java/示例公司面经/一面.md"
            file.parent.mkdir(parents=True)
            file.write_text("# 示例一面\n> 来源：https://www.nowcoder.com/discuss/123456\n### 1、线程和进程有什么区别？\n1. 答案内容不要抽取\n### 2、自我介绍\n### 3、你有什么想问的？\n", encoding="utf-8")
            (file.parent / "没有来源.md").write_text("### 1、事务如何实现？", encoding="utf-8")
            entries = forum.extract(root, "fixed-revision", [{"repository_document": "Java/示例公司面经/一面.md", "content": "原帖测试正文", "author": "测试作者", "published_at": 1701351739000}])
            self.assertEqual(1, len(entries))
            self.assertEqual("https://www.nowcoder.com/discuss/123456", entries[0]["source_url"])
            self.assertIn("fixed-revision", entries[0]["archive_url"])
            self.assertEqual("测试作者", entries[0]["attribution"])
            self.assertEqual("2023-11-30", entries[0]["source_date"])
            self.assertNotIn("interview_date", entries[0])

    def test_merge_preserves_first_ids_and_is_idempotent(self):
        first = {"id": "saved-id", "company": "示例", "prompt": "Ｃ＋＋：原理？", "source_url": "original"}
        duplicate = dict(first, id="other-id", prompt="C++ 原理?", source_url="later")
        merged = merger.merge([{"experiences": [first]}, {"experiences": [duplicate]}])
        self.assertEqual([first], merged["experiences"])
        self.assertEqual(merged["experiences"], merger.merge([merged, merged])["experiences"])
        with self.assertRaises(ValueError):
            merger.merge([{"experiences": [{"id": "english", "prompt": "English only"}]}])


if __name__ == "__main__":
    unittest.main()
