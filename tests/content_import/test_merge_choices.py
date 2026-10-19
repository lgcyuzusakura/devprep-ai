import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('merge_choices', Path(__file__).resolve().parents[2] / 'scripts/content-import/merge_choices.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MergeChoicesTests(unittest.TestCase):
    def setUp(self):
        self.base = {'translation': {'status': 'partial'}, 'topics': [{'id': 'old'}],
                     'questions': [{'id': 'translated-old', 'stem': '已有译文', 'type': 'short_answer'}]}
        self.extra = {'topics': [{'id': 'new', 'domain': 'Java', 'title': '选择题', 'summary': '原创练习'}],
                      'questions': [{'id': 'new-1', 'point': 'new', 'type': 'single_choice', 'content_locale': 'zh-CN',
                                     'stem': '哪项正确？', 'options': ['甲', '乙'], 'answer': '甲', 'explanation': '解析。',
                                     'source_name': '原创', 'source_url': 'https://example.com/source', 'source_attribution': 'AI 编写'}]}

    def test_existing_content_is_preserved_and_reimport_is_idempotent(self):
        original = copy.deepcopy(self.base)
        merged = module.merge_choices(self.base, self.extra)
        self.assertEqual(original, self.base)
        self.assertEqual(original['questions'][0], merged['questions'][0])
        self.assertEqual(original['translation'], merged['translation'])
        self.assertEqual(merged, module.merge_choices(merged, self.extra))

    def test_conflicting_id_is_not_silently_overwritten(self):
        merged = module.merge_choices(self.base, self.extra)
        self.extra['questions'][0]['answer'] = '乙'
        with self.assertRaisesRegex(ValueError, 'Conflicting question ID'):
            module.merge_choices(merged, self.extra)

    def test_invalid_choice_content_is_rejected(self):
        for field, value in [('answer', '不存在'), ('options', ['甲', '甲']), ('content_locale', 'en'),
                             ('stem', 'English only'), ('explanation', ''), ('source_attribution', ''), ('point', 'missing')]:
            with self.subTest(field=field):
                pack = copy.deepcopy(self.extra)
                pack['questions'][0][field] = value
                with self.assertRaises(ValueError):
                    module.merge_choices(self.base, pack)

    def test_duplicate_ids_are_rejected_before_merge(self):
        self.extra['questions'] *= 2
        with self.assertRaisesRegex(ValueError, 'duplicate IDs'):
            module.merge_choices(self.base, self.extra)
