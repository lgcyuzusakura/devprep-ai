import { workspaceStorage as localStorage } from "../lib/workspaceStorage.js";
import { cloneElement, useEffect, useRef, useState } from "react";
import { Plus, FileText, ExternalLink, Copy, Download, Save, ArrowLeft, Search, Upload, Trash2, Check, MessageSquare, Presentation } from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { api, post, navigate, dateLabel } from "../lib/api.js";
import { Button, Badge, ErrorNotice, PageHeading, EmptyState, Loading } from "../components/UI.jsx";
import "./jobs.css";

const EMPTY_JOB = { title: "", company: "", location: "", salary: "", url: "", jd: "", notes: "", preferences: "" };
const EMPTY_MASTER = { title: "通用简历", resume: "", facts: "", preferences: "", revision: -1 };
const STATUS = { saved: "已收藏", prepared: "材料就绪", contacted: "已沟通", submitted: "已投递", interview: "面试中", offer: "收到 Offer", rejected: "未通过", archived: "已归档" };
const MATERIAL_FIELDS = { markdown: "简历正文", greeting: "招聘聊天开场白", self_introduction: "岗位自我介绍", follow_up: "跟进草稿" };
const equalFields = (a, b, fields) => fields.every(key => (a?.[key] || "") === (b?.[key] || ""));
const safeUrl = value => { try { const u = new URL(value); return u.protocol === "https:" && !u.username && !u.password ? u.href : ""; } catch { return ""; } };
function readStorage(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; } }
function Field({ title, children, ...props }) { return <label className="jobs-field"><span>{title}</span>{children ? cloneElement(children, { "aria-label": title }) : <input aria-label={title} {...props} />}</label>; }

