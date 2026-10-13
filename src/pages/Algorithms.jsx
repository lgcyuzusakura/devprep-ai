import { useEffect, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { java } from "@codemirror/lang-java";
import { python } from "@codemirror/lang-python";
import { javascript } from "@codemirror/lang-javascript";
import { go } from "@codemirror/lang-go";
import ReactMarkdown from "react-markdown";
import { Check, Play, Upload, RotateCcw, MessageSquare, Search } from "lucide-react";
import { api, post, navigate } from "../lib/api.js";
import { Badge, Button, ErrorNotice, Loading } from "../components/UI.jsx";

const languages = { java: { label: "Java 17", extension: java }, go: { label: "Go 1.24", extension: go }, python: { label: "Python 3.12", extension: python }, javascript: { label: "JavaScript / Node.js 22", extension: javascript } };
const statusNames = { accepted: "通过", wrong_answer: "答案不正确", runtime_error: "运行错误", compile_error: "编译错误", timeout: "运行超时", output_limit: "输出超过限制", runner_error: "运行服务不可用" };
const key = (id, language) => `devprep.algorithm.${id}.${language}`;

export default function Algorithms({ params }) {
  const [problems, setProblems] = useState([]);
  const [collections, setCollections] = useState([]);
  const [problem, setProblem] = useState(null);
  const [language, setLanguage] = useState(() => { try { const saved = localStorage.getItem("devprep.algorithm.language"); return languages[saved] ? saved : "java"; } catch { return "java"; } });
  const [code, setCode] = useState("");
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState("");
  const [collection, setCollection] = useState("hot100");
  const [tab, setTab] = useState("description");
  const [editorial, setEditorial] = useState(null);
  const [history, setHistory] = useState([]);
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const id = params.get("problem") || "lc-001-two-sum";
  const loadList = () => api("/algorithms").then(data => { setProblems(data.problems); setCollections(data.collections || []); });
  const filtered = problems.filter(p => (!collection || (collection === "local" ? !p.official : p.collections?.includes(collection))) && (!filter || p.tag === filter) && (p.title.includes(query) || String(p.number).includes(query)));
  const runnable = problem && (!problem.official || problem.runnable_languages?.includes(language));
  useEffect(() => { loadList().catch(err => setError(err.message)); }, []);
  useEffect(() => {
    let cancelled = false; setProblem(null); setResult(null); setEditorial(null); setTab("description"); setError("");
    (async () => { const data = await api(`/algorithms/${id}`); if (cancelled) return; setProblem(data); const records = await api(`/algorithms/${id}/submissions`); if (!cancelled) setHistory(records.submissions); })().catch(err => { if (!cancelled) setError(err.message); });
    return () => { cancelled = true; };
  }, [id]);
  useEffect(() => { if (!problem) return; try { setCode(localStorage.getItem(key(problem.id, language)) ?? problem.templates[language]); localStorage.setItem("devprep.algorithm.language", language); } catch { setCode(problem.templates[language]); } }, [problem, language]);
  function edit(value) { setCode(value); try { localStorage.setItem(key(id, language), value); } catch { /* Editing stays available without storage. */ } }
  async function judge(submit) {
    if (busy) return; setBusy(true); setError(""); setResult(null);
    try { const data = await post(`/algorithms/${id}/${submit ? "submit" : "run"}`, { language, code }); setResult(data); if (submit) { const records = await api(`/algorithms/${id}/submissions`); setHistory(records.submissions); await loadList(); } }
    catch (err) { setError(err.message); } finally { setBusy(false); }
  }
  async function changeTab(value) { setTab(value); if (value === "editorial" && !editorial) { try { setEditorial(await api(`/algorithms/${id}/editorial`)); } catch (err) { setError(err.message); } } }
  return <>
    <div className="algorithm-heading"><div><h1>算法练习</h1><p>Hot 100 · 面试经典 · 编程基础 · 算法专项</p></div><span className="muted">{problems.filter(p => p.solved).length} / {problems.length} 本地测试已通过</span></div>
    <ErrorNotice message={error} onDismiss={() => setError("")} />
    <div className="algorithm-workbench">
      <aside className="algorithm-list"><div className="algorithm-filters"><select aria-label="算法题单" value={collection} onChange={e => setCollection(e.target.value)}>{collections.map(item => <option key={item.id} value={item.id}>{item.title}（{item.count}）</option>)}<option value="local">本地边界测试练习（{problems.filter(p => !p.official).length}）</option><option value="">全部题目（{problems.length}）</option></select><div className="search-input"><Search size={15} /><input placeholder="搜索题目" aria-label="搜索算法题" value={query} onChange={e => setQuery(e.target.value)} /></div><select aria-label="算法分类" value={filter} onChange={e => setFilter(e.target.value)}><option value="">全部分类</option>{[...new Set(problems.map(p => p.tag))].map(tag => <option key={tag}>{tag}</option>)}</select><small className="muted" role="status">当前筛选：{filtered.length} 道题</small></div><div>{filtered.map((item, i) => <button key={item.id} className={`algorithm-list-row ${id === item.id ? "selected" : ""}`} disabled={busy} onClick={() => navigate("algorithms", { problem: item.id })}><span className="algorithm-number">{item.solved ? <Check size={14} /> : item.number || String(i + 1).padStart(2, "0")}</span><span><strong>{item.title}</strong><small>{item.tag} · {item.difficulty}</small></span></button>)}{!filtered.length && <p className="muted">没有符合筛选条件的题目。</p>}</div></aside>
      {!problem ? <Loading label="正在读取算法题" /> : <>
        <section className="algorithm-description"><header><h2>{problem.number ? `${problem.number}. ` : ""}{problem.title}</h2><Badge tone={problem.difficulty === "简单" ? "success" : "warning"}>{problem.difficulty}</Badge><span className="muted">{problem.tag}</span></header><div className="workbench-tabs">{[["description", "题目"], ["editorial", "解题思路"], ["history", "提交记录"]].map(([value, title]) => <button key={value} className={tab === value ? "active" : ""} onClick={() => changeTab(value)}>{title}</button>)}</div><div className="problem-content">{problem.source_url && <a className="leetcode-original" href={problem.source_url} target="_blank" rel="noreferrer">打开 LeetCode 原题与官方判题 ↗</a>}
          {tab === "description" && <><div className="markdown-body"><ReactMarkdown>{problem.description}</ReactMarkdown></div>{problem.examples.map((example, i) => <div className="algorithm-example" key={i}><h3>示例 {i + 1}</h3><p>输入</p><pre>{JSON.stringify(example.input)}</pre><p>输出</p><pre>{JSON.stringify(example.expected)}</pre><span>{example.explanation}</span></div>)}<details className="algorithm-hints"><summary>查看分级提示</summary>{problem.hints.map((hint, i) => <p key={i}>{i + 1}. {hint}</p>)}</details><p className="algorithm-source">{problem.source}</p></>}
          {tab === "editorial" && (editorial ? <div className="markdown-body"><ReactMarkdown>{editorial.explanation}</ReactMarkdown><p className="algorithm-source">{editorial.source}</p></div> : <Loading />)}
          {tab === "history" && (history.length ? history.map(item => <div className="submission-row" key={item.id}><Badge tone={item.result.status === "accepted" ? "success" : "warning"}>{statusNames[item.result.status]}</Badge><span>{languages[item.language]?.label}<small>{new Date(item.created_at).toLocaleString("zh-CN")}</small></span><Button disabled={busy} onClick={() => { setLanguage(item.language); try { localStorage.setItem(key(id, item.language), item.code); } catch { } setCode(item.code); setResult(item.result); }}>恢复代码</Button></div>) : <p className="muted">提交完整测试后，结果与代码会保存在这里。</p>)}
        </div><button className="algorithm-ask" onClick={() => navigate("questions", { q: `我正在练习「${problem.title}」，题目ID是 ${problem.id}。请用${languages[language]?.label}给我一个提示，暂时不要给完整答案。` })}><MessageSquare size={15} />向学习教练要个提示</button></section>
        <section className="algorithm-editor"><header><strong>代码</strong><select aria-label="编程语言" value={language} disabled={busy} onChange={e => setLanguage(e.target.value)}>{Object.entries(languages).map(([value, item]) => <option key={value} value={value}>{item.label}</option>)}</select><Button icon={RotateCcw} disabled={busy} onClick={() => { if (window.confirm("恢复当前语言模板？已有草稿会被替换。")) edit(problem.templates[language]); }}>重置</Button></header><div className="language-note">{problem.language_notes[language]}</div><div className="code-editor-area"><CodeMirror aria-label="算法代码编辑器" value={code} height="100%" minHeight="340px" extensions={[languages[language].extension()]} onChange={edit} editable={!busy} basicSetup={{ lineNumbers: true, foldGutter: true, autocompletion: true }} /></div><div className="algorithm-actions"><span>{problem.official ? "公开样例验证" : "单例 3 秒 · 512 MB"}</span><Button icon={Play} busy={busy} disabled={!runnable} onClick={() => judge(false)}>运行样例</Button><Button icon={Upload} tone="primary" busy={busy} disabled={!runnable} onClick={() => judge(true)}>{problem.official ? "保存并验证" : "提交"}</Button></div><div className="judge-panel" aria-live="polite">{busy ? <Loading label="正在隔离容器中编译和运行" /> : result ? <><div className="judge-heading"><Badge tone={result.status === "accepted" ? "success" : "warning"}>{statusNames[result.status] || result.status}</Badge><strong>{result.passed} / {result.total} 测试通过</strong><span>{result.judge_scope === "examples" ? "仅验证公开样例" : result.submitted ? "本地完整测试" : "公开样例"}</span></div>{result.compiler_output && <pre className="compiler-output">{result.compiler_output}</pre>}<div className="judge-cases">{result.cases.map(row => <details key={row.index}><summary><Badge tone={row.passed ? "success" : "warning"}>测试 {row.index}</Badge>{row.hidden ? "边界测试" : "公开样例"} · {statusNames[row.status] || row.status}<span>{row.duration_ms} ms</span></summary>{row.hidden ? <p>边界测试只展示判定结果。</p> : <><pre>输入：{JSON.stringify(row.input)}{"\n"}预期：{JSON.stringify(row.expected)}{"\n"}实际：{JSON.stringify(row.actual)}</pre>{row.stderr && <pre>{row.stderr}</pre>}</>}</details>)}</div></> : <p className="judge-placeholder">{problem.official ? (runnable ? "运行官方公开样例。最终正确性请使用 LeetCode 官方判题验证。" : "这道题包含暂未适配的复杂接口，请通过原题入口运行与提交；代码草稿会保留。") : "运行后在这里查看测试结果。输入从标准输入读取一个 JSON 对象，输出一个 JSON 值。"}</p>}</div></section>
      </>}
    </div>
  </>;
}
