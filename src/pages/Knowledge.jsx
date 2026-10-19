import { useEffect, useState } from "react";
import { ArrowRight, BookOpen } from "lucide-react";
import { api, dateLabel, navigate, reviewState } from "../lib/api.js";
import { Badge, Button, EmptyState, ErrorNotice, Loading, PageHeading, SearchInput } from "../components/UI.jsx";

export default function Knowledge({ revision }) {
  const [topics, setTopics] = useState(null), [banks, setBanks] = useState(null);
  const [error, setError] = useState(""), [query, setQuery] = useState("");
  const [domain, setDomain] = useState("全部领域"), [filter, setFilter] = useState("all");
  const [details, setDetails] = useState(false);
  function load() {
    setError("");
    Promise.all([api("/knowledge"), api("/practice/banks")]).then(([knowledge, data]) => {
      setTopics(knowledge.topics); setBanks(data.banks);
    }).catch(err => setError(err.message));
  }
  useEffect(load, [revision]);
  const visible = (topics || []).filter(topic => (domain === "全部领域" || topic.domain === domain)
    && `${topic.title} ${topic.summary}`.toLowerCase().includes(query.toLowerCase())
    && (filter === "all" || (filter === "weak" ? topic.attempts && topic.accuracy < 0.6 : !topic.attempts || new Date(topic.next_review_at) <= new Date())));
  const matchingBanks = (banks || []).filter(bank => `${bank.title} ${bank.description}`.toLowerCase().includes(query.toLowerCase()));
  const recommended = banks?.find(bank => bank.id === "recommended");
  return <>
    <PageHeading title="专项题库" description="按方向刷选择题，问答题可在练习页切换。" action={<Button tone="primary" onClick={() => navigate("practice")}>开始刷题</Button>} />
    <ErrorNotice message={error} retry={load} />
    <div className="bank-toolbar"><SearchInput value={query} onChange={setQuery} placeholder="搜索 Java、网络、SQL、Agent…" />
      <button className="text-button" onClick={() => setDetails(value => !value)}>{details ? "收起细分知识点" : "查看细分知识点"}</button>
    </div>
    {!banks ? !error && <Loading /> : <>
      <section className="bank-directory" aria-label="按方向选择题库">
        {matchingBanks.map(bank => <button key={bank.id} className={`bank-entry ${bank.id === "recommended" ? "bank-recommended" : ""}`} onClick={() => navigate("practice", { bank: bank.id })}>
          <div className="bank-entry-title"><strong>{bank.title}</strong><span>{bank.choice_count.toLocaleString()}<small>选择题</small></span></div>
          <p>{bank.description}</p><div className="bank-entry-bottom"><span>未做 {bank.new_choice_count.toLocaleString()} 题</span><span>{bank.short_answer_count.toLocaleString()} 道问答题</span><ArrowRight size={15} /></div>
        </button>)}
      </section>
      {!matchingBanks.length && !details && <EmptyState icon={BookOpen} title="没有匹配的题库" action={<Button onClick={() => setDetails(true)}>搜索细分知识点</Button>} />}
      <p className="bank-count-note">{recommended ? `本地共有 ${recommended.choice_count.toLocaleString()} 道中文选择题，${recommended.short_answer_count.toLocaleString()} 道中文问答题。` : ""}同一道题可属于多个方向，分类题量不能直接相加。</p>
      <div className="bank-source-note"><strong>也可以去牛客刷专项题</strong><p>牛客提供 Java、网络基础、SQL 等在线专项练习。本站显示的数字均为本地实际题量。</p><a href="https://www.nowcoder.com/exam/intelligent" target="_blank" rel="noreferrer">打开牛客专项练习</a></div>
    </>}
    {details && <section className="bank-topic-details">
      <PageHeading title="细分知识点" description={`${topics?.length || 0} 个知识点，供精确筛选和查看学习记录。`} />
      <div className="list-toolbar"><select aria-label="领域" value={domain} onChange={event => setDomain(event.target.value)}>{["全部领域", ...new Set((topics || []).map(topic => topic.domain))].map(item => <option key={item}>{item}</option>)}</select>
        <div className="segmented"><button className={filter === "all" ? "selected" : ""} onClick={() => setFilter("all")}>全部</button><button className={filter === "due" ? "selected" : ""} onClick={() => setFilter("due")}>待复习</button><button className={filter === "weak" ? "selected" : ""} onClick={() => setFilter("weak")}>需巩固</button></div>
      </div>
      {!visible.length ? <EmptyState icon={BookOpen} title="没有符合条件的知识点" /> : <div className="knowledge-table"><div className="knowledge-table-head"><span>知识点</span><span>练习记录</span><span>最近练习</span><span>状态</span><span /></div>{visible.map(topic => {
        const state = reviewState(topic);
        return <div className="knowledge-row" key={topic.id}><div className="knowledge-description"><strong>{topic.title}</strong><span>{topic.domain} · {topic.choice_count || 0} 道选择题 · {topic.question_count - (topic.choice_count || 0)} 道问答题</span></div><div className="knowledge-score">{topic.attempts ? <><strong>{topic.correct} / {topic.attempts}</strong><span>答对 / 自评掌握</span></> : <span className="muted">暂无记录</span>}</div><time>{dateLabel(topic.last_practiced, true)}</time><Badge tone={state.tone}>{state.label}</Badge><Button icon={ArrowRight} disabled={topic.question_count === 0} onClick={() => navigate("practice", { topic: topic.id, ...(topic.choice_count ? {} : { type: "all" }) })}>练习</Button></div>;
      })}</div>}
    </section>}
  </>;
}
