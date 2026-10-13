import { useEffect, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import "katex/dist/katex.min.css";
import { ArrowRight, ArrowLeft, BookOpen, Check, CheckCircle2, Eye, ExternalLink, ListChecks } from "lucide-react";
import { api, post, navigate, dateLabel } from "../lib/api.js";
import { Badge, Button, ErrorNotice, Loading, PageHeading } from "../components/UI.jsx";
import { hasSourceFormatting, isMarkdownContent, remarkSourceTables, sourceMarkdown, sourcePlainText } from "../lib/sourceContent.js";

function safeSourceLink(value, base) {
  if (typeof value !== "string" || !value.trim()) return "";
  try {
    const url = new URL(value, base || undefined);
    return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password ? url.href : "";
  } catch { return ""; }
}

function localeLabel(value) {
  if (typeof value !== "string" || !value.trim()) return "";
  const locale = value.trim().toLowerCase();
  if (locale === "en" || locale.startsWith("en-")) return "英文";
  if (locale === "zh" || locale.startsWith("zh-")) return "中文";
  return value;
}

function PracticeContent({ value, format, sourceUrl, className = "" }) {
  const text = typeof value === "string" ? value : String(value ?? "");
  if (!isMarkdownContent(format)) return <div className={"practice-plain " + className}>{text}</div>;
  const base = safeSourceLink(sourceUrl);
  const tables = [];
  const markdown = sourceMarkdown(text, tables);
  return <div className={"markdown-body practice-markdown " + className}>
    <ReactMarkdown skipHtml remarkPlugins={[remarkGfm, remarkMath, [remarkSourceTables, { tables }]]}
      rehypePlugins={[[rehypeKatex, { trust: false, throwOnError: false, strict: "ignore", maxExpand: 1000, maxSize: 20 }]]}
      urlTransform={(url) => safeSourceLink(url, base)} components={{
      a: ({ href, children }) => href ? <a href={href} target="_blank" rel="noopener noreferrer">{children}</a> : <span>{children}</span>,
      img: ({ src, alt }) => <span className="practice-image-source"><span>图示：{alt || "原文图片"}</span>{src && <a href={src} target="_blank" rel="noopener noreferrer">查看原图 <ExternalLink size={12} /></a>}</span>,
      table: ({ children }) => <div className="practice-table-scroll"><table>{children}</table></div>,
    }}>{markdown}</ReactMarkdown>
  </div>;
}

function QuestionSource({ question }) {
  const url = safeSourceLink(question.source_url);
  return <div className="question-source">
    <div className="context-label">题目来源</div>
    <Badge>{question.source_type || "本地题库"}</Badge>
    {question.source_name && <strong className="source-name">{question.source_name}</strong>}
    {url && <a className="source-link" href={url} target="_blank" rel="noopener noreferrer">查看题目原文 <ExternalLink size={13} /></a>}
    {(question.source_path || question.source_license || question.license || question.source_attribution || question.attribution || question.source_locale || question.locale || question.content_locale) && <dl className="source-details">
      {question.source_path && <><dt>原文文件</dt><dd>{question.source_path}</dd></>}
      {(question.source_license || question.license) && <><dt>许可证</dt><dd>{question.source_license || question.license}</dd></>}
      {(question.source_attribution || question.attribution) && <><dt>署名</dt><dd>{question.source_attribution || question.attribution}</dd></>}
      {(question.source_locale || question.locale) && <><dt>原文语言</dt><dd>{localeLabel(question.source_locale || question.locale)}</dd></>}
      {question.content_locale && <><dt>题目语言</dt><dd>{localeLabel(question.content_locale)}</dd></>}
    </dl>}
    {(question.source_revision || question.revision || question.source_sha256) && <details className="source-version"><summary>版本与校验信息</summary><dl className="source-details">
      {(question.source_revision || question.revision) && <><dt>来源版本</dt><dd><code>{question.source_revision || question.revision}</code></dd></>}
      {question.source_sha256 && <><dt>SHA-256</dt><dd><code>{question.source_sha256}</code></dd></>}
    </dl></details>}
  </div>;
}

export default function Practice({ params, target, changed }) {
  const point = params.get("topic") || "";
  const [question, setQuestion] = useState(null);
  const [answer, setAnswer] = useState("");
  const [reference, setReference] = useState(null);
  const [hits, setHits] = useState([]);
  const [selfAssessment, setSelfAssessment] = useState(null);
  const [feedback, setFeedback] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [completed, setCompleted] = useState(0);
  const referenceMode = (feedback?.assessment_mode || reference?.assessment_mode || question?.assessment_mode) === "reference";
  const feedbackPassed = referenceMode ? (feedback?.self_assessment ?? selfAssessment) === true : feedback?.correct;

  function load() {
    setBusy(true); setError("");
    api("/practice/next" + (point ? "?knowledge_point_id=" + encodeURIComponent(point) : "")).then((data) => {
      setQuestion(data); setAnswer(""); setFeedback(null); setReference(null); setHits([]); setSelfAssessment(null);
    }).catch((err) => setError(err.message)).finally(() => setBusy(false));
  }
  useEffect(load, [point]);

  async function reveal() {
    if (busy) return;
    setBusy(true); setError("");
    try {
      const data = await api("/practice/reference?question_id=" + encodeURIComponent(question.id));
      setReference(data); setHits((data.rubric || []).map(() => false)); setSelfAssessment(null);
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }

  async function submit() {
    if (busy || feedback || !answer.trim() || (referenceMode && selfAssessment === null)) return;
    setBusy(true); setError("");
    try {
      const payload = { question_id: question.id, answer, rubric_hits: referenceMode ? [] : hits };
      if (referenceMode) payload.self_assessment = selfAssessment;
      const data = await post("/attempts", payload);
      setFeedback(data); setCompleted((value) => value + 1); changed();
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }

  function editAnswer() {
    setReference(null); setHits([]); setSelfAssessment(null);
  }

  return <>
    <PageHeading title={point ? "知识点练习" : "练习"} description={"本轮已完成 " + completed + " 题"} action={<Button icon={ArrowLeft} onClick={() => navigate("today")}>返回复习</Button>} />
    <ErrorNotice message={error} retry={!question ? load : undefined} />
    {!question ? (!error && <Loading />) : <div className="practice-layout">
      <section className="question-surface" aria-busy={busy}>
        <div className="question-topline"><span>{question.domain}</span><Badge>{question.question_type === "single_choice" ? "单选题" : referenceMode ? "问答题" : "简答题"}</Badge></div>
        {isMarkdownContent(question.content_format) && hasSourceFormatting(question.stem) ? <><h2 className="sr-only">{sourcePlainText(question.stem) || "题目"}</h2><PracticeContent className="question-stem" value={question.stem} format={question.content_format} sourceUrl={question.source_url} /></> : <h2 className="question-stem">{isMarkdownContent(question.content_format) ? sourcePlainText(question.stem) : question.stem}</h2>}
        <div className="question-topic"><BookOpen size={14} />{question.topic_title}</div>
        {question.question_type === "single_choice" ? <fieldset className="answer-options" disabled={!!feedback || busy}>
          <legend className="sr-only">选择答案</legend>
          {question.options.map((option, index) => <label key={option} className={["answer-option", answer === option && "chosen", feedback && option === feedback.answer && "correct-option", feedback && answer === option && !feedback.correct && "wrong-option"].filter(Boolean).join(" ")}>
            <input type="radio" name="answer" aria-label={String.fromCharCode(65 + index) + " " + sourcePlainText(option)} value={option} checked={answer === option} onChange={() => setAnswer(option)} /><span className="option-letter">{String.fromCharCode(65 + index)}</span><PracticeContent className="option-content" value={option} format={question.content_format} sourceUrl={question.source_url} />{feedback && option === feedback.answer && <Check size={17} />}
          </label>)}
        </fieldset> : <>
          <label className="field-label" htmlFor="written-answer">你的回答</label>
          <textarea id="written-answer" className="written-answer" placeholder="先写下你记得的内容，再对照参考答案" value={answer} disabled={!!reference || !!feedback || busy} maxLength={8000} onChange={(event) => setAnswer(event.target.value)} />
          <div className="textarea-meta">{answer.length} / 8000</div>
        </>}
        {reference && !feedback && <div className="reference-panel">
          <h3>参考答案</h3><PracticeContent value={reference.answer} format={reference.content_format || question.content_format} sourceUrl={question.source_url} />
          {referenceMode ? <fieldset className="self-assessment" disabled={busy}>
            <legend>对照参考答案，你掌握了吗？</legend>
            <p className="assessment-hint">判断自己的解释是否清楚、是否遗漏关键内容，再记录本次掌握情况。</p>
            <div className="assessment-options">
              <label className={"assessment-option" + (selfAssessment === true ? " selected" : "")}><input type="radio" name="self-assessment" checked={selfAssessment === true} onChange={() => setSelfAssessment(true)} /><span><strong>掌握</strong><small>能够独立解释主要内容</small></span></label>
              <label className={"assessment-option" + (selfAssessment === false ? " selected" : "")}><input type="radio" name="self-assessment" checked={selfAssessment === false} onChange={() => setSelfAssessment(false)} /><span><strong>需复习</strong><small>仍有遗漏，安排再次练习</small></span></label>
            </div>
          </fieldset> : <><h3>回答要点</h3><div className="rubric-list">{(reference.rubric || []).map((item, index) => <label key={item}><input type="checkbox" checked={hits[index]} disabled={busy} onChange={(event) => setHits((previous) => previous.map((hit, i) => i === index ? event.target.checked : hit))} /><span>{item}</span></label>)}</div></>}
        </div>}
        {feedback && <div className={"feedback-panel " + (feedbackPassed ? "feedback-success" : "feedback-review")} role="status">
          <div className="feedback-heading"><CheckCircle2 size={20} /><strong>{referenceMode ? feedbackPassed ? "自评：已掌握" : "自评：需复习" : feedback.correct ? "回答正确" : "还需要巩固"}</strong><span>{referenceMode ? "对照答案自评" : Math.round(feedback.score * 100) + " 分"}</span></div>
          <h3>参考答案</h3><PracticeContent value={feedback.answer} format={feedback.content_format || question.content_format} sourceUrl={question.source_url} />
          {feedback.explanation && <><h3>{referenceMode ? "复习提示" : "解析"}</h3><PracticeContent value={feedback.explanation} format={feedback.content_format || question.content_format} sourceUrl={question.source_url} /></>}
          <div className="feedback-next">下次复习 <strong>{dateLabel(feedback.review_at)}</strong></div>
        </div>}
        <div className="question-actions">
          {feedback ? <Button tone="primary" icon={ArrowRight} busy={busy} onClick={load}>{completed >= target ? "继续练习" : "下一题"}</Button> : question.question_type === "short_answer" && !reference ? <Button tone="primary" icon={Eye} busy={busy} disabled={!answer.trim()} onClick={reveal}>{referenceMode ? "查看参考答案" : "查看要点并自评"}</Button> : <Button tone="primary" icon={Check} busy={busy} disabled={!answer.trim() || (referenceMode && selfAssessment === null)} onClick={submit}>{reference ? "保存自评" : "提交答案"}</Button>}
          {reference && !feedback && <button className="text-button" disabled={busy} onClick={editAnswer}>继续修改</button>}
        </div>
      </section>
      <aside className="practice-context" aria-label="题目来源与复习信息">
        <div className="section-heading"><h2>本题知识点</h2><BookOpen size={17} /></div><h3>{question.topic_title}</h3><p>{question.topic_summary}</p>
        <div className="context-rule" /><div className="context-label">选题依据</div><p>{question.reason || "优先安排尚未练习的题目"}</p>
        <QuestionSource question={question} />
        <div className="context-rule" /><button className="text-button" onClick={() => navigate("questions", { q: question.topic_title })}>询问这个知识点 <ArrowRight size={15} /></button>
        {question.source_material_id && <button className="text-button" onClick={() => navigate("materials", { id: question.source_material_id })}>查看参考资料 <ArrowRight size={15} /></button>}
        <button className="text-button" onClick={() => navigate("knowledge")}>选择其他方向或知识点 <ArrowRight size={15} /></button>
        <div className="session-progress"><ListChecks size={16} /><span>{completed} / {target} 题</span></div><div className="progress-track"><div style={{ width: Math.min(completed / target, 1) * 100 + "%" }} /></div>{completed >= target && <div className="session-done"><Check size={15} />本轮目标已完成</div>}
      </aside>
    </div>}
  </>;
}
