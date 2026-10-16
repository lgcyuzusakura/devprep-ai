"""Original synthetic fixtures; no third-party article text is checked in."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts" / "content-import"))
from xiaolin import extract
from multi_source import validate_pack


class XiaolinImporterTest(unittest.TestCase):
    def parse(self, content, key="java"):
        return extract('<main><div class="theme-default-content">'+content+'</div></main>', key, "a"*64, "2026-10-16T00:00:00+00:00")

    def test_questions_keep_nested_answer_sections_code_tables_and_source(self):
        topics, questions = self.parse('''<h1>测试文章</h1><h2>基础</h2>
          <h3 id="question-one"><a class="header-anchor">#</a>如何处理示例请求？</h3>
          <p>先校验输入，再记录唯一请求标识，重复请求返回第一次的结果。测试环境需要固定。</p>
          <h4>实现示例</h4><pre class="language-java"><code>if (value &lt; 1) return;</code></pre>
          <table><tr><th>情况</th><th>操作</th></tr><tr><td>重复请求</td><td>返回历史结果</td></tr></table>
          <h3 id="question-two">为什么保留记录？</h3><p>保留记录可以核对第一次处理结果，重试时避免重复执行，应根据业务确定保留期限。</p>''')
        self.assertEqual(2,len(questions))
        self.assertEqual("如何处理示例请求？",questions[0]["stem"])
        self.assertIn("#### 实现示例",questions[0]["answer"])
        self.assertIn("```java\nif (value < 1) return;",questions[0]["answer"])
        self.assertIn("| 重复请求 | 返回历史结果 |",questions[0]["answer"])
        self.assertNotIn("保留记录可以核对",questions[0]["answer"])
        self.assertTrue(questions[0]["source_url"].endswith("#question-one"))
        validate_pack({"topics":topics,"questions":questions})

    def test_two_level_question_lists_are_parsed_without_fragmenting_answers(self):
        topics,questions=self.parse('''<h1>示例</h1><h2 id="one">什么是示例命令？</h2>
        <p>这是用于查看示例数据状态的命令，运行前需要确认目标目录和输入参数，避免处理错误的数据。</p>
        <h3>参数说明</h3><p>这个小节属于上一个问题的解释，不应该变成一道额外问题。</p>
        <h2 id="two">如何校验结果？</h2><p>核对返回值和预期输出，覆盖空数据、重复数据和失败重试的处理路径。</p>''',"linux")
        self.assertEqual(2,len(questions));self.assertIn("参数说明",questions[0]["answer"])

    def test_script_navigation_empty_answers_and_english_only_titles_are_not_imported(self):
        _,questions=self.parse('''<h1>标题</h1><h2>基础</h2>
        <h3 id="valid">如何查看结果？</h3><p>查看处理记录和执行状态，核对输入输出是否符合预期，必要时在固定环境复现。</p>
        <script>throw new Error('should never be imported')</script>
        <h3>English only?</h3><p>这段内容虽然有中文，但是题干不是中文，应当跳过该题并保留其他题目。</p>
        <h3>如何处理空答案？</h3><p>见链接。</p><h3>参考资料</h3><p>这里是一个目录，不是题目，不应该用于练习。</p>''')
        self.assertEqual(1,len(questions));self.assertNotIn("should never",questions[0]["answer"])

    def test_ids_are_stable_when_answers_change_and_unsafe_links_are_not_executed(self):
        one='<h1>标题</h1><h2>基础</h2><h3 id="stable">如何复核材料？</h3><p>核对材料的来源和版本，确保每次修改都有记录，并保留原始快照以便回看。</p>'
        _,before=self.parse(one);_,after=self.parse(one.replace("保留原始快照","保存完整原文"))
        self.assertEqual(before[0]["id"],after[0]["id"])
        _,items=self.parse(one+'<p><a href="javascript:alert(1)">不能执行</a><img src="javascript:alert(2)"></p>')
        self.assertNotIn("javascript:",items[0]["answer"])

    def test_missing_public_article_body_is_not_treated_as_a_success(self):
        with self.assertRaises(ValueError):
            extract("<html><p>请登录</p></html>","java","a"*64,"today")


if __name__ == "__main__":
    unittest.main()
