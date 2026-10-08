import { useEffect, useState } from "react";
import { ArrowRight, ArrowLeft, BookOpen, Check, CheckCircle2, RotateCcw, Eye, ListChecks } from "lucide-react";
import { api, post, navigate, dateLabel } from "../lib/api.js";
import { Badge, Button, ErrorNotice, Loading, PageHeading } from "../components/UI.jsx";

export default function Practice({ params, target, changed }) {
  const point = params.get("topic") || "";
  const [question, setQuestion] = useState(null);
  const [answer, setAnswer] = useState("");
  const [reference, setReference] = useState(null);
  const [hits, setHits] = useState([]);
  const [feedback, setFeedback] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [completed, setCompleted] = useState(0);
  function load() {
    setBusy(true); setError("");
    api(`/practice/next${point ? `?knowledge_point_id=${encodeURIComponent(point)}` : ""}`).then((data) => {
      setQuestion(data); setAnswer(""); setFeedback(null); setReference(null); setHits([]);
    }).catch((err) => setError(err.message)).finally(() => setBusy(false));
  }
  useEffect(load, [point]);
  async function reveal() {
    if (busy) return;
    setBusy(true); setError("");
    try { const data = await api(`/practice/reference?question_id=${encodeURIComponent(question.id)}`); setReference(data); setHits(data.rubric.map(() => false)); }
    catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }
  async function submit() {
    if (busy || feedback) return;
    setBusy(true); setError("");
    try {
      const data = await post("/attempts", { question_id: question.id, answer, rubric_hits: hits });
      setFeedback(data); setCompleted((value) => value + 1); changed();
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }

  return <>
    <PageHeading title={point ? "知识点练习" : "练习"} description={`本轮已完成 ${completed} 题`} action={<Button icon={ArrowLeft} onClick={() => navigate("today")}>返回复习</Button>} />
    <ErrorNotice message={error} retry={!question ? load : undefined} />
    {!question ? (!error && <Loading />) : <div className="practice-layout">
      <section className="question-surface" aria-busy={busy}><div className="question-topline"><span>{question.domain}</span><Badge>{question.question_type === "single_choice" ? "单选题" : "简答题"}</Badge></div><h2>{question.stem}</h2><div className="question-topic"><BookOpen size={14} />{question.topic_title}</div>
        {question.question_type === "single_choice" ? <fieldset className="answer-options" disabled={!!feedback || busy}><legend className="sr-only">选择答案</legend>{question.options.map((option, index) => <label key={option} className={`answer-option ${answer === option ? "chosen" : ""} ${feedback && option === feedback.answer ? "correct-option" : ""} ${feedback && answer === option && !feedback.correct ? "wrong-option" : ""}`}><input type="radio" name="answer" value={option} checked={answer === option} onChange={() => setAnswer(option)} /><span className="option-letter">{String.fromCharCode(65 + index)}</span><span>{option}</span>{feedback && option === feedback.answer && <Check size={17} />}</label>)}</fieldset> : <><label className="field-label" htmlFor="written-answer">你的回答</label><textarea id="written-answer" className="written-answer" placeholder="写下你记得的关键点" value={answer} disabled={!!reference || !!feedback || busy} maxLength={8000} onChange={(event) => setAnswer(event.target.value)} /><div className="textarea-meta">{answer.length} / 8000</div></>}
        {reference && !feedback && <div className="reference-panel"><h3>参考答案</h3><p>{reference.answer}</p><h3>回答要点</h3><div className="rubric-list">{reference.rubric.map((item, index) => <label key={item}><input type="checkbox" checked={hits[index]} onChange={(event) => setHits((previous) => previous.map((hit, i) => i === index ? event.target.checked : hit))} /><span>{item}</span></label>)}</div></div>}
        {feedback && <div className={`feedback-panel ${feedback.correct ? "feedback-success" : "feedback-review"}`}><div className="feedback-heading"><CheckCircle2 size={20} /><strong>{feedback.correct ? "回答正确" : "还需要巩固"}</strong><span>{Math.round(feedback.score * 100)} 分</span></div><h3>参考答案</h3><p>{feedback.answer}</p><h3>解析</h3><p>{feedback.explanation}</p><div className="feedback-next">下次复习 <strong>{dateLabel(feedback.review_at)}</strong></div></div>}
        <div className="question-actions">{feedback ? <Button tone="primary" icon={ArrowRight} busy={busy} onClick={load}>{completed >= target ? "继续练习" : "下一题"}</Button> : question.question_type === "short_answer" && !reference ? <Button tone="primary" icon={Eye} busy={busy} disabled={!answer.trim()} onClick={reveal}>查看要点并自评</Button> : <Button tone="primary" icon={Check} busy={busy} disabled={!answer.trim()} onClick={submit}>{reference ? "保存自评" : "提交答案"}</Button>}
          {reference && !feedback && <button className="text-button" disabled={busy} onClick={() => { setReference(null); setHits([]); }}>继续修改</button>}
        </div>
      </section>
      <aside className="practice-context"><div className="section-heading"><h2>本题知识点</h2><BookOpen size={17} /></div><h3>{question.topic_title}</h3><p>{question.topic_summary}</p><div className="context-rule" /><div className="context-label">选题依据</div><p>{question.reason || "优先安排尚未练习的题目"}</p><div className="context-label">题目来源</div><Badge>{question.source_type}</Badge><div className="context-rule" /><button className="text-button" onClick={() => navigate("questions", { q: question.topic_title })}>询问这个知识点 <ArrowRight size={15} /></button><button className="text-button" onClick={() => navigate("materials", { id: "material-java-foundations" })}>查看参考资料 <ArrowRight size={15} /></button><div className="session-progress"><ListChecks size={16} /><span>{completed} / {target} 题</span></div><div className="progress-track"><div style={{ width: `${Math.min(completed / target, 1) * 100}%` }} /></div>{completed >= target && <div className="session-done"><Check size={15} />本轮目标已完成</div>}</aside>
    </div>}
  </>;
}
