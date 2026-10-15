import { useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Plus, Send, SkipForward, Flag, Download, Printer, Trash2, Play, ArrowUpRight } from "lucide-react";
import { api, post, navigate, dateLabel } from "../lib/api.js";
import { Button, Badge, ErrorNotice, PageHeading, Loading } from "../components/UI.jsx";
import "./interview.css";

const TYPES = { knowledge: "八股与场景", algorithm: "算法", project: "项目深挖" };
const LEVELS = { none: "无开发经验 / 转行", campus: "校招 / 应届生", junior: "初级工程师", senior: "有经验工程师" };
const DIFFICULTIES = { auto: "自动匹配阶段", basic: "基础档：应届 / 无经验", intermediate: "进阶档：场景与排障", advanced: "高阶档：设计与验证" };
const COMPANY_STYLES = { general: "通用场景", small: "小厂练习：交付与基础", medium: "中厂练习：模块与稳定性", large: "大厂练习：算法与规模" };
const INTENSITIES = { gentle: "基础问答，不追加追问", standard: "标准面试，每题最多追问一次", deep: "深挖面试，每题最多追问两次" };
const LANGUAGES = { java: "Java", go: "Go", python: "Python", javascript: "JavaScript" };
const DIMENSIONS = { correctness: ["正确性", 40], depth: ["理解深度", 25], clarity: ["表达", 15], engineering: ["工程判断", 20] };
const INITIAL = { title: "", role: "Java 后端", jd: "", resume: "", mode: "bank", level: "campus", difficulty: "auto", company_style: "general", intensity: "standard", language: "java", feedback_mode: "at_end", question_count: 6, categories: Object.keys(TYPES) };
function stored(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) || fallback; } catch { return fallback; } }
function remember(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* Disabled or full storage. */ } }
function forget(key) { try { localStorage.removeItem(key); } catch { /* Disabled storage. */ } }
function Markdown({ children }) { return <div className="markdown-body"><ReactMarkdown remarkPlugins={[remarkGfm]}>{children || ""}</ReactMarkdown></div>; }

