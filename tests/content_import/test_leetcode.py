"""Synthetic HTML fixtures only; no LeetCode statements or model requests."""
import sys
import unittest
import tempfile
from unittest.mock import Mock
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'scripts/content-import'))
import leetcode

class LeetcodeImportTests(unittest.TestCase):
    def test_overlapping_plans_keep_one_problem_and_all_memberships(self):
        questions=[{'questionFrontendId':'200','titleSlug':'graph'}, {'questionFrontendId':'1','titleSlug':'shared'}, {'questionFrontendId':'101','titleSlug':'outside'}, {'questionFrontendId':'LCR 1','titleSlug':'other'}]
        selected=leetcode.select_questions(questions, {'hot100':{'shared'},'leetcode75':{'shared'},'graph':{'graph'}})
        self.assertEqual(['1','200'],[q['questionFrontendId'] for q,_ in selected])
        self.assertEqual(['hot100','leetcode75','first100'],selected[0][1])
        self.assertEqual(['graph'],selected[1][1])

    def test_resume_reuses_cache_without_network_and_offline_never_fetches(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'question.json'
            fetch=Mock(return_value={'title':'中文题目'})
            self.assertEqual({'title':'中文题目'},leetcode.cached_json(path,fetch))
            fetch.assert_called_once()
            fetch.reset_mock()
            self.assertEqual({'title':'中文题目'},leetcode.cached_json(path,fetch,offline=True))
            fetch.assert_not_called()
            self.assertFalse(path.with_suffix('.json.tmp').exists())
            path.unlink()
            with self.assertRaises(ValueError): leetcode.cached_json(path,fetch,offline=True)
            fetch.assert_not_called()

    def test_failed_fetch_does_not_leave_poisoned_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'question.json'
            with self.assertRaises(TimeoutError):
                leetcode.cached_json(path,Mock(side_effect=TimeoutError()))
            self.assertFalse(path.exists())

    def test_pre_and_new_example_block_formats(self):
        raw='<pre><b>输入：</b>nums = [1,2], target = 3\n<b>输出：</b>[0,1]\n</pre><div class="example-block"><p>输入: nums = [2,3], target = 5</p><p>输出: [0,1]</p></div>'
        samples=leetcode.examples(raw,['nums','target'])
        self.assertEqual(2,len(samples))
        self.assertEqual({'nums':[2,3],'target':5},samples[1]['input'])
    def test_unnamed_and_single_quote_inputs_are_normalized(self):
        raw="<pre>输入：['1','0']\n输出：true\n</pre>"
        self.assertEqual({'chars':['1','0']},leetcode.examples(raw,['chars'])[0]['input'])
    def test_markup_text_is_rendered_as_markdown_without_html_execution(self):
        result=leetcode.text('<p>中文说明</p><pre>print(1)</pre><img src="https://example.invalid/image.png">')
        self.assertIn('```text',result)
        self.assertNotIn('<img',result)
    def test_complex_design_signature_does_not_claim_stdio_support(self):
        _,supported=leetcode.bridges({}, {'python3':'class Cache:\n    def __init__(self, n: int):\n        pass'}, [{'input':{'n':1}}])
        self.assertEqual([],supported)
    def test_custom_node_return_does_not_claim_json_execution_support(self):
        _,supported=leetcode.bridges({}, {'python3':'class Solution:\n    def construct(self, grid: List[List[int]]) -> Node:\n        pass'}, [{'input':{'grid':[[0]]},'expected':[0]}])
        self.assertEqual([],supported)
