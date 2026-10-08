"""Parser regression tests use original synthetic Markdown, never source content."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts" / "content-import"))
from llm import sections


class LlmSectionTests(unittest.TestCase):
    def test_nested_answer_headings_and_code_stay_in_answer(self):
        markdown = """# Example
### 1. How does Example work?
The original answer explains two operations and their ordering.
#### Details
The nested explanation stays attached to the original question.
```md
### 2. A heading inside code?
This is code, not another question.
```
### 2. Why use Example?
The second original answer is sufficiently long to be useful.
"""
        rows = sections(markdown)
        self.assertEqual(2, len(rows))
        self.assertIn("#### Details", rows[0][1])
        self.assertIn("heading inside code", rows[0][1])
        self.assertNotIn("second original answer", rows[0][1])

    def test_empty_and_navigation_sections_are_skipped(self):
        rows = sections("# Navigation\n[Some link](https://example.com)\n### 1. Empty question?\n### 2. Also empty?\n")
        self.assertEqual([], rows)


if __name__ == "__main__":
    unittest.main()