export default function Interview({ params, settings }) {
  const id = params.get("session") || "";
  const [form, setForm] = useState(() => ({ ...INITIAL, ...stored("devprep.interview.setup", {}) }));
  const [session, setSession] = useState(null);
  const [sessions, setSessions] = useState([]);
  const [resumes, setResumes] = useState([]);
  const [catalog, setCatalog] = useState(null);
  const [busy, setBusy] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const locked = useRef(false), tail = useRef(null);
  const refresh = () => api("/interviews").then(data => setSessions(data.sessions));
  useEffect(() => { refresh().catch(err => setError(err.message)); api("/resumes").then(data => setResumes(data.workspaces)).catch(() => {}); }, []);
  useEffect(() => { api("/interviews/catalog").then(setCatalog).catch(() => {}); }, []);
  useEffect(() => { remember("devprep.interview.setup", form); }, [form]);
  useEffect(() => {
    const controller = new AbortController(); setError(""); setSession(null);
    if (!id) { setLoading(false); return () => controller.abort(); }
    setLoading(true);
    api(`/interviews/${id}`, { signal: controller.signal }).then(data => setSession(data)).catch(err => { if (!controller.signal.aborted) setError(err.message); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [id]);
  const current = session?.status === "active" ? session.turns.at(-1) : null;
  useEffect(() => { if (current) tail.current?.scrollIntoView({ block: "nearest" }); }, [current?.id]);
  const update = (key, value) => setForm(prev => ({ ...prev, [key]: value }));
  async function action(label, task) {
    if (locked.current) return null;
    locked.current = true; setBusy(label); setError("");
    try { return await task(); } catch (err) { setError(err.message); return null; } finally { locked.current = false; setBusy(""); }
  }
  async function start() {
    await action(form.mode === "ai" ? "正在结合 JD 组卷" : "正在抽取题库", async () => {
      const result = await post("/interviews", form); setSession(result); navigate("interview", { session: result.id }); refresh().catch(() => {});
    });
  }
  async function submit(path, data = {}) {
    return action(path === "answer" ? "正在判分并考虑下一道追问" : path === "run" ? "正在运行算法样例" : "正在保存面试", async () => {
      const result = await post(`/interviews/${id}/${path}`, { revision: session.revision, turn_id: current?.id, ...data });
      setSession(result); refresh().catch(() => {}); return result;
    });
  }
  async function importResume(value) {
    if (!value) return;
    await action("正在读取简历", async () => {
      const result = await api(`/resumes/${value}`); const text = result.versions[0]?.markdown || `${result.workspace.original}\n${result.workspace.facts}`;
      if (text.length > 20000) throw new Error("这份简历超过面试材料长度，请精简后粘贴。 ");
      setForm(prev => ({ ...prev, resume: text, jd: prev.jd || result.workspace.jd }));
    });
  }
  async function remove() {
    if (!window.confirm("删除这场面试及回答记录？")) return;
    await action("正在删除记录", async () => { await post(`/interviews/${id}/delete`, { revision: session.revision }); navigate("interview"); refresh().catch(() => {}); });
  }

  return <section className="interview-page">
    <PageHeading title="模拟面试" description="按岗位练基础，沿回答追项目。每次只问一道，结束后逐项复盘。" action={<Button icon={Plus} disabled={!!busy} onClick={() => navigate("interview")}>新建面试</Button>} />
    <ErrorNotice message={error} retry={id && !busy ? () => api(`/interviews/${id}`).then(setSession).catch(err => setError(err.message)) : undefined} onDismiss={() => setError("")} />
    <div className={`interview-layout ${session ? "with-session" : ""}`}>
      <aside className="interview-sidebar">
        {session ? <div className="interview-brief">
          <h2>{session.title}</h2><p>{session.role} · {LEVELS[session.level]}</p>
          <dl><dt>出题方式</dt><dd>{session.mode === "ai" ? "JD 定向" : "题库随机"}</dd><dt>难度</dt><dd>{DIFFICULTIES[session.difficulty_resolved] || DIFFICULTIES[session.difficulty] || "自动匹配"}</dd><dt>场景</dt><dd>{COMPANY_STYLES[session.company_style] || "通用场景"}</dd><dt>主问题</dt><dd>{session.question_count} 道</dd><dt>代码语言</dt><dd>{LANGUAGES[session.language]}</dd><dt>追问</dt><dd>{INTENSITIES[session.intensity]}</dd></dl>
          <details><summary>本场考查重点与材料</summary><ul>{session.focus.map((text, i) => <li key={i}>{text}</li>)}</ul>{session.jd && <><h3>目标 JD</h3><p className="interview-material-text">{session.jd}</p></>}{session.resume && <><h3>项目 / 简历材料</h3><p className="interview-material-text">{session.resume}</p></>}</details>
          {session.difficulty_standard && <details><summary>本场难度与判分标准</summary><p>{session.difficulty_standard.criteria}</p><p>{session.difficulty_standard.focus}</p><small>{session.difficulty_standard.note}</small></details>}
          <small>材料保存在本机。使用 AI 出题和判分时，本场材料及相关回答会发送到你配置的模型网关。</small>
          {session.status === "active" && <Button icon={Flag} disabled={!!busy} onClick={() => { if (window.confirm("提前结束并生成已有回答的复盘？未作答题目会单独列出。")) submit("finish"); }}>结束并复盘</Button>}
          <button className="text-button interview-delete" disabled={!!busy} onClick={remove}><Trash2 size={14} />删除这场面试</button>
        </div> : <div className="interview-context"><h2>先确定这一场练什么</h2><p>校招、社招的考查深度不同。填入真实项目材料，追问才能落到你做过的实现和取舍上。</p><ol><li>选择岗位与题型</li><li>逐题作答，接受追问</li><li>查看判分依据与补强清单</li></ol><p>题库随机出题无需密钥；JD 出题、评分和追问使用当前配置的 GPT 网关。</p></div>}
        <details className="interview-history" open><summary>面试记录 <span>{sessions.length}</span></summary>{sessions.length ? sessions.map(item => <button key={item.id} disabled={!!busy} className={id === item.id ? "selected" : ""} onClick={() => navigate("interview", { session: item.id })}><strong>{item.title}</strong><small>{item.status === "active" ? "进行中" : "已结束"} · {dateLabel(item.updated_at)}</small></button>) : <p>完成或暂停的面试会留在这里。</p>}</details>
      </aside>
      <div className="interview-main">
        {loading ? <Loading label="正在读取面试" /> : !session ? <form className="interview-setup" onSubmit={event => { event.preventDefault(); start(); }}>
          <div className="interview-mode" role="group" aria-label="出题方式"><button type="button" aria-pressed={form.mode === "bank"} onClick={() => update("mode", "bank")}>题库随机面试</button><button type="button" aria-pressed={form.mode === "ai"} onClick={() => update("mode", "ai")}>JD 定向面试</button></div>
          <div className="interview-form-row"><label>目标岗位<input required maxLength={100} list="interview-roles" value={form.role} onChange={e => update("role", e.target.value)} /></label><label>面试名称<input maxLength={100} placeholder="留空则使用岗位名称" value={form.title} onChange={e => update("title", e.target.value)} /></label></div>
          <datalist id="interview-roles">{["Java 后端","Go 后端","前端开发","Python 开发","AI 应用开发","Agent 开发","测试开发","运维 / 云原生"].map(role => <option key={role} value={role} />)}</datalist>
          <label>目标岗位 JD{form.mode === "bank" && <small>可选，帮助筛选相关题目和判分</small>}<textarea required={form.mode === "ai"} maxLength={16000} value={form.jd} onChange={e => update("jd", e.target.value)} placeholder="粘贴岗位职责、技术要求和职级。定向出题会围绕这些要求组织问题。" /></label>
          {!!resumes.length && <label>从简历工作区导入<select disabled={!!busy} defaultValue="" onChange={e => importResume(e.target.value)}><option value="">选择一份已有简历，可继续编辑材料</option>{resumes.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}</select></label>}
          <label>项目经历 / 原始简历<small>可选。说明自己做了什么、难点、取舍和验证过程；没有材料会先请你介绍项目。</small><textarea maxLength={20000} value={form.resume} onChange={e => update("resume", e.target.value)} placeholder="例如：校园订单项目，本人实现库存扣减与缓存。使用本地压测验证并发冲突……" /></label>
          <div className="interview-form-row"><label>候选人阶段<select value={form.level} onChange={e => update("level", e.target.value)}>{Object.entries(LEVELS).map(([value, text]) => <option key={value} value={value}>{text}</option>)}</select></label><label>面试难度<select value={form.difficulty} onChange={e => update("difficulty", e.target.value)}>{Object.entries(DIFFICULTIES).map(([value, text]) => <option key={value} value={value}>{text}</option>)}</select></label></div>
          <div className="interview-form-row"><label>练习场景<select value={form.company_style} onChange={e => update("company_style", e.target.value)}>{Object.entries(COMPANY_STYLES).map(([value, text]) => <option key={value} value={value}>{text}</option>)}</select></label><label>算法语言<select value={form.language} onChange={e => update("language", e.target.value)}>{Object.entries(LANGUAGES).map(([value, text]) => <option key={value} value={value}>{text}</option>)}</select></label></div>
          <p className="interview-privacy">难度标准：基础看概念、简单应用和完整链路；进阶加入原理、场景取舍与排障；高阶加入系统设计、性能和验证证据。场景只调整侧重，不替代难度。题库会显示来源，面经仅作练习参考。</p>
          {catalog && <details className="interview-catalog"><summary>离线面试素材：{catalog.total} 条 · {catalog.documents} 份来源文档</summary><p>{catalog.note}</p><p>候选人回忆 {catalog.source_kinds.candidate_recollection || 0} 条，公司题合集 {catalog.source_kinds.company_collection || 0} 条，原创演练 {catalog.source_kinds.original || 0} 条。</p><p>基础 {catalog.difficulties.basic || 0} · 进阶 {catalog.difficulties.intermediate || 0} · 高阶 {catalog.difficulties.advanced || 0}</p><p>{catalog.companies.join("、")}</p></details>}
          <fieldset><legend>题型范围</legend><div className="interview-category-options">{Object.entries(TYPES).map(([value, text]) => <label key={value}><input type="checkbox" checked={form.categories.includes(value)} onChange={e => update("categories", e.target.checked ? [...form.categories, value] : form.categories.filter(kind => kind !== value))} />{text}</label>)}</div></fieldset>
          <div className="interview-form-row"><label>主问题数量<input type="number" min={3} max={12} value={form.question_count} onChange={e => update("question_count", Number(e.target.value))} /></label><label>追问强度<select value={form.intensity} onChange={e => update("intensity", e.target.value)}>{Object.entries(INTENSITIES).map(([value, text]) => <option key={value} value={value}>{text}</option>)}</select></label></div>
          <label>反馈时机<select value={form.feedback_mode} onChange={e => update("feedback_mode", e.target.value)}><option value="at_end">面试模式：结束后统一看评分和参考思路</option><option value="after_each">练习模式：每题回答后立即看反馈</option></select></label>
          <p className="interview-privacy">AI 请求逐个执行，不并发，默认两次请求间隔 10 秒。当前模型：{settings?.model || "未配置，可先抽题"}。</p>
          <Button type="submit" icon={Send} tone="primary" busy={!!busy} disabled={!form.categories.length}>{busy || "开始面试"}</Button>
        </form> : <>
          <header className="interview-session-bar"><span><Badge tone={session.status === "active" ? "success" : "neutral"}>{session.status === "active" ? "面试进行中" : "面试已结束"}</Badge>{current && <span>主问题 {current.main_index} / {session.question_count}{current.depth > 0 ? ` · 追问 ${current.depth}` : ""}</span>}</span><small>{session.feedback_mode === "at_end" && session.status === "active" ? "评分和参考思路在结束后展示" : "回答和判分已保存"}</small></header>
          {session.status === "completed" && <InterviewReport session={session} />}
          <div className="interview-transcript" aria-label="面试对话">{session.turns.map(turn => <article key={turn.id} className={`interview-turn ${turn.id === current?.id ? "current" : ""}`}>
            <div className="interview-turn-label"><span>面试官</span><Badge>{TYPES[turn.kind]}</Badge><small>第 {turn.main_index} 题{turn.depth ? ` / 追问 ${turn.depth}` : ""}</small></div>
            <h2>{turn.title}</h2><Markdown>{turn.prompt}</Markdown>
            <div className="interview-source">{turn.source_name}{turn.company && <span> · {turn.company}{turn.round && ` / ${turn.round}`}</span>}{turn.source_date && turn.source_date !== "未核对" && <span> · 原帖发布 {turn.source_date}</span>}{turn.source_verification && <span> · {turn.source_verification.startsWith("已打开") ? "已核对原帖" : "归档记录"}</span>}{turn.attribution && <span> · {turn.attribution}</span>}{/^https?:\/\//.test(turn.source_url || "") && <a href={turn.source_url} target="_blank" rel="noreferrer">来源 <ArrowUpRight size={12} /></a>}</div>
            {!!turn.experience_sources?.length && <details><summary>出题参考</summary><ul>{turn.experience_sources.map(source => <li key={source.id}>{source.company || "原创演练"} · {source.source_name} · {source.attribution}{/^https?:\/\//.test(source.source_url || "") && <> <a href={source.source_url} target="_blank" rel="noreferrer">查看来源</a></>}</li>)}</ul><small>问题由 AI 改编，参考材料不代表企业官方要求。</small></details>}
            {turn.state === "answered" && <div className="interview-candidate"><span>我的回答</span><Markdown>{turn.answer}</Markdown>{turn.code && <pre><code>{turn.code}</code></pre>}</div>}
            {turn.state === "skipped" && <p className="interview-skipped">本题已跳过，未评分。</p>}
            {session.status === "completed" && turn.state === "pending" && <p className="interview-skipped">提前结束，未作答。</p>}
            {turn.evaluation && <Evaluation grade={turn.evaluation} />}
            {turn.execution && turn.state !== "pending" && <div className="interview-execution" role="status"><strong>本地样例：{turn.execution.result.status === "accepted" ? "已通过" : turn.execution.result.status}</strong><span>通过 {turn.execution.result.passed || 0} / {turn.execution.result.total || 0} 个；只代表本次样例运行，修改代码后需重新运行。</span>{turn.execution.result.compiler_output && <pre>{turn.execution.result.compiler_output}</pre>}</div>}
          </article>)}</div>
          {current && <div ref={tail}><AnswerComposer key={`${id}.${current.id}`} session={session} turn={current} busy={busy} submit={submit} /></div>}
        </>}
      </div>
    </div>
    {busy && <div className="interview-busy" role="status">{busy}…{busy.includes("判分") || busy.includes("JD") ? "模型调用包含请求间隔，请稍等。" : ""}</div>}
  </section>;
}

function AnswerComposer({ session, turn, busy, submit }) {
  const key = `devprep.interview.answer.${session.id}.${turn.id}`;
  const [draft, setDraft] = useState(() => stored(key, { answer: "", code: turn.starter_code || "" }));
  const [seconds, setSeconds] = useState(0);
  useEffect(() => { remember(key, draft); }, [key, draft]);
  useEffect(() => { const tick = () => setSeconds(Math.max(0, Math.floor((Date.now() - new Date(turn.asked_at)) / 1000))); tick(); const timer = setInterval(tick, 1000); return () => clearInterval(timer); }, [turn.asked_at]);
  async function answer() { if (await submit("answer", draft)) forget(key); }
  const runnable = turn.kind === "algorithm" && turn.bank_id && (turn.can_run === true || (turn.can_run !== false && (!turn.runnable_languages?.length || turn.runnable_languages.includes(session.language))));
  return <div className="interview-composer">
    <div className="interview-composer-top"><strong>{turn.depth ? "回答追问" : "你的回答"}</strong><span aria-label="本题已用时间">已用 {Math.floor(seconds / 60)} 分 {seconds % 60} 秒</span></div>
    <label className="sr-only" htmlFor="interview-answer">面试回答</label><textarea id="interview-answer" maxLength={12000} disabled={!!busy} value={draft.answer} onChange={e => setDraft(prev => ({ ...prev, answer: e.target.value }))} placeholder="先说结论，再展开原因与边界；项目题请说清你自己的实现。不会也可以直接说明。" />
    {turn.kind === "algorithm" && <><label className="interview-code-label">{LANGUAGES[session.language]} 代码<textarea aria-label="算法代码" className="interview-code" spellCheck={false} maxLength={30000} disabled={!!busy} value={draft.code} onChange={e => setDraft(prev => ({ ...prev, code: e.target.value }))} placeholder="可以写实现，也可以先在回答里说明思路与复杂度。" /></label><div className="interview-algorithm-actions">{runnable && <Button icon={Play} disabled={!!busy || !draft.code.trim()} onClick={async () => { const result = await submit("run", { code: draft.code }); if (result) setDraft(prev => ({ ...prev, last_run_code: draft.code })); }}>运行样例</Button>}{turn.language_note && <small>{turn.language_note}</small>}{turn.bank_id && <a href={`#/algorithms?problem=${encodeURIComponent(turn.bank_id)}`} target="_blank" rel="noreferrer">打开算法工作区 <ArrowUpRight size={13} /></a>}<small>AI 评审与样例运行分别展示，AI 分数不代表官方 AC。</small></div></>}
    {turn.execution && (draft.last_run_code === draft.code ? <div className="interview-execution" role="status"><strong>本地样例：{turn.execution.result.status === "accepted" ? "已通过" : "未通过"}</strong><span>通过 {turn.execution.result.passed || 0} / {turn.execution.result.total || 0} 个公开样例。</span>{turn.execution.result.compiler_output && <pre>{turn.execution.result.compiler_output}</pre>}{turn.execution.result.cases?.filter(row => !row.passed).map(row => <pre key={row.index}>样例 {row.index}：{row.status}{"\n"}{row.stderr || ""}</pre>)}</div> : <p className="interview-skipped" role="status">代码已修改，或尚未核对这份草稿。请重新运行；先前的样例结果不代表当前代码。</p>)}
    <div className="interview-composer-actions"><small>草稿自动保存在这台电脑，不会自动提交。</small><Button icon={SkipForward} disabled={!!busy} onClick={async () => { if (draft.answer || draft.code) { if (!window.confirm("跳过当前题目？草稿会保留在本机，本题不评分。")) return; } await submit("skip"); }}>跳过本题</Button><Button icon={Send} tone="primary" busy={busy.includes("判分")} disabled={!!busy || (!draft.answer.trim() && !draft.code.trim())} onClick={answer}>提交回答</Button></div>
  </div>;
}

function Evaluation({ grade }) {
  return <details className="interview-evaluation"><summary>本题评分 <strong>{grade.score} / 100</strong><span>查看依据与参考思路</span></summary><div className="interview-dimensions">{Object.entries(DIMENSIONS).map(([key, [label, max]]) => <div key={key}><span>{label}</span><strong>{grade.dimensions[key].score} / {max}</strong><p>{grade.dimensions[key].reason}</p></div>)}</div><Markdown>{grade.feedback}</Markdown>{grade.evidence && <blockquote>回答依据：“{grade.evidence}”</blockquote>}<div className="interview-findings"><div><h3>答对的点</h3><ul>{grade.strengths.map((text, i) => <li key={i}>{text}</li>)}</ul></div><div><h3>遗漏与问题</h3><ul>{grade.gaps.map((text, i) => <li key={i}>{text}</li>)}</ul></div></div><h3>参考思路</h3><Markdown>{grade.sample_answer}</Markdown><small>评分模型：{grade.model}；仅针对本次回答。</small></details>;
}

function InterviewReport({ session }) {
  const report = session.report;
  return <section className="interview-report" aria-label="面试复盘报告"><div className="interview-report-heading"><div><h2>这场面试的复盘</h2><p>{report.scored} 题已评分，{report.skipped} 题跳过，{report.unanswered} 题未作答</p></div><div className="interview-report-score">{report.score === null ? "—" : report.score}<small>/ 100</small></div></div><p className="interview-score-rule">{report.score_rule}</p><div className="interview-report-categories">{report.categories.map(group => <div key={group.kind}><span>{TYPES[group.kind]}</span><strong>{group.score === null ? "未评分" : `${group.score} 分`}</strong><small>{group.count} 道已评分</small></div>)}</div><div className="interview-findings"><div><h3>保留的优势</h3>{report.strengths.length ? <ul>{report.strengths.map((text, i) => <li key={i}>{text}</li>)}</ul> : <p>暂无已评分回答，完成作答后再评估。</p>}</div><div><h3>优先补齐</h3>{report.gaps.length ? <ul>{report.gaps.map((text, i) => <li key={i}>{text}</li>)}</ul> : <p>本次尚未记录具体漏答点。</p>}</div></div>{!!report.practice_plan.length && <details><summary>下一轮练习安排</summary><ol>{report.practice_plan.map((text, i) => <li key={i}>{text}</li>)}</ol></details>}<div className="interview-report-actions"><a className="button" href={`/api/interviews/${session.id}/report.md`} download><Download size={15} />导出复盘 Markdown</a><Button icon={Printer} onClick={() => window.print()}>打印 / PDF</Button></div><p className="interview-report-note">逐题回答、判分依据和参考思路在下方。评分用于训练，不是录用结论；没有实际执行的代码不会标为已通过。</p></section>;
}


