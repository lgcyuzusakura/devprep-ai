import { workspaceStorage as localStorage } from "../lib/workspaceStorage.js";
import { useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import { ArrowUp, ArrowDown, Plus, BookOpen, Trash2, ChevronDown, MessageSquare, ListChecks, Search, Copy, Check, RotateCcw, Square, Pencil, PanelLeft, X, LoaderCircle } from "lucide-react";
import { api, post, navigate } from "../lib/api.js";
import { Badge, Button, ErrorNotice } from "../components/UI.jsx";

const modes = { mentor: "自由学习", quiz: "互动出题", interview: "模拟面试", algorithm: "算法指导" };
const toolNames = { search_materials: "检索学习笔记", search_questions: "检索中文题库", learning_progress: "读取学习进度", list_topics: "查找知识点", pick_question: "选择练习题", question_reference: "对照参考答案", algorithm_problem: "读取算法题" };
const suggestions = ["用例子讲讲 HashMap 的扩容", "出一道 Java 并发题，等我回答再讲解", "模拟后端面试，每次问一个问题", "两数之和怎么想？先给我一点提示"];
const draftKey = id => `devprep.chat.draft.${id || "new"}`;
function savedDraft(id) { try { return localStorage.getItem(draftKey(id)) || ""; } catch { return ""; } }

export default function StudyChat({ params, settings }) {
  const id = params.get("session") || "";
  const [sessions, setSessions] = useState([]);
  const [messages, setMessages] = useState([]);
  const [mode, setMode] = useState("mentor");
  const [draft, setDraft] = useState(params.get("q") || savedDraft(id));
  const [busy, setBusy] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState("");
  const [live, setLive] = useState(null);
  const [error, setError] = useState("");
  const [search, setSearch] = useState("");
  const [sidebar, setSidebar] = useState(false);
  const [away, setAway] = useState(false);
  const [renaming, setRenaming] = useState("");
  const [title, setTitle] = useState("");
  const transcript = useRef(null), input = useRef(null), locked = useRef(false), activeSession = useRef(""), controller = useRef(null), mounted = useRef(true), atBottom = useRef(true);
  const refreshSessions = () => api("/study/sessions").then(data => { if (mounted.current) setSessions(data.sessions); });
  useEffect(() => {
    mounted.current = true;
    refreshSessions().catch(err => setError(err.message));
    return () => { mounted.current = false; controller.current?.abort(); };
  }, []);
  useEffect(() => {
    setSidebar(false); setError("");
    // Creating a session changes the route while its first message is streaming.
    if (locked.current && activeSession.current === id) return;
    if (locked.current) { const old = activeSession.current; post(`/study/sessions/${old}/stop`, {}).catch(() => {}); controller.current?.abort(); }
    setDraft(params.get("q") || savedDraft(id)); setLive(null); setMessages([]); atBottom.current = true; setAway(false);
    if (!id) { setMode("mentor"); setLoading(false); return; }
    let cancelled = false; setLoading(true);
    api(`/study/sessions/${id}`).then(data => {
      if (!cancelled) { setMessages(data.messages); setMode(data.session.mode); setBusy(data.busy); activeSession.current = data.busy ? id : ""; }
    }).catch(err => { if (!cancelled) setError(err.message); }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [id]);
  // Observe a turn still finishing after refresh without submitting it again.
  useEffect(() => {
    if (!busy || locked.current || !id) return;
    let cancelled = false, polling = false;
    const timer = setInterval(async () => {
      if (polling) return; polling = true;
      try { const detail = await api(`/study/sessions/${id}`); if (!cancelled) { setMessages(detail.messages); if (!detail.busy) { setBusy(false); setStopping(false); refreshSessions(); } } } catch { /* Retry on the next poll. */ } finally { polling = false; }
    }, 2000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [busy, id]);
  useEffect(() => { const el = transcript.current; if (el && atBottom.current) el.scrollTop = el.scrollHeight; }, [messages, live, status, loading]);
  useEffect(() => { if (input.current) { input.current.style.height = "auto"; input.current.style.height = `${Math.min(180, input.current.scrollHeight)}px`; } }, [draft]);
  function edit(value) { setDraft(value); try { localStorage.setItem(draftKey(activeSession.current || id), value); } catch { } }
  function jump() { atBottom.current = true; setAway(false); transcript.current?.scrollTo({ top: transcript.current.scrollHeight, behavior: "smooth" }); }
  function newChat() { if (busy) return; activeSession.current = ""; setDraft(""); setMessages([]); setLive(null); setMode("mentor"); navigate("questions"); input.current?.focus(); }
  async function stop() {
    if (stopping) return; setStopping(true);
    try { await post(`/study/sessions/${activeSession.current || id}/stop`, {}); }
    catch (err) { setError(err.message); setStopping(false); }
  }
  async function send(event, retry = false) {
    event?.preventDefault();
    if (locked.current || busy || loading || (!retry && !draft.trim())) return;
    const text = draft.trim();
    locked.current = true; setBusy(true); setStopping(false); setError(""); setLive({ content: "", payload: { tools: [] } });
    atBottom.current = true; setAway(false); setStatus("正在连接");
    if (!retry) edit("");
    let sessionId = id, accepted = false, completed = false;
    const abort = new AbortController(); controller.current = abort;
    try {
      if (!sessionId) {
        const session = await post("/study/sessions", { mode }); sessionId = session.id;
        activeSession.current = sessionId;
        await refreshSessions(); navigate("questions", { session: sessionId });
      } else activeSession.current = sessionId;
      const response = await fetch(`/api/study/sessions/${sessionId}/${retry ? "retry" : "messages"}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(retry ? {} : { content: text }), signal: abort.signal });
      if (!response.ok) { const data = await response.json(); throw new Error(data.error?.message || "发送失败"); }
      accepted = true;
      if (retry) setMessages(old => old.at(-1)?.role === "assistant" ? old.slice(0, -1) : old);
      const reader = response.body.getReader(), decoder = new TextDecoder(); let buffer = "";
      function receive(block) {
        const lines = block.split("\n");
        const name = lines.find(line => line.startsWith("event:"))?.slice(6).trim();
        const raw = lines.filter(line => line.startsWith("data:")).map(line => line.slice(5).trimStart()).join("\n");
        if (!raw || !mounted.current || activeSession.current !== sessionId) return;
        const data = JSON.parse(raw);
        if (name === "user") setMessages(old => [...old.filter(m => m.id !== data.id), data]);
        if (name === "status") setStatus(data.text);
        if (name === "delta") { setStatus("正在回答"); setLive(old => ({ ...old, content: (old?.content || "") + data.text })); }
        if (name === "tool_start" || name === "tool") setLive(old => {
          const list = old?.payload?.tools || [];
          return { ...old, payload: { ...old?.payload, tools: [...list.filter(item => item.id !== data.id), { ...data, running: name === "tool_start" }] } };
        });
        if (name === "question") setLive(old => ({ ...old, payload: { ...old?.payload, question: data } }));
        if (name === "message") { completed = true; setMessages(old => [...old.filter(m => m.id !== data.id), data]); setLive(null); }
        if (name === "error") { completed = true; setError(data.message); }
      }
      while (true) {
        const { value, done } = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
        buffer = buffer.replace(/\r\n/g, "\n");
        let end; while ((end = buffer.indexOf("\n\n")) >= 0) { receive(buffer.slice(0, end)); buffer = buffer.slice(end + 2); }
        if (done) { if (buffer.trim()) receive(buffer); break; }
      }
      if (!completed) throw new Error("连接已断开。已有消息会保留，可以重新回答。");
    } catch (err) {
      if (err.name !== "AbortError" && mounted.current) { setError(err.message); if (!accepted && !retry) edit(text); }
    } finally {
      if (mounted.current && sessionId && activeSession.current === sessionId) {
        try { const detail = await api(`/study/sessions/${sessionId}`); setMessages(detail.messages); setBusy(detail.busy); await refreshSessions(); }
        catch { setBusy(false); }
        setLive(null); setStatus(""); setStopping(false);
      } else if (mounted.current) setBusy(false);
      locked.current = false; controller.current = null;
      if (mounted.current) input.current?.focus();
    }
  }
  async function remove(session) {
    if (!window.confirm(`删除“${session.title}”及其对话记录？`)) return;
    try { await api(`/study/sessions/${session.id}`, { method: "DELETE" }); await refreshSessions(); if (id === session.id) navigate("questions"); }
    catch (err) { setError(err.message); }
  }
  async function rename(event, sessionId) {
    event.preventDefault();
    try { await api(`/study/sessions/${sessionId}`, { method: "PATCH", body: JSON.stringify({ title }) }); setRenaming(""); await refreshSessions(); }
    catch (err) { setError(err.message); }
  }
  const current = sessions.find(session => session.id === id);
  return <div className={`study-layout ${sidebar ? "study-sidebar-open" : ""}`}>
    {sidebar && <button className="study-sidebar-scrim" aria-label="关闭对话列表" onClick={() => setSidebar(false)} />}
    <aside className="study-sessions">
      <div className="study-sessions-heading"><strong>对话</strong><button className="icon-button study-mobile-close" aria-label="关闭对话列表" onClick={() => setSidebar(false)}><X size={16} /></button></div>
      <Button icon={Plus} disabled={busy} onClick={newChat}>新对话</Button>
      <div className="study-session-search"><Search size={14} /><input aria-label="搜索对话" placeholder="搜索对话" value={search} onChange={e => setSearch(e.target.value)} /></div>
      <div className="session-list">{sessions.filter(session => session.title.toLowerCase().includes(search.toLowerCase())).map(session => <div key={session.id} className={`session-row ${id === session.id ? "selected" : ""}`}>
        {renaming === session.id ? <form onSubmit={e => rename(e, session.id)} className="session-rename"><input aria-label="对话名称" autoFocus maxLength={80} value={title} onChange={e => setTitle(e.target.value)} onKeyDown={e => { if (e.key === "Escape") setRenaming(""); }} /><button className="icon-button" aria-label="保存对话名称"><Check size={14} /></button></form> : <><button disabled={busy} onClick={() => navigate("questions", { session: session.id })}><MessageSquare size={14} /><span>{session.title}<small>{new Date(session.updated_at).toLocaleDateString("zh-CN", { month: "short", day: "numeric" })}</small></span></button><div className="session-actions"><button className="icon-button" aria-label={`重命名 ${session.title}`} disabled={busy} onClick={() => { setRenaming(session.id); setTitle(session.title); }}><Pencil size={13} /></button><button className="icon-button" aria-label={`删除 ${session.title}`} disabled={busy} onClick={() => remove(session)}><Trash2 size={13} /></button></div></>}
      </div>)}{!sessions.length && <p className="muted">对话自动保存。下次回来，接着聊。</p>}{!!sessions.length && !sessions.some(s => s.title.toLowerCase().includes(search.toLowerCase())) && <p className="muted">没有找到这段对话。</p>}</div>
      <div className="study-session-footer"><BookOpen size={15} /><a href="#/sourceqa">资料引用问答</a></div>
    </aside>
    <section className="study-conversation">
      <header className="study-header"><button className="icon-button study-sidebar-toggle" aria-label="打开对话列表" onClick={() => setSidebar(true)}><PanelLeft size={18} /></button><div><h1>学习教练</h1><span>{current?.title || "计算机学习对话"}</span></div><a href="#/settings" className="study-model" title="修改回答模型"><span className={`status-dot ${settings?.model ? "" : "status-dot-muted"}`} />{settings?.model || "选择模型"}<ChevronDown size={13} /></a></header>
      <div className="study-transcript" ref={transcript} onScroll={e => { const el = e.currentTarget; atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 90; setAway(!atBottom.current); }}>
        {loading && <p className="study-loading"><LoaderCircle size={16} className="spin" />正在读取对话</p>}
        {!loading && !messages.length && !busy && <div className="study-welcome"><BookOpen size={28} strokeWidth={1.4} /><h2>今天想学什么？</h2><p>问一个知识点，一起推导算法，或者让我考考你。</p><div className="study-suggestions">{suggestions.map(text => <button key={text} onClick={() => { edit(text); input.current?.focus(); }}><span>{text}</span><ArrowUp size={14} /></button>)}</div>{!settings?.model && <p className="model-setup-hint"><a href="#/settings">选择回答模型</a>，开始互动辅导。</p>}</div>}
        {messages.map((message, index) => <ChatMessage key={message.id} message={message} onAnswer={value => { edit(value); input.current?.focus(); }} retry={index === messages.length - 1 && message.role === "assistant" ? () => send(null, true) : null} disabled={busy || loading} />)}
        {busy && <div className="study-live" aria-live="polite"><div className="study-working" role="status"><LoaderCircle size={13} className="spin" />{stopping ? "正在停止…" : status || "正在回答"}</div>{live && <ChatMessage message={{ ...live, role: "assistant", id: "live" }} onAnswer={value => { edit(value); input.current?.focus(); }} streaming disabled />}</div>}
      </div>
      {away && <button className="study-jump" aria-label="回到最新消息" onClick={jump}><ArrowDown size={16} />最新消息</button>}
      <div className="study-compose-area"><ErrorNotice message={error} onDismiss={() => setError("")} /><form className="study-composer" onSubmit={send}><textarea ref={input} aria-label="发给学习教练" value={draft} maxLength={4000} rows={2} placeholder="发消息，问知识点，或者让我出题…" onChange={e => edit(e.target.value)} onKeyDown={e => { if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); if (!busy) send(e); } }} /><div><select aria-label="辅导模式" value={mode} disabled={!!id || busy} onChange={e => setMode(e.target.value)}>{Object.entries(modes).map(([key, name]) => <option value={key} key={key}>{name}</option>)}</select><span className="study-compose-counter">{draft.length ? `${draft.length} / 4000` : "Enter 发送，Shift + Enter 换行"}</span>{busy ? <Button type="button" icon={Square} disabled={stopping} onClick={stop}>停止生成</Button> : <Button type="submit" icon={ArrowUp} tone="primary" disabled={!draft.trim() || loading}>发送</Button>}</div></form><p className="composer-help">{busy ? "可以先写下一条消息，回答结束后发送。" : "回答供学习参考，关键结论可对照资料核验。"}</p></div>
    </section>
  </div>;
}
function ChatMessage({ message, streaming, onAnswer, retry, disabled }) {
  const payload = message.payload || {};
  return <article className={`study-message study-message-${message.role}`} data-streaming={streaming || undefined}>
    {message.role === "assistant" && <div className="study-message-author">学习教练{payload.cancelled && <Badge>已停止</Badge>}{payload.failed && <Badge tone="warning">回答中断</Badge>}</div>}
    {!!payload.tools?.length && <ToolTrace items={payload.tools} />}
    {message.role === "user" ? <div className="study-user-text">{message.content}</div> : <Markdown text={message.content} />}
    {payload.question && <QuestionCard question={payload.question} answer={onAnswer} />}
    {payload.failed && payload.error && message.content !== payload.error && <p className="study-turn-error">{payload.error}</p>}
    {!streaming && <div className="study-message-actions"><CopyButton text={message.content} label="复制消息" />{retry && <button disabled={disabled} onClick={retry}><RotateCcw size={13} />重新回答</button>}</div>}
  </article>;
}
function CopyButton({ text, label = "复制代码" }) {
  const [copied, setCopied] = useState(false);
  async function copy() { try { await navigator.clipboard.writeText(text); setCopied(true); setTimeout(() => setCopied(false), 1800); } catch { setCopied(false); } }
  return <button type="button" aria-label={label} onClick={copy}>{copied ? <Check size={13} /> : <Copy size={13} />}{copied ? "已复制" : label}</button>;
}
function codeText(node) { return typeof node === "string" ? node : Array.isArray(node) ? node.map(codeText).join("") : node?.props ? codeText(node.props.children) : ""; }
function Markdown({ text }) { return <div className="markdown-body"><ReactMarkdown remarkPlugins={[remarkGfm, remarkMath]} rehypePlugins={[rehypeKatex]} components={{ pre: ({ children }) => <div className="study-code"><div className="study-code-toolbar"><span>{children?.props?.className?.replace("language-", "") || "代码"}</span><CopyButton text={codeText(children).replace(/\n$/, "")} /></div><pre>{children}</pre></div>, a: ({ children, ...props }) => <a {...props} target="_blank" rel="noreferrer">{children}</a> }}>{text || ""}</ReactMarkdown></div>; }
function resultSummary(item) {
  if (item.running) return "正在检索";
  if (item.result?.error) return item.result.error;
  if (Array.isArray(item.result)) return `找到 ${item.result.length} 条结果`;
  if (item.name === "pick_question") return item.result?.topic_title || "已选好题目";
  if (item.name === "question_reference") return "已读取参考答案";
  if (item.name === "learning_progress") return "已读取学习记录";
  return "已完成";
}
function ToolTrace({ items }) { return <details className="tool-trace"><summary><ChevronDown size={13} />学习工具 · {items.length} 次{items.some(i => i.running) && <LoaderCircle size={12} className="spin" />}</summary>{items.map((item, index) => <div className="study-tool-row" key={item.id || index}><span>{item.running ? <LoaderCircle size={13} className="spin" /> : <Check size={13} />}</span><div><strong>{toolNames[item.name] || "学习工具"}</strong><small>{resultSummary(item)}</small>{!item.running && <details className="study-tool-result"><summary>查看检索结果</summary><pre>{JSON.stringify(item.result, null, 2)}</pre></details>}</div></div>)}</details>; }
function QuestionCard({ question, answer }) { return <div className="chat-question"><div><ListChecks size={17} /><strong>{question.topic_title}</strong><Badge>练习题</Badge></div><Markdown text={question.stem} />{question.options?.map((option, i) => <button className="chat-option" key={i} onClick={() => answer(`我的答案是 ${String.fromCharCode(65 + i)}：${option}`)}><span>{String.fromCharCode(65 + i)}</span>{option}</button>)}<div className="chat-question-actions"><Button onClick={() => answer("我的答案是：")}>在对话里作答</Button><a href={`#/practice?topic=${encodeURIComponent(question.knowledge_point_id)}`}>去练习页记录成绩</a></div>{question.source_url && <a className="chat-question-source" href={question.source_url} target="_blank" rel="noreferrer">{question.source_name || "题目来源"}</a>}</div>; }
