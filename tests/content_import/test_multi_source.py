"""Synthetic parser regressions; these fixtures contain no downloaded source text."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts" / "content-import"))
from multi_source import candidate_heading, has_reference_answer, headings, section_end, split_prompt_and_answer


class MultiSourceParserTest(unittest.TestCase):
    def test_nested_answer_headings_do_not_cut_a_numbered_question(self):
        text = ("1. ### What is a sample widget?\n\nA widget is a sample value.\n"
                "## Implementation detail\nThe nested explanation continues.\n"
                "2. ### How is a sample widget copied?\n\nA fresh value is made.\n")
        items = headings(text)
        end = section_end(items, 0, text, "javascript", Path("README.md"))
        answer = text[items[0].body_start:end]
        self.assertIn("The nested explanation continues.", answer)
        self.assertNotIn("How is a sample widget copied?", answer)

    def test_code_question_keeps_input_out_of_reference_answer(self):
        body = ("\n```text\nprint(sample_value)\n```\n\n"
                "<details><summary><b>Answer</b></summary>\nThe sample value is printed.\n</details>\n")
        stem, answer = split_prompt_and_answer("What is the output of this sample?", body, "javascript")
        self.assertIn("print(sample_value)", stem)
        self.assertNotIn("The sample value is printed.", stem)
        self.assertIn("The sample value is printed.", answer)
        self.assertNotIn("print(sample_value)", answer)
        go_body = "\n```text\nsample_input\n```\n\nA: one\nB: two\n\n### 正确答案：B\nA documented explanation.\n"
        go_stem, go_answer = split_prompt_and_answer("Choose a sample result.", go_body, "go")
        self.assertIn("sample_input", go_stem)
        self.assertIn("A: one", go_stem)
        self.assertNotIn("正确答案", go_stem)
        self.assertTrue(go_answer.startswith("### 正确答案：B"))

    def test_navigation_and_answer_indexes_are_not_practice_questions(self):
        self.assertFalse(has_reference_answer("[Read the reference](https://example.com/reference)\n"))
        self.assertFalse(has_reference_answer("A: first choice\nB: second choice\n"))
        answer_index = headings("### 1. D\nA result is selected.\n")[0]
        self.assertFalse(candidate_heading(answer_index, "go", Path("question/sample.md"), "答案"))

    def test_question_like_lines_inside_fenced_input_are_ignored(self):
        text = ("1. ### What is a sample block?\n\n   ```text\n"
                "   8. ### How is this line interpreted?\n   ```\n"
                "2. ### Why is a sample block useful?\n\nA complete explanation.\n")
        self.assertEqual([item.title for item in headings(text)],
                         ["What is a sample block?", "Why is a sample block useful?"])


if __name__ == "__main__":
    unittest.main()
