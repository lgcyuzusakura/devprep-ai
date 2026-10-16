import { workspaceStorage as localStorage } from "../lib/workspaceStorage.js";
import { useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Plus, Upload, FilePenLine, Save, Download, Printer, Check, Trash2, Copy, ArrowRight } from "lucide-react";
import { api, post, navigate, dateLabel } from "../lib/api.js";
import { Button, ErrorNotice, PageHeading } from "../components/UI.jsx";
import "./resume.css";
import { DEFAULT_LAYOUT } from "../lib/resumeTemplates.js";
import { ResumeStarter, ResumeFormatting, ResumeExpansion } from "./ResumeStudio.jsx";

const EMPTY = { title: "", jd: "", original: "", facts: "", preferences: "" };
const DRAFT_KEY = "devprep.resume.draft";
function draft() { try { const saved = JSON.parse(localStorage.getItem(DRAFT_KEY)); return saved && typeof saved === "object" ? { ...EMPTY, ...saved } : { ...EMPTY }; } catch { return { ...EMPTY }; } }
function fields(workspace) { return Object.fromEntries(Object.keys(EMPTY).map(key => [key, workspace[key] || ""])); }
function download(markdown, name) {
  const url = URL.createObjectURL(new Blob([markdown], { type: "text/markdown;charset=utf-8" }));
  const link = document.createElement("a"); link.href = url; link.download = `${name.replace(/[\\/:*?"<>|]/g, "-") || "简历"}.md`; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export default function Resume({ settings }) {
  const [form, setForm] = useState(draft);
  const [mode, setMode] = useState(() => draft().jd ? "targeted" : "general");
  const [workspace, setWorkspace] = useState(null);
  const [workspaces, setWorkspaces] = useState([]);
  const [versions, setVersions] = useState([]);
  const [selected, setSelected] = useState(null);
  const [markdown, setMarkdown] = useState("");
  const [layout, setLayout] = useState({ ...DEFAULT_LAYOUT });
  const [selection, setSelection] = useState("");
  const [expansion, setExpansion] = useState(null);
  const [tab, setTab] = useState("analysis");
  const [edit, setEdit] = useState(false);
  const [enhance, setEnhance] = useState(true);
  const [answers, setAnswers] = useState({});
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const fileInput = useRef(null);
  const analysis = workspace?.analysis;
  const changedSource = workspace && (workspace.original !== form.original || workspace.jd !== (mode === "targeted" ? form.jd : ""));
  const hasAnalysis = analysis?.positioning && !changedSource;
  const dirty = workspace ? Object.keys(EMPTY).some(key => workspace[key] !== (key === "jd" && mode === "general" ? "" : form[key])) : Boolean(form.original || form.title || form.jd || form.facts);
  const edited = selected && (markdown !== selected.markdown || JSON.stringify(layout) !== JSON.stringify({ ...DEFAULT_LAYOUT, ...selected.layout }));
  const ready = Boolean(form.original.trim() && (mode === "general" || form.jd.trim()));

  async function refresh() { const result = await api("/resumes"); setWorkspaces(result.workspaces); }
  useEffect(() => { refresh().catch(err => setError(err.message)); }, []);
  useEffect(() => {
    if (workspace) return;
    try { localStorage.setItem(DRAFT_KEY, JSON.stringify(form)); } catch { /* Saving to the server remains available. */ }
  }, [form, workspace]);
  useEffect(() => {
    const warn = event => { if (dirty || edited || busy) { event.preventDefault(); event.returnValue = ""; } };
    window.addEventListener("beforeunload", warn); return () => window.removeEventListener("beforeunload", warn);
  }, [dirty, edited, busy]);

  function update(key, value) { setForm(current => ({ ...current, [key]: value })); setNotice(""); }
  function mayLeave() { return (!dirty && !edited) || window.confirm("还有未保存的修改，是否放弃这些修改？"); }
  function newWorkspace() {
    if (!mayLeave()) return;
    setWorkspace(null); setForm({ ...EMPTY }); setMode("general"); setVersions([]); setSelected(null); setMarkdown(""); setLayout({ ...DEFAULT_LAYOUT }); setSelection(""); setExpansion(null); setAnswers({}); setTab("analysis"); setNotice(""); setError("");
  }
  async function load(id) {
    if (busy || !mayLeave()) return;
    setBusy("正在打开工作稿"); setError("");
    try { const result = await api(`/resumes/${id}`); setWorkspace(result.workspace); setForm(fields(result.workspace)); setMode(result.workspace.jd ? "targeted" : "general"); setVersions(result.versions); chooseVersion(result.versions[0] || null); setAnswers({}); setTab("analysis"); setNotice(""); }
    catch (err) { setError(err.message); } finally { setBusy(""); }
  }
  function chooseVersion(version) { setSelected(version); setMarkdown(version?.markdown || ""); setLayout({ ...DEFAULT_LAYOUT, ...version?.layout }); setSelection(""); setExpansion(null); setEdit(false); }
  async function createDraft(text, title) {
    if (busy || !mayLeave()) return;
    setBusy("正在创建简历工作稿"); setError("");
    try {
      const saved = await post("/resumes", { ...EMPTY, title, original: text });
      setWorkspace(saved); setForm(fields(saved)); setMode("general");
      const version = await post(`/resumes/${saved.id}/draft`, { revision: saved.revision, markdown: text, layout: DEFAULT_LAYOUT });
      const result = await api(`/resumes/${saved.id}`); setWorkspace(result.workspace); setVersions(result.versions); chooseVersion(version); setEdit(true); setTab("resume"); setAnswers({});
      localStorage.removeItem(DRAFT_KEY); await refresh(); setNotice("工作稿已创建，可以编辑正文、调整版式，或分析后继续改写");
    } catch (err) { setError(err.message); } finally { setBusy(""); }
  }
  async function expand(instruction) {
    if (busy || !selection.trim()) return;
    setBusy("正在扩写所选经历"); setError(""); setExpansion(null);
    try {
      const saved = await saveInputs();
      const result = await post(`/resumes/${saved.id}/expand`, { revision: saved.revision, selection, instruction });
      setExpansion({ ...result, original: selection }); await refresh();
    } catch (err) { setError(err.message); } finally { setBusy(""); }
  }
  function adoptExpansion(kind) {
    if (!expansion) return;
    const next = kind === "replace" ? markdown.replace(expansion.original, expansion.expanded) : `${markdown.trimEnd()}\n\n${expansion.expanded}`;
    if (next.length > 40000) { setError("正文过长，请精简后再加入。"); return; }
    const fact = `AI 扩写采用记录（待自行核验）：\n${expansion.expanded}\n核对：${expansion.checks.join("；")}`;
    if ((form.facts + fact).length > 12000) { setError("补充材料过长，请精简后再采用扩写。"); return; }
    update("facts", `${form.facts}${form.facts ? "\n\n" : ""}${fact}`);
    setMarkdown(next); setExpansion(null); setNotice("扩写已加入正文，请核对后保存新版本");
  }
  async function saveInputs() {
    const body = { ...form, title: form.title.trim() || (mode === "targeted" ? "岗位定向简历" : "简历工作稿"), jd: mode === "targeted" ? form.jd : "" };
    const result = await post(workspace ? `/resumes/${workspace.id}` : "/resumes", { ...body, ...(workspace ? { revision: workspace.revision } : {}) });
    setWorkspace(result); setForm(fields(result));
    try { localStorage.removeItem(DRAFT_KEY); } catch { /* Storage can be disabled. */ }
    return result;
  }
  async function run(task) {
    if (busy) return;
    setBusy(task === "analyze" ? "正在分析经历、优势与修改重点" : task === "rewrite" ? "正在重写简历并核对数值与依据" : "正在保存材料"); setError(""); setNotice("");
    try {
      const saved = await saveInputs();
      if (task === "analyze") { const result = await post(`/resumes/${saved.id}/analyze`, { revision: saved.revision, enhance }); setWorkspace(result); setTab("analysis"); setAnswers({}); }
      else if (task === "rewrite") {
        const version = await post(`/resumes/${saved.id}/rewrite`, { revision: saved.revision, layout });
        const result = await api(`/resumes/${saved.id}`); setWorkspace(result.workspace); setVersions(result.versions); chooseVersion(version); setTab("resume");
      } else setNotice("材料已保存到本机工作区");
      await refresh();
    } catch (err) { setError(err.message); } finally { setBusy(""); }
  }
  async function saveVersion() {
    const saved = dirty ? await saveInputs() : workspace;
    const result = await post(`/resumes/${saved.id}/versions/${selected.id}`, { revision: saved.revision, markdown, layout });
    const current = await api(`/resumes/${saved.id}`); setWorkspace(current.workspace); setVersions(current.versions); chooseVersion(result); return result;
  }
  async function exportVersion(kind) {
    if (busy || !selected || !markdown.trim()) return;
    setBusy(edited ? "正在保存编辑稿" : "正在导出"); setError("");
    try {
      const version = edited || dirty ? await saveVersion() : selected;
      if (kind === "markdown") download(version.markdown, form.title);
      else if (kind === "docx") {
        const response = await fetch(`/api/resumes/${workspace.id}/versions/${version.id}/docx`);
        if (!response.ok) throw new Error("Word 导出失败，请尝试 Markdown 导出。");
        const url = URL.createObjectURL(await response.blob()); const link = document.createElement("a"); link.href = url; link.download = `${form.title || "简历"}.docx`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      } else { setEdit(false); setTimeout(() => window.print(), 100); }
      await refresh();
    } catch (err) { setError(err.message); } finally { setBusy(""); }
  }
  async function saveEdits() {
    setBusy("正在保存编辑稿"); setError("");
    try { await saveVersion(); await refresh(); setNotice("已保存为新版本，原版本仍可查看"); } catch (err) { setError(err.message); } finally { setBusy(""); }
  }
  async function remove() {
    if (!workspace || !window.confirm("删除这份工作稿及全部简历版本？")) return;
    setBusy("正在删除"); setError("");
    try { await post(`/resumes/${workspace.id}/delete`, { revision: workspace.revision }); setWorkspace(null); setForm({ ...EMPTY }); setVersions([]); setSelected(null); setMarkdown(""); setAnswers({}); setSelection(""); setExpansion(null); await refresh(); }
    catch (err) { setError(err.message); } finally { setBusy(""); }
  }
  async function importFile(event) {
    const file = event.target.files?.[0]; event.target.value = ""; if (!file) return;
    if (file.size > 2_000_000) { setError("请选择 2 MB 以内的文件。"); return; }
    if (form.original && !window.confirm("导入文件会替换当前原始简历，是否继续？")) return;
    setBusy("正在提取简历文字"); setError("");
    try {
      const data = await new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(reader.result.split(",")[1]); reader.onerror = () => reject(new Error("文件无法读取")); reader.readAsDataURL(file); });
      const result = await post("/resumes/extract", { filename: file.name, content: data }); update("original", result.text); setNotice("文字已提取，请核对换行、日期和联系方式后分析");
    } catch (err) { setError(err.message); } finally { setBusy(""); }
  }
  function addAnswers() {
    const additions = (analysis?.questions || []).filter(q => answers[q.id]?.trim()).map(q => `${q.question}\n${answers[q.id].trim()}`).join("\n\n");
    if (!additions) return;
    if ((form.facts + additions).length > 11900) { setError("补充材料过长，请精简后再加入。"); return; }
    update("facts", `${form.facts}${form.facts ? "\n\n" : ""}${additions}`); setAnswers({}); setNotice("回答已加入补充材料，生成简历时会一并使用");
  }
  function adopt(suggestion) {
    const value = `增强设想（用户采用，待自行核验）：${suggestion.claim}\n前提：${suggestion.assumptions}\n核验：${suggestion.verification}\n面试追问：${suggestion.followup}`;
    if ((form.facts + value).length > 12000) { setError("补充材料过长，请精简后再加入。"); return; }
    update("facts", `${form.facts}${form.facts ? "\n\n" : ""}${value}`); setNotice("已加入增强设想，可在补充材料里调整数值和口径");
  }

  return <div className="resume-workbench">
    <PageHeading title="简历修改" description="从已有经历出发，把值得被看到的内容写清楚。" action={<Button icon={Plus} onClick={newWorkspace} disabled={Boolean(busy)}>新建工作稿</Button>} />
    <ErrorNotice message={error} onDismiss={() => setError("")} />
    {notice && <div className="resume-notice" role="status"><Check size={16} />{notice}</div>}
    <ResumeStarter busy={Boolean(busy)} onCreate={createDraft} />
    <div className="resume-layout">
      <aside className="resume-inputs">
        <label className="resume-field">工作稿名称<input maxLength={80} value={form.title} disabled={Boolean(busy)} onChange={e => update("title", e.target.value)} placeholder="例如：Java 后端 · 校招投递" /></label>
        <div className="resume-mode" role="group" aria-label="修改方式">{[["general", "普通修改"], ["targeted", "JD 定向修改"]].map(([id, label]) => <button key={id} aria-pressed={mode === id} disabled={Boolean(busy)} onClick={() => setMode(id)}>{label}</button>)}</div>
        {mode === "targeted" && <label className="resume-field">目标岗位 JD<textarea maxLength={16000} value={form.jd} disabled={Boolean(busy)} onChange={e => update("jd", e.target.value)} placeholder="粘贴岗位职责、必要条件和加分项。" className="resume-jd" /></label>}
        <div className="resume-label-row"><label htmlFor="resume-original">原始简历</label><button className="text-button" disabled={Boolean(busy)} onClick={() => fileInput.current.click()}><Upload size={14} />导入文件</button><input type="file" ref={fileInput} hidden accept=".pdf,.docx,.txt,.md" onChange={importFile} /></div>
        <textarea id="resume-original" className="resume-original" value={form.original} maxLength={30000} disabled={Boolean(busy)} onChange={e => update("original", e.target.value)} placeholder={"粘贴完整简历，保留项目细节、日期和已有成果。\n\n也可导入 PDF、Word、TXT 或 Markdown。"} />
        <span className="resume-field-help">{form.original.length.toLocaleString()} / 30,000 字 · 文件最多 2 MB</span>
        <label className="resume-field">补充材料<textarea value={form.facts} maxLength={12000} disabled={Boolean(busy)} onChange={e => update("facts", e.target.value)} placeholder="补充个人职责、具体交付、规模、测试环境和成果口径。也可以在分析后回答追问。" /></label>
        <label className="resume-field">修改偏好<input maxLength={2000} value={form.preferences} disabled={Boolean(busy)} onChange={e => update("preferences", e.target.value)} placeholder="例如：应届生，一页以内，突出工程实现" /></label>
        <label className="resume-enhance-toggle"><input type="checkbox" checked={enhance} disabled={Boolean(busy)} onChange={e => setEnhance(e.target.checked)} /><span>同时提出合理的增强设想<small>列出表述、前提、验证方法和面试追问，由你选择是否采用。</small></span></label>
        <div className="resume-input-actions"><Button icon={Save} onClick={() => run("save")} disabled={!ready || Boolean(busy)}>保存材料</Button><Button tone="primary" icon={FilePenLine} onClick={() => run("analyze")} disabled={!ready || Boolean(busy)}>分析简历</Button></div>
        {!settings?.model && <p className="resume-model-hint">先<button className="text-button" onClick={() => navigate("settings")}>配置模型和密钥</button>，即可分析和改写。材料可以先保存。</p>}
        <details className="resume-library" open={workspaces.length > 0}><summary>已保存工作稿 <span>{workspaces.length}</span></summary><div>{workspaces.map(item => <button key={item.id} className={workspace?.id === item.id ? "selected" : ""} disabled={Boolean(busy)} onClick={() => load(item.id)}><span>{item.title}</span><small>{dateLabel(item.updated_at)}</small></button>)}</div>{workspace && <button className="text-button resume-delete" disabled={Boolean(busy)} onClick={remove}><Trash2 size={14} />删除当前工作稿</button>}</details>
      </aside>
      <section className="resume-output">
        <div className="resume-output-top"><div className="resume-tabs" role="tablist" aria-label="简历工作区">{[["analysis", "诊断与补充"], ["resume", "简历正文"], ["changes", "修改对照"]].map(([id, name]) => <button key={id} role="tab" aria-selected={tab === id} onClick={() => setTab(id)}>{name}</button>)}</div><Button icon={ArrowRight} tone="primary" onClick={() => run("rewrite")} disabled={!hasAnalysis || Boolean(busy) || Object.values(answers).some(v => v.trim())}>生成改写稿</Button></div>
        {busy && <div className="resume-progress" role="status"><span className="resume-progress-dot" />{busy}…<small>每步仅发起一次模型请求，请等当前任务完成。</small></div>}
        {changedSource && <p className="resume-stale">原始简历或 JD 已修改，请重新分析；已有简历版本仍然保留。</p>}
        {tab === "analysis" && (!analysis?.positioning ? <div className="resume-start"><FilePenLine size={30} strokeWidth={1.3} /><h2>先看经历，再决定怎么写</h2><p>普通修改检查叙事、技术细节和亮点；定向修改会额外对照岗位要求，把最相关的经历放在前面。</p><ol><li>导入原始简历，按需加入 JD。</li><li>分析后补充职责、成果和项目细节。</li><li>生成正文，逐条查看改动，再编辑与导出。</li></ol><p className="resume-privacy">文件在本机提取文字；点击分析或生成时，材料才会发送到你配置的模型网关。</p></div> : <div className="resume-diagnosis">
          <h2>这份简历的主线</h2><p className="resume-positioning">{analysis.positioning}</p>
          <h3>{workspace.jd ? "岗位要求与经历证据" : "需要优先处理的内容"}</h3>
          <div className="resume-requirements">{analysis.requirements.map((row, i) => <article key={i}><div><strong>{row.requirement}</strong><span>{row.priority} · {row.status}</span></div>{row.evidence && <blockquote>{row.evidence}</blockquote>}<p>{row.advice}</p></article>)}</div>
          {analysis.strengths.length > 0 && <><h3>值得放大的优势</h3><ul className="resume-bullets">{analysis.strengths.map((item, i) => <li key={i}>{item}</li>)}</ul></>}
          {analysis.weaknesses.length > 0 && <><h3>取舍与调整</h3>{analysis.weaknesses.map((row, i) => <div className="resume-tradeoff" key={i}><strong>{row.issue}<span>{row.action}</span></strong><p>{row.reason}</p></div>)}</>}
          {analysis.questions.length > 0 && <div className="resume-questions"><h3>把这些细节补上，表达会更有分量</h3>{analysis.questions.map(q => <label key={q.id}>{q.question}<small>{q.why}</small><textarea value={answers[q.id] || ""} maxLength={1500} disabled={Boolean(busy)} onChange={e => setAnswers(current => ({ ...current, [q.id]: e.target.value }))} placeholder={q.example || "没有数据时，可以描述具体交付和验证方法。"} /></label>)}<Button icon={Plus} onClick={addAnswers} disabled={Boolean(busy) || !Object.values(answers).some(v => v.trim())}>将回答加入补充材料</Button></div>}
          {analysis.enhancements.length > 0 && <div className="resume-enhancements"><h3>增强设想</h3><p>这些是基于现有项目提出的工作稿建议，需要你确认前提和口径。</p>{analysis.enhancements.map((row, i) => <article key={i}><strong>{row.claim}</strong><dl><dt>成立前提</dt><dd>{row.assumptions}</dd><dt>验证方法</dt><dd>{row.verification}</dd><dt>面试追问</dt><dd>{row.followup}</dd></dl><Button icon={Plus} onClick={() => adopt(row)} disabled={Boolean(busy) || form.facts.includes(row.claim)}>采用到补充材料</Button></article>)}</div>}
          {analysis.outline.length > 0 && <div className="resume-outline"><h3>建议的信息顺序</h3><p>{analysis.outline.join(" → ")}</p></div>}
        </div>)}
        {tab !== "analysis" && (!selected ? <div className="resume-start"><h2>先分析，再生成改写稿</h2><p>分析完成后可以直接生成，也可以先补充关键细节。所有生成版本都会保留。</p></div> : <>
          <div className="resume-version-bar"><label>版本<select aria-label="简历版本" value={selected.id} disabled={Boolean(busy)} onChange={e => { if (!edited || window.confirm("放弃未保存的正文修改？")) chooseVersion(versions.find(v => v.id === e.target.value)); }}>{versions.map((v, i) => <option key={v.id} value={v.id}>{versions.length - i} · {v.report.manual_edit ? "手动编辑" : "模型改写"} · {dateLabel(v.created_at)}</option>)}</select></label><span>{selected.model}</span></div>
          {tab === "resume" ? <>
            <div className="resume-export-bar"><button className="text-button" onClick={() => setEdit(!edit)}>{edit ? "预览正文" : "编辑正文"}</button>{edited && <Button icon={Save} disabled={Boolean(busy) || !markdown.trim()} onClick={saveEdits}>保存新版本</Button>}<button className="text-button" disabled={Boolean(busy) || markdown.length > 30000} onClick={() => { if (window.confirm("用当前正文替换左侧原始简历？已有版本保留，之后请重新分析。")) { update("original", markdown); setNotice("正文已作为新的原始材料，可保存或重新分析"); } }}>正文作为新材料</button><span className="resume-export-spacer" /><Button icon={Copy} disabled={Boolean(busy)} onClick={async () => { try { await navigator.clipboard.writeText(markdown); setNotice("简历正文已复制"); } catch { setError("复制失败，请使用 Markdown 导出。"); } }}>复制</Button><Button icon={Download} disabled={Boolean(busy)} onClick={() => exportVersion("markdown")}>Markdown</Button><Button icon={Download} disabled={Boolean(busy)} onClick={() => exportVersion("docx")}>Word</Button><Button icon={Printer} disabled={Boolean(busy)} onClick={() => exportVersion("print")}>打印 / PDF</Button></div>
            <ResumeFormatting busy={Boolean(busy)} layout={layout} onChange={setLayout} markdown={markdown} onMarkdown={setMarkdown} />
            <ResumeExpansion busy={Boolean(busy)} selection={selection} setSelection={value => { setSelection(value); setExpansion(null); }} result={expansion} onRun={expand} onAdopt={adoptExpansion} canReplace={Boolean(expansion && markdown.includes(expansion.original) && markdown.indexOf(expansion.original) === markdown.lastIndexOf(expansion.original))} />
            {edit ? <textarea className="resume-editor" aria-label="编辑简历正文" maxLength={40000} value={markdown} disabled={Boolean(busy)} onChange={e => setMarkdown(e.target.value)} onSelect={e => { const text = e.target.value.slice(e.target.selectionStart, e.target.selectionEnd); if (text && text.length <= 6000) { setSelection(text); setExpansion(null); } }} /> : <article className={`resume-paper markdown-body template-${layout.template} font-${layout.font} density-${layout.density} accent-${layout.accent}`} style={{ "--resume-size": `${layout.size}px` }}><ReactMarkdown remarkPlugins={[remarkGfm]}>{markdown}</ReactMarkdown></article>}
            <div className="resume-review-notes"><h3>投递前核对</h3><ul>{[...(selected.report.review_notes || []), ...(selected.report.checks || [])].map((item, i) => <li key={i}>{item}</li>)}</ul>{edited && <p>当前有未保存的正文修改；导出时会先保存为新版本。</p>}</div>
          </> : <div className="resume-change-report"><h2>为什么这样改</h2><p>{selected.report.strategy}</p>{selected.report.manual_edit && <p className="resume-stale">此版本含手动修改，下方对照保留生成时的说明，部分句子可能已被你调整。</p>}{selected.report.changes.map((row, i) => <article key={i}><div className="resume-comparison"><div><span>原表达</span><p>{row.original}</p></div><div><span>改写后</span><p>{row.revised}</p></div></div><p className="resume-change-reason">{row.reason}</p><details><summary>查看依据</summary><blockquote>{row.evidence}</blockquote></details></article>)}{selected.report.omissions?.length > 0 && <><h3>压缩与省略</h3>{selected.report.omissions.map((row, i) => <p key={i}><strong>{row.text}</strong>：{row.reason}</p>)}</>}{selected.report.missing_facts?.length > 0 && <><h3>仍可补充的材料</h3><ul>{selected.report.missing_facts.map((item, i) => <li key={i}>{item}</li>)}</ul></>}<details className="resume-original-snapshot"><summary>查看生成时的原始材料</summary><pre>{selected.inputs.original}</pre>{selected.inputs.jd && <><h3>目标 JD</h3><pre>{selected.inputs.jd}</pre></>}{selected.inputs.facts && <><h3>补充材料</h3><pre>{selected.inputs.facts}</pre></>}</details></div>}
        </>)}
      </section>
    </div>
  </div>;
}
