import { useEffect, useState } from "react";
import { Check, CircleAlert, History, ListChecks } from "lucide-react";
import { api, dateLabel } from "../lib/api.js";
import { Badge, EmptyState, ErrorNotice, Loading, PageHeading } from "../components/UI.jsx";

export default function Records({ revision }) {
  const [attempts, setAttempts] = useState(null);
  const [filter, setFilter] = useState("all");
  const [error, setError] = useState("");
  function load() { setError(""); api("/history?limit=100").then((data) => setAttempts(data.attempts)).catch((err) => setError(err.message)); }
  useEffect(load, [revision]);
  const visible = (attempts || []).filter((attempt) => filter === "all" || (filter === "correct" ? attempt.is_correct : !attempt.is_correct));
  const correct = (attempts || []).filter((item) => item.is_correct).length;
  return <><PageHeading title="学习记录" description={`${attempts?.length || 0} 条最近作答`} /><ErrorNotice message={error} retry={load} /><div className="record-summary"><div><span>完成练习</span><strong>{attempts?.length || 0}<small> 次</small></strong></div><div><span>完整答对</span><strong>{correct}<small> 题</small></strong></div><div><span>平均得分</span><strong>{attempts?.length ? Math.round(attempts.reduce((sum, item) => sum + item.score, 0) / attempts.length * 100) : 0}<small> 分</small></strong></div><div><span>近 100 次</span><History size={17} /></div></div>
    <div className="record-toolbar"><h2>作答明细</h2><div className="segmented"><button className={filter === "all" ? "selected" : ""} onClick={() => setFilter("all")}>全部</button><button className={filter === "correct" ? "selected" : ""} onClick={() => setFilter("correct")}>答对</button><button className={filter === "wrong" ? "selected" : ""} onClick={() => setFilter("wrong")}>待巩固</button></div></div>
    {!attempts ? (!error && <Loading />) : !visible.length ? <EmptyState icon={ListChecks} title="还没有符合条件的记录" /> : <div className="records-list">{visible.map((attempt) => <article className="record-item" key={attempt.id}><span className={`record-icon ${attempt.is_correct ? "passed" : "missed"}`}>{attempt.is_correct ? <Check size={17} /> : <CircleAlert size={17} />}</span><div className="record-content"><div className="record-title"><Badge>{attempt.domain}</Badge><strong>{attempt.topic}</strong><time>{dateLabel(attempt.created_at)}</time></div><p>{attempt.stem}</p><div className="record-answer"><span>你的回答</span><strong>{typeof attempt.answer === "string" ? attempt.answer : String(attempt.answer ?? "未作答")}</strong></div></div><div className="record-score"><strong>{Math.round(attempt.score * 100)}</strong><span>得分</span></div></article>)}</div>}
  </>;
}