export default function JobSearch({ settings, params }) {
  const [jobs, setJobs] = useState([]), [sources, setSources] = useState([]), [profile, setProfile] = useState(EMPTY_MASTER), [master, setMaster] = useState(EMPTY_MASTER);
  const [job, setJob] = useState(null), [form, setForm] = useState(EMPTY_JOB), [materials, setMaterials] = useState([]), [versionId, setVersionId] = useState("");
  const [materialDraft, setMaterialDraft] = useState({}), [editing, setEditing] = useState(false), [events, setEvents] = useState([]), [attachments, setAttachments] = useState([]);
  const [view, setView] = useState("workspace"), [tab, setTab] = useState("jd"), [busy, setBusy] = useState(""), [error, setError] = useState(""), [notice, setNotice] = useState(""), [ready, setReady] = useState(false);
  const [query, setQuery] = useState(""), [filter, setFilter] = useState(""), [status, setStatus] = useState("prepared"), [statusNote, setStatusNote] = useState("");
  const [resumeList, setResumeList] = useState([]), [resumeId, setResumeId] = useState("");
  const [sourceUrl, setSourceUrl] = useState(""), [sourceName, setSourceName] = useState(""), [custom, setCustom] = useState(() => readStorage("devprep.jobs.sources", []));
  const [sourceRows, setSourceRows] = useState([]), [sourceQuery, setSourceQuery] = useState("");
  const masterFile = useRef(null), attachmentFile = useRef(null), initialized = useRef(false);
  const selected = materials.find(item => item.id === versionId);
  const dirty = !equalFields(form, job || EMPTY_JOB, Object.keys(EMPTY_JOB));
  const masterDirty = !equalFields(master, profile, ["title", "resume", "facts", "preferences"]);
  const materialDirty = selected && !equalFields(materialDraft, selected.content, Object.keys(MATERIAL_FIELDS));
  const hasChanges = dirty || masterDirty || materialDirty;

  useEffect(() => {
    if (initialized.current) return;
    initialized.current = true;
    (async () => {
      try {
        const data = await api("/jobs");setJobs(data.jobs);setSources(data.sources);setProfile(data.profile);
        const masterDraft = readStorage("devprep.jobs.master-draft", null);
        setMaster(masterDraft?.revision === data.profile.revision ? masterDraft : data.profile);
        const list = await api("/resumes");setResumeList(list.workspaces);
        const imported = params?.get("capture");
        if (imported) {
          // A browser helper transfers only an untrusted draft; nothing is saved or sent to a model automatically.
          window.history.replaceState(null, "", "#/jobs");
          const value = JSON.parse(imported);
          setForm({ ...EMPTY_JOB, title: String(value.title || "").slice(0, 160), url: safeUrl(value.url), jd: String(value.jd || "").slice(0, 20000) });
          setNotice("已读取浏览器草稿，请核对职位名称、公司和JD后保存。");
        } else {
          const cached = readStorage("devprep.jobs.draft", null);
          if (cached) {setForm({ ...EMPTY_JOB, ...cached });setNotice("已恢复尚未收藏的岗位草稿。");}
        }
      } catch (ex) {setError(ex.message);} finally {setReady(true);}
    })();
  }, [params]);
  useEffect(() => { const handler = event => { if (hasChanges) { event.preventDefault();event.returnValue = ""; } };window.addEventListener("beforeunload", handler);return () => window.removeEventListener("beforeunload", handler); }, [hasChanges]);
  useEffect(() => { if (!ready || job) return;try {if (dirty) localStorage.setItem("devprep.jobs.draft", JSON.stringify(form));else localStorage.removeItem("devprep.jobs.draft");} catch { /* Optional recovery. */ } }, [form, job, ready, dirty]);
  useEffect(() => { if (!ready) return;try {if (masterDirty) localStorage.setItem("devprep.jobs.master-draft", JSON.stringify(master));else localStorage.removeItem("devprep.jobs.master-draft");} catch { /* Optional recovery. */ } }, [master, masterDirty, ready]);
  useEffect(() => { if (!ready || !job) return;try {const key = `devprep.jobs.job-draft.${job.id}`;if (dirty) localStorage.setItem(key, JSON.stringify(form));else localStorage.removeItem(key);} catch { /* Optional recovery. */ } }, [form, job, dirty, ready]);
  useEffect(() => { if (!selected) return;try {const key = `devprep.jobs.material-draft.${selected.id}`;if (materialDirty) localStorage.setItem(key, JSON.stringify(materialDraft));else localStorage.removeItem(key);} catch { /* Optional recovery. */ } }, [materialDraft, selected, materialDirty]);
  useEffect(() => {
    setAttachments([]);
    if (!job || !versionId) return;
    let active = true;api(`/jobs/${job.id}/materials/${versionId}/attachments`).then(data => {if (active) setAttachments(data.attachments);}).catch(ex => {if (active) setError(ex.message);});
    return () => {active = false;};
  }, [job?.id, versionId]);

  async function run(label, action) { if (busy) return;setBusy(label);setError("");setNotice("");try {await action();} catch (ex) {setError(ex.message);} finally {setBusy("");} }
  async function load(id, preferred = "") {
    const data = await api(`/jobs/${id}`);setJob(data.job);
    const jobDraft = readStorage(`devprep.jobs.job-draft.${id}`, null);setForm(jobDraft?.revision === data.job.revision ? jobDraft : data.job);setMaterials(data.materials);setEvents(data.events);
    const item = data.materials.find(v => v.id === preferred) || data.materials[0];
    const draft = item && readStorage(`devprep.jobs.material-draft.${item.id}`, null);
    setVersionId(item?.id || "");setMaterialDraft(draft || item?.content || {});setEditing(!!draft);
    setJobs(items => [data.job, ...items.filter(j => j.id !== id)]);
  }
  const abandon = () => {
    if (!(dirty || materialDirty)) return true;
    if (!window.confirm("当前岗位或材料还有未保存修改，放弃这些修改？")) return false;
    try {localStorage.removeItem(job ? `devprep.jobs.job-draft.${job.id}` : "devprep.jobs.draft");if (selected) localStorage.removeItem(`devprep.jobs.material-draft.${selected.id}`);} catch { /* Optional recovery. */ }return true;
  };
  function selectJob(id) { if (!abandon()) return;run("读取岗位", async () => {await load(id);setView("workspace");setTab("materials");}); }
  function newJob() { if (!abandon()) return;setJob(null);setForm(EMPTY_JOB);setMaterials([]);setVersionId("");setMaterialDraft({});setEditing(false);setView("workspace");setTab("jd");setNotice("");setError(""); }
  async function saveJob() {
    const saved = await post(job ? `/jobs/${job.id}` : "/jobs", form);setJob(saved);setForm(saved);setJobs(items => [saved, ...items.filter(j => j.id !== saved.id)]);
    try {localStorage.removeItem("devprep.jobs.draft");} catch { /* Optional recovery. */ }return saved;
  }
  async function generate() {
    if (materialDirty && !window.confirm("材料修改尚未另存，继续生成新版本？")) return;
    await run("生成岗位材料", async () => {
      const saved = dirty || !job ? await saveJob() : job;
      const material = await post(`/jobs/${saved.id}/generate`, { revision: saved.revision, profile_revision: profile.revision });
      await load(saved.id, material.id);setTab("materials");setNotice("专属简历和话术已生成。核对后可复制、下载或手动投递。");
    });
  }
  async function saveMaterial() {
    const result = await post(`/jobs/${job.id}/materials/${versionId}`, { ...materialDraft, revision: job.revision });
    try {localStorage.removeItem(`devprep.jobs.material-draft.${versionId}`);} catch { /* Optional recovery. */ }
    await load(job.id, result.id);return result;
  }
  async function download(format) {
    await run("准备下载", async () => {
      const target = materialDirty ? await saveMaterial() : selected;
      const response = await fetch(`/api/jobs/${job.id}/materials/${target.id}/export/${format}`);
      if (!response.ok) {const data = await response.json();throw new Error(data.error?.message || "下载失败");}
      const blob = await response.blob();const url = URL.createObjectURL(blob);const a = document.createElement("a");a.href = url;
      const ext = { docx: "docx", md: "md", messages: "txt", zip: "zip" }[format];
      const snapshot = target.inputs.job;a.download = `${snapshot.company || "公司"}-${snapshot.title}-${target.id.slice(0, 8)}.${ext}`;document.body.append(a);a.click();a.remove();setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  }
  async function copy(text) {await run("复制", async () => {await navigator.clipboard.writeText(text);setNotice("已复制，可粘贴到招聘网站。");});}
  async function importFile(event, isAttachment = false) {
    const file = event.target.files?.[0];event.target.value = "";if (!file) return;
    await run("读取文件", async () => {
      if (file.size > 2_000_000) throw new Error("请选择2MB以内的文件。");
      const content = await new Promise((resolve, reject) => {const reader = new FileReader();reader.onload = () => resolve(String(reader.result).split(",")[1]);reader.onerror = () => reject(new Error("文件读取失败"));reader.readAsDataURL(file);});
      if (isAttachment) {
        const data = await post(`/jobs/${job.id}/materials/${versionId}/attachments`, { filename: file.name, content });setAttachments(data.attachments);setNotice("附件已绑定到当前材料版本，下载ZIP时会一起带上。");
      } else {
        const data = await post("/resumes/extract", { filename: file.name, content });setMaster(value => ({ ...value, resume: data.text }));
      }
    });
  }
  function chooseVersion(id) {if (materialDirty && !window.confirm("放弃尚未保存的材料修改？")) return;const item = materials.find(v => v.id === id);setVersionId(id);setMaterialDraft(item?.content || {});setEditing(false);}
  function interview() {
    if (dirty || materialDirty) {setError("请先保存岗位和材料修改，再准备模拟面试。");return;}
    const snapshot = selected.inputs.job;
    try {localStorage.setItem("devprep.interview.setup", JSON.stringify({ title: `${snapshot.company} ${snapshot.title}`.slice(0, 100), role: snapshot.title.slice(0, 100), jd: snapshot.jd.slice(0, 16000), resume: selected.content.markdown.slice(0, 30000), mode: "ai", level: "campus", difficulty: "auto", company_style: "general", intensity: "standard", language: /python/i.test(snapshot.title) ? "python" : /\bgo\b/i.test(snapshot.title) ? "go" : "java", feedback_mode: "at_end", question_count: 6, categories: ["knowledge", "algorithm", "project"] }));navigate("interview");} catch {setError("浏览器无法保存面试准备材料，请手动复制JD和简历。");}
  }
  const filtered = jobs.filter(j => (!filter || j.status === filter) && `${j.title} ${j.company} ${j.location} ${j.jd}`.toLowerCase().includes(query.toLowerCase()));
  const historical = selected && (selected.inputs.profile.revision !== profile.revision || !equalFields(selected.inputs.job, job, Object.keys(EMPTY_JOB)));
  const patch = (key, value) => setForm(current => ({ ...current, [key]: value }));
  if (!ready) return <Loading label="正在读取求职工作台" />;
  return <div className="jobs-page">
    <PageHeading title="求职工作台" description="一份通用简历，按岗位准备专属简历与沟通话术。" action={<div className="jobs-actions"><Button disabled={!!busy} onClick={() => setView(view === "master" ? "workspace" : "master")} icon={FileText}>通用简历{masterDirty ? " *" : ""}</Button><Button disabled={!!busy} onClick={() => setView(view === "sources" ? "workspace" : "sources")} icon={Search}>找岗位</Button></div>} />
    <ErrorNotice message={error} onDismiss={() => setError("")} />
    {notice && <div className="success-notice" role="status"><Check size={16} />{notice}</div>}
    {busy && <p className="jobs-progress" role="status">{busy}…{busy === "生成岗位材料" ? "模型按顺序处理请求，请保持页面打开。" : ""}</p>}
    {view === "master" ? <section className="jobs-master">
      <div className="jobs-section-title"><div><h2>通用简历母版</h2><p>维护完整经历和补充事实，每个岗位从这里生成一份独立工作稿。</p></div><Button onClick={() => setView("workspace")} icon={ArrowLeft}>返回岗位</Button></div>
      <fieldset disabled={!!busy}>
        <div className="jobs-import"><select aria-label="选择已有简历" value={resumeId} onChange={e => setResumeId(e.target.value)}><option value="">从简历工作台选择</option>{resumeList.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}</select><Button disabled={!resumeId} onClick={() => run("导入简历", async () => {if (master.resume && !window.confirm("用选中的简历替换母版编辑区？保存后才会生效。")) return;const data = await api(`/resumes/${resumeId}`);setMaster(value => ({ ...value, title: data.workspace.title, resume: data.versions[0]?.markdown || data.workspace.original, facts: data.workspace.facts, preferences: data.workspace.preferences }));})}>读取简历</Button><Button icon={Upload} onClick={() => masterFile.current.click()}>导入文件</Button><input ref={masterFile} type="file" accept=".pdf,.docx,.txt,.md" hidden onChange={importFile} /></div>
        <Field title="母版名称" value={master.title} maxLength={80} onChange={e => setMaster({ ...master, title: e.target.value })} />
        <Field title="通用简历正文"><textarea rows={18} value={master.resume} maxLength={40000} placeholder="粘贴完整简历，或导入PDF、Word、TXT、Markdown。保留联系方式、经历和项目细节。" onChange={e => setMaster({ ...master, resume: e.target.value })} /></Field>
        <div className="jobs-two-fields"><Field title="补充事实与测量口径"><textarea rows={6} maxLength={12000} value={master.facts} placeholder="本人负责的部分、项目环境、测试基线、可补充的交付成果等。" onChange={e => setMaster({ ...master, facts: e.target.value })} /></Field><Field title="通用写作偏好"><textarea rows={6} maxLength={2000} value={master.preferences} placeholder="例如：项目在前，教育背景在后；偏技术表达；压缩校园经历。" onChange={e => setMaster({ ...master, preferences: e.target.value })} /></Field></div>
        <div className="jobs-actions"><Button icon={Save} tone="primary" disabled={!master.resume.trim() || !master.title.trim()} onClick={() => run("保存通用简历", async () => {const saved = await post("/jobs/profile", master);setProfile(saved);setMaster(saved);setNotice("通用简历已保存；已有岗位材料仍保留原来的快照。");})}>保存通用简历</Button><span className="muted">{profile.revision < 0 ? "尚未保存" : `当前母版版本 ${profile.revision + 1}`}{masterDirty ? "，有未保存修改" : ""}</span></div>
      </fieldset>
    </section> : view === "sources" ? <section className="jobs-sources">
      <div className="jobs-section-title"><div><h2>招聘来源</h2><p>在网站找岗位，保存链接和JD。浏览器助手可读取当前页面的选中文字。</p></div><Button icon={ArrowLeft} onClick={() => setView("workspace")}>返回岗位</Button></div>
      <div className="jobs-source-links">{[...sources, ...custom].map((source, i) => <div className="jobs-source-link" key={`${source.url}-${i}`}><a href={safeUrl(source.url) || undefined} target="_blank" rel="noopener noreferrer"><strong>{source.name}</strong><ExternalLink size={14} /><span>{source.hint || source.url}</span></a>{i >= sources.length && <button aria-label={`移除${source.name}`} className="text-button" onClick={() => {const next = custom.filter((_, j) => j !== i - sources.length);setCustom(next);try {localStorage.setItem("devprep.jobs.sources", JSON.stringify(next));} catch { /* Optional storage. */ }}}>移除</button>}</div>)}</div>
      <fieldset disabled={!!busy} className="jobs-source-form"><Field title="自定义来源名称" value={sourceName} maxLength={80} onChange={e => setSourceName(e.target.value)} placeholder="公司官网 / 招聘网站" /><Field title="招聘来源链接" value={sourceUrl} maxLength={2048} onChange={e => {setSourceUrl(e.target.value);setSourceRows([]);}} placeholder="https://…" /><div className="jobs-actions"><Button onClick={() => {if (!sourceName.trim() || !safeUrl(sourceUrl)) {setError("请填写来源名称和有效的HTTPS链接。");return;}const next = [...custom, { name: sourceName.trim(), url: safeUrl(sourceUrl) }];setCustom(next);try {localStorage.setItem("devprep.jobs.sources", JSON.stringify(next));} catch { /* Optional storage. */ }setNotice("来源入口已保存到当前浏览器。");}}>保存入口</Button><Button onClick={() => run("读取公开岗位", async () => {const data = await post("/jobs/sources/preview", { url: sourceUrl });setSourceRows(data.jobs);if (!data.jobs.length) setNotice("这个公开招聘页当前没有可读取的岗位。");})}>预览公开岗位</Button><a href="https://github.com/lgcyuzusakura/devprep-ai/blob/main/docs/job-search.md#浏览器助手" target="_blank" rel="noreferrer" className="text-button">浏览器助手安装说明 <ExternalLink size={13} /></a></div></fieldset>
      <p className="muted jobs-source-note">公开列表支持 jobs.lever.co/公司、job-boards.greenhouse.io/公司、jobs.ashbyhq.com/公司；每次最多预览200条。其他网站请粘贴JD或使用浏览器助手。</p>
      {!!sourceRows.length && <><Field title={`筛选公开岗位（${sourceRows.length}）`} value={sourceQuery} onChange={e => setSourceQuery(e.target.value)} placeholder="岗位 / 地点关键词" /><div className="jobs-source-results">{sourceRows.filter(row => `${row.title} ${row.location} ${row.jd}`.toLowerCase().includes(sourceQuery.toLowerCase())).map((row, i) => {const exists = jobs.some(j => j.url === row.url);return <div key={`${row.url}-${i}`}><div><strong>{row.title}</strong><span>{row.company} · {row.location || "地点待确认"}</span></div><Button disabled={!!busy || exists} onClick={() => run("收藏公开岗位", async () => {const saved = await post("/jobs", row);setJobs(items => [saved, ...items]);setNotice(`已收藏：${saved.title}`);})}>{exists ? "已收藏" : "收藏"}</Button></div>;})}</div></>}
    </section> : <div className="jobs-layout">
      <aside className="jobs-list"><div className="jobs-list-top"><h2>我的岗位 <span>{jobs.length}</span></h2><Button icon={Plus} disabled={!!busy} onClick={newJob}>新岗位</Button></div><input aria-label="搜索已收藏岗位" placeholder="搜岗位、公司、地点" value={query} onChange={e => setQuery(e.target.value)} /><select aria-label="筛选投递状态" value={filter} onChange={e => setFilter(e.target.value)}><option value="">全部状态</option>{Object.entries(STATUS).map(([key, label]) => <option value={key} key={key}>{label}</option>)}</select><div className="jobs-list-items">{filtered.map(item => <button key={item.id} className={`jobs-list-item ${job?.id === item.id ? "selected" : ""}`} disabled={!!busy} onClick={() => selectJob(item.id)}><strong>{item.title}</strong><span>{item.company || "公司待补充"}{item.location ? ` / ${item.location}` : ""}</span><small>{STATUS[item.status]}{item.salary ? ` · ${item.salary}` : ""}</small></button>)}{!filtered.length && <p className="muted">{jobs.length ? "没有符合筛选的岗位。" : "先保存一个感兴趣的岗位。"}</p>}</div><div className="jobs-master-status"><FileText size={16} /><div><strong>{profile.resume ? profile.title : "尚无通用简历"}</strong><span>{masterDirty ? "母版有未保存修改" : profile.resume ? `母版版本 ${profile.revision + 1}` : "生成前先准备完整经历"}</span></div><button className="text-button" onClick={() => setView("master")}>编辑</button></div></aside>
      <section className="jobs-detail">
        <div className="jobs-detail-heading"><div><h2>{job ? job.title : "收藏新岗位"}</h2><p>{job ? `${job.company || "公司待补充"}${job.location ? ` / ${job.location}` : ""}` : "粘贴岗位JD，随后生成专属材料。"}</p></div>{job && <Badge>{STATUS[job.status]}</Badge>}</div>
        <div className="jobs-tabs" role="tablist" aria-label="岗位内容">{[["jd", "岗位与JD"], ["materials", `专属材料${materials.length ? ` (${materials.length})` : ""}`], ["timeline", "投递记录"]].map(([key, label]) => <button role="tab" aria-selected={tab === key} key={key} onClick={() => setTab(key)}>{label}{key === "jd" && dirty ? " *" : ""}</button>)}</div>
        {tab === "jd" && <fieldset className="jobs-jd" disabled={!!busy}>
          <div className="jobs-two-fields"><Field title="岗位名称" value={form.title} maxLength={160} onChange={e => patch("title", e.target.value)} placeholder="Java后端开发 / 实习" /><Field title="公司名称" value={form.company} maxLength={160} onChange={e => patch("company", e.target.value)} /></div>
          <div className="jobs-two-fields"><Field title="工作地点" value={form.location} maxLength={160} onChange={e => patch("location", e.target.value)} /><Field title="薪资范围" value={form.salary} maxLength={160} onChange={e => patch("salary", e.target.value)} placeholder="保留JD原文，留空也可以" /></div>
          <Field title="岗位原始链接" value={form.url} maxLength={2048} placeholder="https://…，可自行填写任意招聘网站的岗位链接" onChange={e => patch("url", e.target.value)} />
          <Field title="岗位JD"><textarea rows={15} value={form.jd} maxLength={20000} onChange={e => patch("jd", e.target.value)} placeholder="粘贴工作职责、任职要求、加分项与业务背景。只填链接不足以生成定向材料。" /></Field>
          <div className="jobs-two-fields"><Field title="本岗位写作要求"><textarea rows={4} value={form.preferences} maxLength={2000} onChange={e => patch("preferences", e.target.value)} placeholder="例如：突出缓存设计，项目优先，偏应届生的表达。" /></Field><Field title="求职笔记"><textarea rows={4} value={form.notes} maxLength={4000} onChange={e => patch("notes", e.target.value)} placeholder="来源、内推人、截止日期、你对岗位的考虑等。" /></Field></div>
          <div className="jobs-actions"><Button icon={Save} disabled={!form.title.trim()} onClick={() => run("保存岗位", async () => {await saveJob();setNotice("岗位已保存。");})}>保存岗位</Button><Button tone="primary" disabled={!form.title.trim() || !form.jd.trim() || !profile.resume || masterDirty || !settings?.model} onClick={generate}>生成专属简历与话术</Button>{safeUrl(form.url) && <a className="button" target="_blank" rel="noopener noreferrer" href={safeUrl(form.url)}>打开岗位 <ExternalLink size={14} /></a>}</div>
          {(!profile.resume || masterDirty || !settings?.model) && <p className="jobs-help">{!profile.resume || masterDirty ? "请先保存通用简历母版。" : "先在模型设置中配置模型，岗位收藏和手动编辑可照常使用。"}</p>}
        </fieldset>}
        {tab === "materials" && (selected ? <div className="jobs-materials">
          <div className="jobs-version-bar"><Field title="材料版本"><select value={versionId} onChange={e => chooseVersion(e.target.value)} disabled={!!busy}>{materials.map((item, i) => <option key={item.id} value={item.id}>版本 {materials.length - i} · {item.content.manual_edit ? "手动修改" : "AI生成"} · {dateLabel(item.created_at)}</option>)}</select></Field><Button disabled={!!busy || masterDirty || !profile.resume || !settings?.model} onClick={generate}>重新生成一版</Button></div>
          <div className="jobs-version-context"><strong>{selected.inputs.job.company || "公司待补充"} / {selected.inputs.job.title}</strong><span>母版版本 {selected.inputs.profile.revision + 1} · {selected.model} · {selected.id.slice(0, 8)}</span>{historical && <p>当前JD或母版已经更新。这份材料仍使用生成时的快照，可重新生成新版本。</p>}{dirty && <p>岗位有未保存修改，先保存再重新生成。</p>}</div>
          <div className="jobs-actions jobs-export"><Button icon={editing ? Save : FileText} disabled={!!busy} onClick={() => editing ? run("保存材料", async () => {await saveMaterial();setNotice("修改已另存为新版本。");}) : setEditing(true)}>{editing ? "另存修改版" : "编辑简历和话术"}</Button><Button icon={Download} disabled={!!busy} onClick={() => download("zip")}>下载整套材料</Button><Button disabled={!!busy} onClick={() => download("docx")}>Word</Button><Button disabled={!!busy} onClick={() => download("md")}>Markdown</Button><Button disabled={!!busy} onClick={() => download("messages")}>话术文本</Button><Button icon={Presentation} disabled={!!busy} onClick={interview}>准备模拟面试</Button></div>
          {materialDirty && <p className="jobs-help">下载会先将当前修改另存为新版本。新版本的附件请单独添加，避免沿用旧文件。</p>}
          <div className="jobs-output"><div className="jobs-resume-output"><div className="jobs-output-title"><h3>岗位专属简历</h3><button className="text-button" disabled={!!busy} onClick={() => copy(materialDraft.markdown)}><Copy size={13} />复制正文</button></div>{editing ? <label className="jobs-field"><span className="sr-only">编辑岗位简历</span><textarea aria-label="编辑岗位简历" rows={30} value={materialDraft.markdown} onChange={e => setMaterialDraft({ ...materialDraft, markdown: e.target.value })} maxLength={40000} disabled={!!busy} /></label> : <article className="jobs-paper"><ReactMarkdown remarkPlugins={[remarkGfm]} components={{ img: ({ alt }) => <span>{alt}</span>, a: ({ children }) => <span>{children}</span> }}>{materialDraft.markdown}</ReactMarkdown></article>}</div>
          <div className="jobs-messages">{Object.entries(MATERIAL_FIELDS).filter(([key]) => key !== "markdown").map(([key, label]) => <section key={key}><div className="jobs-output-title"><h3>{label}</h3><button className="text-button" disabled={!!busy} onClick={() => copy(materialDraft[key])}><Copy size={13} />复制</button></div>{editing ? <textarea aria-label={`编辑${label}`} rows={6} maxLength={2500} value={materialDraft[key]} onChange={e => setMaterialDraft({ ...materialDraft, [key]: e.target.value })} disabled={!!busy} /> : <p>{materialDraft[key]}</p>}{key === "follow_up" && <small>按实际进展调整后再发送。</small>}</section>)}</div></div>
          <details className="jobs-analysis"><summary>岗位匹配依据与修改取舍</summary><p>{selected.content.strategy}</p><div className="jobs-requirements">{selected.content.requirements.map((item, i) => <section key={i}><div><strong>{item.requirement}</strong><Badge tone={item.status === "有证据" ? "success" : "warning"}>{item.status}</Badge></div>{item.evidence && <blockquote>{item.evidence}</blockquote>}<p>{item.advice}</p></section>)}</div>{selected.content.changes.map((item, i) => <div className="jobs-change" key={i}><blockquote>{item.evidence}</blockquote><p>{item.revised}</p><small>{item.reason}</small></div>)}<h3>待补充与核对</h3><ul>{[...selected.content.gaps, ...selected.content.checks].map((item, i) => <li key={i}>{item}</li>)}</ul>{selected.content.manual_edit && <p className="muted">此版本经过手动修改，以上分析与引用来自AI生成时，请重新核对修改后的正文。</p>}</details>
          <div className="jobs-attachments"><div className="jobs-output-title"><div><h3>本版本附件</h3><p className="muted">作品说明、补充材料等；只随当前版本的ZIP下载。</p></div><Button icon={Upload} disabled={!!busy || attachments.length >= 5 || materialDirty} onClick={() => attachmentFile.current.click()}>添加附件</Button><input type="file" hidden ref={attachmentFile} accept=".pdf,.docx,.txt,.md" onChange={e => importFile(e, true)} /></div>{attachments.map(item => <div key={item.id}><span>{item.filename}</span><button className="text-button" disabled={!!busy} onClick={() => run("移除附件", async () => {const data = await post(`/jobs/${job.id}/materials/${versionId}/attachments/${item.id}/delete`, {});setAttachments(data.attachments);})}>移除</button></div>)}</div>
          <div className="jobs-manual-next"><div><strong>材料在手，就可以手动投递</strong><p>下载或复制以上内容，到岗位页面核对后使用；完成后在“投递记录”登记实际进展。</p></div>{safeUrl(selected.inputs.job.url) && <a className="button button-primary" target="_blank" rel="noopener noreferrer" href={safeUrl(selected.inputs.job.url)}>打开此版本岗位 <ExternalLink size={14} /></a>}</div>
        </div> : <EmptyState icon={MessageSquare} title="保存JD与通用简历后，生成这个岗位专属的简历和话术。" action={<Button onClick={() => setTab("jd")}>填写岗位与JD</Button>} />)}
        {tab === "timeline" && <div className="jobs-timeline">{job ? <><h3>登记实际进展</h3><p className="muted">打开链接、复制话术或下载材料不会自动记为已投递。</p><fieldset disabled={!!busy}><div className="jobs-two-fields"><Field title="实际进展"><select value={status} onChange={e => setStatus(e.target.value)}>{Object.entries(STATUS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></Field><Field title="本次使用的材料版本"><select value={versionId} onChange={e => chooseVersion(e.target.value)}><option value="">未使用材料</option>{materials.map((item, i) => <option key={item.id} value={item.id}>版本 {materials.length - i} / {item.id.slice(0, 8)}</option>)}</select></Field></div><Field title="进展备注"><textarea rows={3} maxLength={1500} value={statusNote} onChange={e => setStatusNote(e.target.value)} placeholder="例如：在官网投递，收到确认邮件；下周安排技术面。" /></Field><Button tone="primary" disabled={dirty || materialDirty} onClick={() => run("记录进展", async () => {await post(`/jobs/${job.id}/status`, { revision: job.revision, status, material_id: versionId, note: statusNote });await load(job.id, versionId);setStatusNote("");setNotice("实际进展已记录。");})}>记录进展</Button></fieldset><ol>{events.map((event, i) => <li key={i}><div><strong>{STATUS[event.status]}</strong><time>{dateLabel(event.created_at)}</time></div><p>{event.note || "更新进展"}</p>{event.material_id && <button className="text-button" onClick={() => {chooseVersion(event.material_id);setTab("materials");}}>查看材料 {event.material_id.slice(0, 8)}</button>}</li>)}</ol><Button icon={Trash2} disabled={!!busy} onClick={() => {if (!window.confirm("删除此岗位及全部材料、附件和投递记录？")) return;run("删除岗位", async () => {await post(`/jobs/${job.id}/delete`, { revision: job.revision });setJobs(items => items.filter(item => item.id !== job.id));setJob(null);setForm(EMPTY_JOB);setMaterials([]);setVersionId("");setTab("jd");});}}>删除岗位及材料</Button></> : <EmptyState title="先收藏一个岗位，再记录求职进展。" />}</div>}
      </section>
    </div>}
  </div>;
}
