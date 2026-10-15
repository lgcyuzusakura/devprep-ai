import { useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ArrowUp, ArrowDown, Plus } from "lucide-react";
import { Button } from "../components/UI.jsx";
import { TEMPLATES, EXAMPLES, SECTIONS, SECTION_HINTS, blankResume, splitSections, moveSection } from "../lib/resumeTemplates.js";

export function ResumeStarter({ busy, onCreate }) {
  const [view, setView] = useState("write");
  const [text, setText] = useState(blankResume);
  const [exampleId, setExampleId] = useState("java");
  const example = EXAMPLES.find(e => e.id === exampleId);
  return <details className="resume-studio">
    <summary>从零编写简历 / 查看方向示例</summary>
    <div className="resume-studio-tabs"><button aria-pressed={view === "write"} onClick={() => setView("write")}>从零编写</button><button aria-pressed={view === "examples"} onClick={() => setView("examples")}>方向示例 · {EXAMPLES.length}</button></div>
    {view === "write" ? <div className="resume-builder">
      <div><h2>先把经历写下来</h2><p>没有旧简历也可以开始。直接填写右侧草稿，保留需要的章节；保存后可调整版式、顺序，或选一段交给 AI 扩写。</p><div className="resume-section-add">{SECTIONS.map(s => <button key={s} disabled={busy} onClick={() => setText(current => `${current.trimEnd()}\n\n## ${s}\n${SECTION_HINTS[s]}`)}><Plus size={12} />{s}</button>)}</div><Button tone="primary" disabled={busy || !text.trim()} onClick={() => onCreate(text, "自主编写简历")}>创建并继续编辑</Button></div>
      <label>简历草稿<textarea aria-label="从零编写简历" value={text} onChange={e => setText(e.target.value)} maxLength={30000} disabled={busy} /></label>
    </div> : <div className="resume-examples">
      <div className="resume-example-index">{EXAMPLES.map(item => <button key={item.id} aria-pressed={exampleId === item.id} onClick={() => setExampleId(item.id)}><strong>{item.direction}</strong><span>{item.title}</span></button>)}</div>
      <div className="resume-example-detail"><h2>{example.title}</h2><p className="resume-example-note">{example.note}</p><p className="resume-example-source">AI 辅助编写的原创虚构示例，数据仅用于说明写法，不是实际候选人的简历。</p><article className="markdown-body"><ReactMarkdown remarkPlugins={[remarkGfm]}>{example.markdown}</ReactMarkdown></article><Button disabled={busy} onClick={() => onCreate(example.markdown, `${example.direction} · 示例改写`)}>复制为独立工作稿</Button></div>
    </div>}
  </details>;
}

export function ResumeFormatting({ layout, onChange, markdown, onMarkdown, busy }) {
  const sections = splitSections(markdown).sections;
  const [sectionName, setSectionName] = useState("");
  return <details className="resume-formatting"><summary>版式与章节顺序</summary>
    <div className="resume-template-list">{TEMPLATES.map(item => <button disabled={busy} key={item.id} aria-pressed={layout.template === item.id} onClick={() => onChange({ ...item.layout })}><span className={`resume-template-thumb template-${item.id}`} aria-hidden="true"><i /><i /><i /><i /></span><strong>{item.name}</strong><small>{item.description}</small></button>)}</div>
    <div className="resume-format-controls"><label>正文字体<select disabled={busy} value={layout.font} onChange={e => onChange({ ...layout, font: e.target.value })}><option value="sans">黑体 / 无衬线</option><option value="serif">宋体 / 衬线</option></select></label><label>字号<select disabled={busy} value={layout.size} onChange={e => onChange({ ...layout, size: Number(e.target.value) })}>{[10,11,12,13,14,15].map(n => <option key={n} value={n}>{n}</option>)}</select></label><label>段落留白<select disabled={busy} value={layout.density} onChange={e => onChange({ ...layout, density: e.target.value })}><option value="normal">标准</option><option value="compact">紧凑</option><option value="relaxed">舒展</option></select></label><label>标题颜色<select disabled={busy} value={layout.accent} onChange={e => onChange({ ...layout, accent: e.target.value })}><option value="green">深绿</option><option value="blue">藏蓝</option><option value="black">黑色</option></select></label></div>
    <div className="resume-section-order"><h3>章节顺序</h3><p>按正文中的二级标题分节，可将教育背景、校园经历移到最后。</p>{sections.map((s, i) => <div key={`${i}-${s.title}`}><span>{s.title}</span><button aria-label={`上移${s.title}`} disabled={busy || i === 0} onClick={() => onMarkdown(moveSection(markdown, i, -1))}><ArrowUp size={14} /></button><button aria-label={`下移${s.title}`} disabled={busy || i === sections.length - 1} onClick={() => onMarkdown(moveSection(markdown, i, 1))}><ArrowDown size={14} /></button></div>)}<label className="resume-new-section">新增章节<input aria-label="新章节名称" maxLength={40} value={sectionName} disabled={busy} onChange={e => setSectionName(e.target.value)} /><Button disabled={busy || !sectionName.trim()} onClick={() => { onMarkdown(`${markdown.trimEnd()}\n\n## ${sectionName.replace(/[\r\n#]/g, "").trim()}\n填写内容。`); setSectionName(""); }}>添加</Button></label></div>
  </details>;
}

export function ResumeExpansion({ busy, selection, setSelection, result, onRun, onAdopt, canReplace }) {
  const [instruction, setInstruction] = useState("");
  return <details className="resume-expansion"><summary>AI 局部扩写</summary><p>粘贴一段经历，或在正文编辑器中选中文字。扩写会结合原始材料和 JD，先给你看结果。</p><label>待扩写内容<textarea aria-label="待扩写内容" maxLength={6000} disabled={busy} value={selection} onChange={e => setSelection(e.target.value)} /></label><label>扩写要求<input aria-label="扩写要求" maxLength={1500} disabled={busy} value={instruction} onChange={e => setInstruction(e.target.value)} placeholder="例如：突出缓存失效的技术取舍；保留课程项目的边界" /></label><Button disabled={busy || !selection.trim()} onClick={() => onRun(instruction)}>扩写这一段</Button>{result && <div className="resume-expansion-result"><h3>扩写建议</h3><article className="markdown-body"><ReactMarkdown remarkPlugins={[remarkGfm]}>{result.expanded}</ReactMarkdown></article><p>{result.reason}</p><ul>{result.checks.map((c, i) => <li key={i}>{c}</li>)}</ul><Button disabled={busy || !canReplace} onClick={() => onAdopt("replace")}>替换原段落</Button><Button disabled={busy} onClick={() => onAdopt("append")}>追加到正文</Button>{!canReplace && <small>原段落与正文不唯一对应时，可手动编辑或追加。</small>}</div>}</details>;
}
