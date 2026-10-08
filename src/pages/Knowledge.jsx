import { useEffect, useState } from "react";
import { ArrowRight, BookOpen } from "lucide-react";
import { api, dateLabel, navigate, reviewState } from "../lib/api.js";
import { Badge, Button, EmptyState, ErrorNotice, Loading, PageHeading, SearchInput } from "../components/UI.jsx";

export default function Knowledge({ revision }) {
  const [topics, setTopics] = useState(null);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [domain, setDomain] = useState("全部领域");
  const [filter, setFilter] = useState("all");
  function load() { setError(""); api("/knowledge").then((data) => setTopics(data.topics)).catch((err) => setError(err.message)); }
  useEffect(load, [revision]);
  const visible = (topics || []).filter((topic) => (domain === "全部领域" || topic.domain === domain) && `${topic.title} ${topic.summary}`.toLowerCase().includes(query.toLowerCase()) && (filter === "all" || (filter === "weak" ? topic.attempts && topic.accuracy < 0.6 : !topic.attempts || new Date(topic.next_review_at) <= new Date())));
  const domains = [...new Set((topics || []).map((topic) => topic.domain))];
  return <><PageHeading title="知识点" description={`${topics?.length || 0} 个知识点`} /><ErrorNotice message={error} retry={load} /><div className="list-toolbar"><SearchInput value={query} onChange={setQuery} /><select aria-label="领域" value={domain} onChange={(event) => setDomain(event.target.value)}>{["全部领域", ...domains].map((item) => <option key={item}>{item}</option>)}</select><div className="segmented"><button className={filter === "all" ? "selected" : ""} onClick={() => setFilter("all")}>全部</button><button className={filter === "due" ? "selected" : ""} onClick={() => setFilter("due")}>待复习</button><button className={filter === "weak" ? "selected" : ""} onClick={() => setFilter("weak")}>需巩固</button></div></div>
    {!topics ? (!error && <Loading />) : !visible.length ? <EmptyState icon={BookOpen} title="没有符合条件的知识点" /> : <div className="knowledge-table"><div className="knowledge-table-head"><span>知识点</span><span>练习记录</span><span>最近练习</span><span>状态</span><span /></div>{visible.map((topic) => { const state = reviewState(topic); return <div className="knowledge-row" key={topic.id}><div className="knowledge-description"><strong>{topic.title}</strong><span>{topic.domain}</span>{topic.prerequisites?.length > 0 && <small>前置：{topic.prerequisites.map((id) => topics.find((other) => other.id === id)?.title || id).join("、")}</small>}</div><div className="knowledge-score">{topic.attempts ? <><strong>{topic.correct} / {topic.attempts}</strong><span>答对 / 自评掌握</span></> : <span className="muted">暂无记录</span>}</div><time>{dateLabel(topic.last_practiced, true)}</time><Badge tone={state.tone}>{state.label}</Badge><Button icon={ArrowRight} onClick={() => navigate("practice", { topic: topic.id })}>练习</Button></div>; })}</div>}
  </>;
}
