import { useEffect, useState } from "react";
import { ArrowRight, ArrowUpRight, Clock3, History, Check, BookOpen, ListChecks } from "lucide-react";
import { api, dateLabel, navigate, reviewState } from "../lib/api.js";
import { Badge, Button, EmptyState, ErrorNotice, Loading, PageHeading } from "../components/UI.jsx";
import { sourcePlainText } from "../lib/sourceContent.js";

export default function Today({ revision, target, setTarget }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  function load() { setError(""); api("/dashboard").then(setData).catch((err) => setError(err.message)); }
  useEffect(load, [revision]);
  if (!data) return error ? <ErrorNotice message={error} retry={load} /> : <Loading />;
  const progress = Math.min(data.studied_today / target, 1);
  const exercised = data.topics.filter((topic) => topic.attempts).length;
  const date = new Date().toLocaleDateString("zh-CN", { month: "long", day: "numeric", weekday: "long" });

  return <>
    <PageHeading title="今天的复习" description={date + (Number.isFinite(data.question_count) ? ` · 本机题库 ${data.question_count.toLocaleString()} 道题` : "")} action={<Button tone="primary" icon={ArrowRight} onClick={() => navigate("practice")}>开始练习</Button>} />
    <ErrorNotice message={error} retry={load} />
    <section className="stats-strip" aria-label="复习概况">
      {[{ label: "今日已练", value: data.studied_today, suffix: "题", icon: Check }, { label: "待复习知识点", value: data.due_count, suffix: "个", icon: Clock3 }, { label: "已覆盖知识点", value: exercised, suffix: `/ ${data.topic_count}`, icon: BookOpen }, { label: "累计练习", value: data.total_attempts, suffix: "次", icon: ListChecks }].map(({ label, value, suffix, icon: Icon }) => <div className="stat" key={label}><div className="stat-label">{label}<Icon size={16} /></div><div className="stat-value">{value}<span>{suffix}</span></div></div>)}
    </section>
    <div className="dashboard-layout">
      <section className="section-block"><div className="section-heading"><h2>复习队列 <span className="count-label">{data.due_count}</span></h2><button className="text-button" onClick={() => navigate("knowledge")}>全部知识点 <ArrowUpRight size={15} /></button></div>
        {data.due_topics.length ? <div className="topic-queue">{data.due_topics.map((topic, index) => { const state = reviewState(topic); return <div className="queue-row" key={topic.id}><span className="row-number">{String(index + 1).padStart(2, "0")}</span><div className="queue-topic"><strong>{topic.title}</strong><span>{topic.domain}{Number.isFinite(topic.question_count) ? ` · 可练 ${topic.question_count} 题 · 未做 ${topic.new_question_count} 题` : ""}</span></div><Badge tone={state.tone}>{state.label}</Badge><button className="queue-action" aria-label={`练习 ${topic.title}`} title={`练习 ${topic.title}`} onClick={() => navigate("practice", { topic: topic.id, ...(topic.choice_count ? {} : { type: "all" }) })}><ArrowRight size={18} /></button></div>; })}</div> : <EmptyState icon={Check} title="当前复习已完成" action={<Button onClick={() => navigate("practice")}>继续练习</Button>} />}
      </section>
      <section className="daily-plan"><div className="section-heading"><h2>今日目标</h2><span className="muted">{data.studied_today} / {target}</span></div><div className="daily-progress"><span>{Math.round(progress * 100)}<small>%</small></span><div className="progress-track"><div style={{ width: `${progress * 100}%` }} /></div></div><div className="target-label">计划题数</div><div className="segmented" aria-label="计划题数">{[5, 10, 20].map((number) => <button key={number} className={target === number ? "selected" : ""} aria-pressed={target === number} onClick={() => setTarget(number)}>{number} 题</button>)}</div><div className="plan-bottom"><Clock3 size={15} /><span>{data.due_count ? `${data.due_count} 个知识点等待复习` : "本轮复习已完成"}</span></div></section>
    </div>
    <section className="section-block recent-section"><div className="section-heading"><h2>最近练习</h2><button className="text-button" onClick={() => navigate("records")}>查看记录 <ArrowUpRight size={15} /></button></div>
      {data.recent_attempts.length ? <div className="activity-list">{data.recent_attempts.map((attempt) => {
        const reference = attempt.assessment_mode === "reference";
        const passed = reference ? attempt.self_assessment === true : attempt.is_correct;
        return <div className="activity-row" key={attempt.id}><span className={`activity-mark ${passed ? "passed" : "missed"}`}>{passed ? <Check size={15} /> : <History size={15} />}</span><div><strong>{attempt.topic}</strong><span>{sourcePlainText(attempt.stem)}</span></div><Badge tone={passed ? "success" : "warning"}>{reference ? attempt.self_assessment === true ? "已掌握" : attempt.self_assessment === false ? "需复习" : "未自评" : `${Math.round(attempt.score * 100)} 分`}</Badge><time>{dateLabel(attempt.created_at)}</time></div>;
      })}</div> : <EmptyState icon={History} title="还没有练习记录" />}
    </section>
  </>;
}
