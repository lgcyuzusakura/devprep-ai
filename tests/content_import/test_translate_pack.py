"""Synthetic regressions for the translator; no downloaded source text or API key."""

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts" / "content-import"))
import translate_pack as translator


class TranslationPackTests(unittest.TestCase):
    def test_crlf_code_is_protected_from_translation_and_language_scanning(self):
        value = "Some prose.\r\n```py\r\n# This code comment stays English.\r\nprint('Hi')\r\n```\r\n"
        payload = {"id": "sample", "stem": "Question?", "options": [], "answer": value,
                   "explanation": "说明", "rubric": []}
        masked, saved = translator.protected_payload([payload], translator.TEXT_FIELDS)
        self.assertNotIn("print('Hi')", masked[0]["answer"])
        self.assertEqual(1, sum("print('Hi')" in value for value in saved.values()))
        self.assertFalse(translator.needs_translation(value.replace("Some prose.", "中文说明。")))

    def test_restoration_requires_placeholders_to_stay_in_the_same_field(self):
        payload = {"id": "sample", "stem": "What is `sample()`?", "options": [],
                   "answer": "The `sample()` call returns 3.", "explanation": "说明", "rubric": []}
        masked, saved = translator.protected_payload([payload], translator.TEXT_FIELDS)
        result = {"sample": dict(masked[0])}
        result["sample"]["stem"] = "这个函数是什么？"
        with self.assertRaises(translator.TranslationError):
            translator.restore_payload(masked, result, saved, translator.TEXT_FIELDS)

    def test_correct_protected_content_is_restored_exactly(self):
        payload = {"id": "sample", "stem": "What is `sample()`?", "options": [],
                   "answer": "See https://example.com/doc\n```py\nprint('Hi')\n```", "explanation": "说明", "rubric": []}
        masked, saved = translator.protected_payload([payload], translator.TEXT_FIELDS)
        result = {"sample": dict(masked[0])}
        output = translator.restore_payload(masked, result, saved, translator.TEXT_FIELDS)
        self.assertEqual(payload, output["sample"])

    def test_mixed_english_prose_is_detected_but_proper_names_and_code_are_allowed(self):
        self.assertTrue(translator.needs_translation("中文提示。 The new widget returns a value."))
        self.assertFalse(translator.needs_translation("使用 Java、Python 和 React。"))
        self.assertFalse(translator.needs_translation("运行 `git status` 查看状态。"))

    def test_input_hash_changes_if_any_translated_field_or_translation_version_changes(self):
        before = {"id": "sample", "stem": "What is Sample?"}
        after = {"id": "sample", "stem": "Why use Sample?"}
        self.assertNotEqual(translator.sha_payload(before), translator.sha_payload(after))
        old = translator.sha_payload(before)
        with patch.object(translator, "TRANSLATION_VERSION", "next-version"):
            self.assertNotEqual(old, translator.sha_payload(before))

    def test_batches_are_lists_of_records_and_obey_count_and_character_bounds(self):
        rows = [{"id": str(index), "stem": "x" * 30, "options": [], "answer": "", "explanation": "", "rubric": []}
                for index in range(5)]
        chunks = list(translator.batches(rows, 2, 1000))
        self.assertEqual([2, 2, 1], [len(chunk) for chunk in chunks])
        self.assertEqual(rows, [item for chunk in chunks for item in chunk])

    def test_partial_run_keeps_all_ids_source_fields_and_resumes_without_new_requests(self):
        question = {"id": "sample-q", "point": "sample-t", "type": "short_answer", "stem": "What is Sample?",
                    "options": [], "answer": "Sample is a useful widget.", "explanation": "Reference answer.",
                    "rubric": [], "source_locale": "en", "source_url": "https://example.com/reference",
                    "source_license": "MIT", "source_sha256": "f" * 64}
        topic = {"id": "sample-t", "domain": "软件", "title": "Sample", "summary": "示例知识点", "prerequisites": []}
        pack = {"topics": [topic], "questions": [question]}
        result = {"sample-q": {"id": "sample-q", "stem": "Sample 是什么？", "options": [],
                                "answer": "Sample 是一个有用的组件。", "explanation": "参考答案。", "rubric": []}}
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / "input.json", Path(directory) / "output.json"
            source.write_text(json.dumps(pack, ensure_ascii=False), encoding="utf-8")
            argv = ["translate_pack.py", "--input", str(source), "--output", str(target),
                    "--base-url", "http://localhost:9999/v1", "--api-key", "test-key", "--model", "example",
                    "--workers", "1"]
            with patch.object(sys, "argv", argv), patch.object(translator, "translate_batch", return_value=result) as generate:
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, translator.main())
                self.assertEqual(1, generate.call_count)
            translated = json.loads(target.read_text(encoding="utf-8"))
            out = translated["questions"][0]
            self.assertEqual(question["id"], out["id"])
            self.assertEqual(question["source_locale"], out["source_locale"])
            self.assertEqual(question["source_url"], out["source_url"])
            self.assertEqual(question["source_sha256"], out["source_sha256"])
            self.assertEqual("zh-CN", out["content_locale"])
            with patch.object(sys, "argv", argv), patch.object(translator, "translate_batch") as generate:
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, translator.main())
                generate.assert_not_called()


if __name__ == "__main__":
    unittest.main()
