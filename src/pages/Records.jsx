import { useEffect, useState } from "react";
import { Check, CircleAlert, ListChecks } from "lucide-react";
import { api, dateLabel } from "../lib/api.js";
import { sourcePlainText } from "../lib/sourceContent.js";
import { Badge, EmptyState, ErrorNotice, Loading, PageHeading } from "../components/UI.jsx";

function passedAttempt(attempt) {
  return attempt.assessment_mode === "reference" ? attempt.self_assessment === true : attempt.is_correct;
}

export default function Records({ revision }) {
  const [attempts, setAttempts] = useState(null);
  const [filter, setFilter] = useState("all");
  const [error, setError] = useState("");
  function load() { setError(""); api("/history?limit=100").then((data) => setAttempts(data.attempts)).catch((err) => setError(err.message)); }
  useEffect(load, [revision]);
  const all = attempts || [];
  const visible = all.filter((attempt) => filter === "all" || (filter === "correct" ? passedAttempt(attempt) : !passedAttempt(attempt)));
  const graded = all.filter((attempt) => attempt.assessment_mode !== "reference");
  const selfAssessed = all.filter((attempt) => attempt.assessment_mode === "reference");
  const correct = graded.filter((attempt) => attempt.is_correct).length;
  const mastered = selfAssessed.filter((attempt) => attempt.self_assessment === true).length;
  const average = graded.length ? Math.round(graded.reduce((sum, attempt) => sum + attempt.score, 0) / graded.length * 100) : null;

  return <>
    <PageHeading title="学习记录" description={(attempts?.length || 0) + " 条最近作答"} />
    <ErrorNotice message={error} retry={load} />
    <div className="record-summary">
      <div><span>完成练习</span><strong>{all.length}<small> 次</small></strong></div>
      <div><span>完整答对</span><strong>{correct}<small> / {graded.length} 题</small></strong></div>
      <div><span>评分题平均分</span><strong>{average === null ? "—" : average}<small>{average === null ? " 暂无评分题" : " 分"}</small></strong></div>
      <div><span>自评已掌握</span><strong>{mastered}<small> / {selfAssessed.length} 题</small></strong></div>
    </div>
    <div className="record-toolbar"><h2>作答明细</h2><div className="segmented">
      <button className={filter === "all" ? "selected" : ""} onClick={() => setFilter("all")}>全部</button>
      <button className={filter === "correct" ? "selected" : ""} onClick={() => setFilter("correct")}>答对 / 已掌握</button>
      <button className={filter === "wrong" ? "selected" : ""} onClick={() => setFilter("wrong")}>待巩固 / 需复习</button>
    </div></div>
    {!attempts ? (!error && <Loading />) : !visible.length ? <EmptyState icon={ListChecks} title="还没有符合条件的记录" /> : <div className="records-list">{visible.map((attempt) => {
      const reference = attempt.assessment_mode === "reference";
      const passed = passedAttempt(attempt);
      return <article className="record-item" key={attempt.id}>
        <span className={"record-icon " + (passed ? "passed" : "missed")}>{passed ? <Check size={17} /> : <CircleAlert size={17} />}</span>
        <div className="record-content"><div className="record-title"><Badge>{attempt.domain}</Badge><strong>{attempt.topic}</strong><time>{dateLabel(attempt.created_at)}</time></div><p>{sourcePlainText(attempt.stem)}</p><div className="record-answer"><span>你的回答</span><strong>{typeof attempt.answer === "string" ? attempt.answer : String(attempt.answer ?? "未作答")}</strong></div></div>
        <div className={"record-score" + (reference ? " record-self-assessment" : "")}><strong>{reference ? attempt.self_assessment === true ? "已掌握" : attempt.self_assessment === false ? "需复习" : "未自评" : Math.round(attempt.score * 100)}</strong><span>{reference ? "原文自评" : "得分"}</span></div>
      </article>;
    })}</div>}
  </>;
}
