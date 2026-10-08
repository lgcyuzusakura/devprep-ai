import { useState } from "react";
import ReactMarkdown from "react-markdown";
import { ArrowRight, BookOpen, MessagesSquare, Send, Search } from "lucide-react";
import { post, navigate } from "../lib/api.js";
import { Badge, Button, ErrorNotice, PageHeading } from "../components/UI.jsx";

export default function Questions({ params, settings }) {
  const [question, setQuestion] = useState(params.get("q") || "");
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function ask(event) {
    event.preventDefault();
    if (!question.trim() || busy) return;
    setBusy(true); setError(""); setResult(null);
    try { setResult(await post("/qa", { question: question.trim() })); }
    catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }
  return <>
    <PageHeading title="知识问答" description="答案结合本地资料，并列出对应原文片段。" />
    <div className="qa-layout"><section className="qa-main"><form className="qa-form" onSubmit={ask}><label htmlFor="qa-question">你的问题</label><textarea id="qa-question" maxLength={4000} value={question} placeholder="例如：volatile 能保证 count++ 的原子性吗？" onChange={(event) => setQuestion(event.target.value)} /><div className="qa-form-bottom"><span>{question.length} / 4000</span><Button type="submit" tone="primary" icon={Send} busy={busy} disabled={!question.trim()}>提问</Button></div></form>
      <ErrorNotice message={error} />{busy && <div className="qa-thinking"><Search size={17} /><span>正在检索资料并组织回答…</span></div>}
      {result && <article className="qa-answer"><div className="answer-meta"><Badge tone={result.grounded ? "success" : "warning"}>{result.grounded ? "附资料引用" : result.citations?.length ? "原文检索结果" : "未找到充分依据"}</Badge>{result.model_used && <span>模型：{result.model_used}</span>}</div><div className="markdown-body"><ReactMarkdown>{result.answer}</ReactMarkdown></div>{result.citations?.length > 0 && <div className="citations"><h3><BookOpen size={16} />参考资料</h3>{result.citations.map((source, index) => <button className="citation-row" key={source.id} onClick={() => navigate("materials", { id: source.material_id, chunk: source.id })}><span className="citation-index">[{index + 1}]</span><span><strong>{source.title}</strong><small>{source.heading} · {source.source_type}</small><em>{source.excerpt}</em></span><ArrowRight size={16} /></button>)}</div>}</article>}
      {!result && !busy && <div className="qa-empty"><MessagesSquare size={26} /><p>先从一个具体的知识点开始</p><div className="suggested-questions">{["volatile 为什么不能保证 count++ 原子性？", "HashMap 怎么处理哈希冲突？", "TCP 三次握手每一步分别确认什么？"].map((item) => <button key={item} onClick={() => setQuestion(item)}>{item}<ArrowRight size={15} /></button>)}</div></div>}
    </section><aside className="qa-sidebar"><div className="section-heading"><h2>检索设置</h2><BookOpen size={16} /></div><div className="setting-summary"><span>资料范围</span><strong>全部已导入资料</strong></div><div className="setting-summary"><span>回答模型</span><strong>{settings?.model || "尚未配置"}</strong></div><div className="setting-summary"><span>来源</span><strong>本地资料片段</strong></div><div className="context-rule" /><button className="text-button" onClick={() => navigate("materials")}>管理资料库 <ArrowRight size={15} /></button><button className="text-button" onClick={() => navigate("settings")}>模型配置 <ArrowRight size={15} /></button></aside></div>
  </>;
}
