import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[2] / "scripts/content-import/javascript_choices.py"
spec = importlib.util.spec_from_file_location("javascript_choices", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class JavaScriptChoiceImportTests(unittest.TestCase):
    def test_keeps_code_and_chinese_explanation_and_does_not_leak_answer_into_stem(self):
        source = """###### 1. 输出是什么？
```js
console.log(1 + 2)
```
- A: `3`
- B: `12`
<details><summary><b>答案</b></summary><p>
#### 答案：A
这里使用数值加法。
</p></details>
"""
        questions, skipped = module.parse_questions(source, "a" * 40)
        self.assertEqual([], skipped)
        self.assertEqual("`3`", questions[0]["answer"])
        self.assertIn("console.log(1 + 2)", questions[0]["stem"])
        self.assertNotIn("答案", questions[0]["stem"])
        self.assertIn("数值加法", questions[0]["explanation"])
        self.assertEqual("zh-CN", questions[0]["content_locale"])
        self.assertEqual("MIT", questions[0]["source_license"])
        self.assertEqual(64, len(questions[0]["source_sha256"]))

    def test_skips_multiple_answers_and_english_questions_and_duplicate_options(self):
        for title, options, answer in [
            ("输出是什么？", "- A: A\n- B: B", "A B"),
            ("What is the output?", "- A: A\n- B: B", "A"),
            ("输出是什么？", "- A: same\n- B: same", "A"),
        ]:
            with self.subTest(title=title, answer=answer):
                source = f"###### 1. {title}\n{options}\n<details>\n#### 答案：{answer}\n解释\n</details>"
                questions, skipped = module.parse_questions(source, "a" * 40)
                self.assertEqual([], questions)
                self.assertEqual(1, len(skipped))
