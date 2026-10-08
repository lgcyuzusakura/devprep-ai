import { useEffect, useMemo, useRef, useState } from "react";
import { ArrowLeft, ArrowUpRight, BookOpen, FileText, Trash2, Upload, Search } from "lucide-react";
import ReactMarkdown from "react-markdown";
import { api, post, navigate, dateLabel } from "../lib/api.js";
import { Button, EmptyState, ErrorNotice, Loading, PageHeading, SuccessNotice } from "../components/UI.jsx";

const MAX_CONTENT_BYTES = 1_000_000;
const markdownComponents = {
  a: ({ node, ...props }) => <a {...props} target="_blank" rel="noreferrer" />,
};

export default function Materials({ params, changed }) {
  const selectedId = params.get("id");
  const [materials, setMaterials] = useState(null);
  const [selected, setSelected] = useState(null);
  const [error, setError] = useState("");
  const [listError, setListError] = useState("");
  const [detailError, setDetailError] = useState("");
  const [detailErrorId, setDetailErrorId] = useState(null);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState("");
  const [resources, setResources] = useState(null);
  const [resourceError, setResourceError] = useState("");
  const [resourceSearch, setResourceSearch] = useState("");
  const [resourceTrack, setResourceTrack] = useState("全部方向");
  const inputRef = useRef(null);
  const selectedIdRef = useRef(selectedId);
  const listRequestRef = useRef(0);
  const detailRequestRef = useRef({ id: 0, controller: null });
  selectedIdRef.current = selectedId;

  async function loadList() {
    const requestId = ++listRequestRef.current;
    setListError("");
    try {
      const data = await api("/materials");
      if (requestId === listRequestRef.current) setMaterials(data.materials);
    } catch (err) {
      if (requestId === listRequestRef.current) setListError(err.message);
    }
  }

  async function loadResources() {
    setResourceError("");
    try {
      const data = await api("/resources");
      setResources(Array.isArray(data.resources) ? data.resources : []);
    } catch (err) {
      setResourceError(err.message);
    }
  }

  function invalidateDetailRequest() {
    detailRequestRef.current.controller?.abort();
    detailRequestRef.current = { id: detailRequestRef.current.id + 1, controller: null };
    return detailRequestRef.current.id;
  }

  async function loadMaterial(id) {
    const requestId = invalidateDetailRequest();
    const controller = new AbortController();
    detailRequestRef.current.controller = controller;
    setError("");
    setDetailError("");
    setDetailErrorId(null);
    setSelected(null);
    try {
      const data = await api(`/materials/${encodeURIComponent(id)}`, { signal: controller.signal });
      if (requestId !== detailRequestRef.current.id || selectedIdRef.current !== id) return;
      if (data.material?.id !== id) {
        setDetailError("资料内容与请求不匹配，请重试。");
        setDetailErrorId(id);
        return;
      }
      setSelected(data);
    } catch (err) {
      if (controller.signal.aborted || requestId !== detailRequestRef.current.id || selectedIdRef.current !== id) return;
      setDetailError(err.message);
      setDetailErrorId(id);
      setSelected(null);
    } finally {
      if (requestId === detailRequestRef.current.id) detailRequestRef.current.controller = null;
    }
  }

  useEffect(() => {
    loadList();
    loadResources();
    return () => { listRequestRef.current += 1; };
  }, []);

  useEffect(() => {
    invalidateDetailRequest();
    setSelected(null);
    setDetailError("");
    setDetailErrorId(null);
    setError("");
    if (selectedId) loadMaterial(selectedId);
    return () => { invalidateDetailRequest(); };
  }, [selectedId]);

  useEffect(() => {
    const chunkId = params.get("chunk");
    if (selected?.material?.id === selectedId && chunkId) document.getElementById(chunkId)?.scrollIntoView({ block: "start" });
  }, [selected, selectedId, params]);

  const currentSelected = selected?.material?.id === selectedId ? selected : null;
  const currentDetailError = detailErrorId === selectedId ? detailError : "";
  const chunks = useMemo(() => (currentSelected?.chunks || []).filter((chunk) => `${chunk.heading} ${chunk.content}`.toLowerCase().includes(search.toLowerCase())), [currentSelected, search]);
  const resourceTracks = useMemo(() => ["全部方向", ...new Set((resources || []).map((resource) => resource.track))], [resources]);
  const visibleResources = useMemo(() => {
    const query = resourceSearch.trim().toLowerCase();
    return (resources || []).filter((resource) => {
      if (resourceTrack !== "全部方向" && resource.track !== resourceTrack) return false;
      if (!query) return true;
      return [resource.title, resource.description, resource.kind, resource.source, resource.track]
        .join(" ").toLowerCase().includes(query);
    });
  }, [resources, resourceSearch, resourceTrack]);

  async function importFiles(files) {
    if (!files.length) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const validation = await Promise.all(files.map(async (file) => {
        const issues = [];
        if (!/\.(md|markdown|txt)$/i.test(file.name)) issues.push("不是支持的 Markdown 或 TXT 文件");
        let content = "";
        try {
          content = await file.text();
          const byteLength = new TextEncoder().encode(content).byteLength;
          if (byteLength > MAX_CONTENT_BYTES) issues.push("内容超过 1 MB");
        } catch {
          issues.push("文件读取失败");
        }
        return { file, content, issues };
      }));

      const invalid = validation.filter((item) => item.issues.length);
      if (invalid.length) {
        const details = invalid.map(({ file, issues }) => `${file.name}：${issues.join("、")}`).join("；");
        setError(`批量导入已取消：${invalid.length} 份文件未通过校验。${details}`);
        return;
      }

      const successes = [];
      const failures = [];
      for (const { file, content } of validation) {
        try {
          successes.push({ file, result: await post("/materials/import", { filename: file.name, content }) });
        } catch (err) {
          failures.push({ file, message: err.message });
        }
      }

      if (successes.length) {
        await loadList();
        changed();
      }
      const summary = `批量导入完成：成功 ${successes.length} 份，失败 ${failures.length} 份。`;
      if (failures.length) {
        const details = failures.map(({ file, message }) => `${file.name}：${message}`).join("；");
        setError(`${summary}${details ? ` ${details}` : ""}`);
      } else {
        setNotice(summary);
      }
      if (!failures.length && successes.length === 1) navigate("materials", { id: successes[0].result.material.id });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
      if (inputRef.current) inputRef.current.value = "";
    }
  }

  async function removeMaterial(material) {
    if (!material?.id || material.id !== selectedIdRef.current) {
      setError("资料已切换，请重新打开后操作。");
      return;
    }
    if (!window.confirm(`删除“${material.title}”？关联的本地资料片段也会一并删除。`)) return;
    if (material.id !== selectedIdRef.current) {
      setError("资料已切换，请重新打开后操作。");
      return;
    }
    setBusy(true); setError("");
    try {
      await api(`/materials/${encodeURIComponent(material.id)}`, { method: "DELETE" });
      if (selectedIdRef.current === material.id) {
        setSelected(null);
        navigate("materials");
        setNotice("资料已删除。");
      }
      await loadList();
      changed();
    } catch (err) {
      if (selectedIdRef.current === material.id) setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  if (selectedId && !currentSelected) {
    if (!currentDetailError && !error) return <Loading />;
    return <>
      <PageHeading title="资料详情" action={<Button icon={ArrowLeft} onClick={() => navigate("materials")}>返回资料库</Button>} />
      <ErrorNotice message={currentDetailError || error} retry={() => loadMaterial(selectedId)} />
    </>;
  }

  if (currentSelected) return <>
    <PageHeading title={currentSelected.material.title} description={`${currentSelected.material.filename} · 导入于 ${dateLabel(currentSelected.material.imported_at)}`} action={<div className="heading-actions"><Button icon={ArrowLeft} onClick={() => navigate("materials")}>返回资料库</Button>{currentSelected.material.source_type === "用户导入" && <Button icon={Trash2} disabled={busy} onClick={() => removeMaterial(currentSelected.material)}>删除资料</Button>}</div>} />
    <ErrorNotice message={error || currentDetailError} /><div className="material-tools"><label className="search-input"><Search size={17} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索章节内容" /></label><span className="muted">{chunks.length} / {currentSelected.chunks.length} 个片段</span></div>
    <div className="material-reader">{chunks.map((chunk) => <section id={chunk.id} className="material-chunk" key={chunk.id}><h2>{chunk.heading}</h2><div className="markdown-body"><ReactMarkdown components={markdownComponents}>{chunk.content}</ReactMarkdown></div></section>)}{!chunks.length && <EmptyState icon={Search} title="没有匹配的内容" />}</div>
  </>;

  return <>
    <PageHeading title="资料库" description={`${materials?.length || 0} 份学习资料`} action={<><input ref={inputRef} className="sr-only" type="file" multiple accept=".md,.markdown,.txt,text/plain,text/markdown" aria-label="选择 Markdown 或 TXT 资料" onChange={(event) => importFiles([...event.target.files])} /><Button tone="primary" icon={Upload} busy={busy} onClick={() => inputRef.current?.click()}>导入资料</Button></>} />
    <ErrorNotice message={error || listError} retry={loadList} />{notice && <SuccessNotice>{notice}</SuccessNotice>}
    <div className="material-table"><div className="material-table-head"><span>文件</span><span>类型</span><span>片段</span><span>导入时间</span><span /></div>{!materials ? (!error && !listError && <Loading />) : materials.length ? materials.map((material) => <div className="material-row" key={material.id}><span className="file-cell"><FileText size={18} /><span><strong>{material.title}</strong><small>{material.filename}</small></span></span><span>{material.source_type}</span><span>{material.chunk_count}</span><time>{dateLabel(material.imported_at, true)}</time><Button icon={ArrowUpRight} onClick={() => navigate("materials", { id: material.id })}>查看</Button></div>) : <EmptyState icon={BookOpen} title="资料库还是空的" action={<Button icon={Upload} onClick={() => inputRef.current?.click()}>导入第一份资料</Button>} />}</div>
    <div className="file-note">目前支持 UTF-8 Markdown 和纯文本（单份不超过 1 MB）。</div>
    <section className="resource-directory" aria-labelledby="resource-directory-title">
      <div className="section-heading resource-heading"><div><h2 id="resource-directory-title">公开学习资源</h2><p>只保存公开来源的索引和用途说明，打开原站阅读完整内容。</p></div><span className="muted">{resources ? `${visibleResources.length} / ${resources.length}` : "加载中"}</span></div>
      {resourceError && <ErrorNotice message={resourceError} retry={loadResources} />}
      <div className="resource-toolbar"><label className="search-input"><Search size={17} /><input value={resourceSearch} onChange={(event) => setResourceSearch(event.target.value)} placeholder="搜索资源、方向或用途" aria-label="搜索公开学习资源" /></label><select value={resourceTrack} onChange={(event) => setResourceTrack(event.target.value)} aria-label="筛选资源方向">{resourceTracks.map((track) => <option key={track}>{track}</option>)}</select></div>
      {!resources ? (!resourceError && <Loading />) : visibleResources.length ? <div className="resource-table"><div className="resource-table-head"><span>资源</span><span>方向</span><span>类型</span><span>来源与许可</span><span /></div>{visibleResources.map((resource) => <div className="resource-row" key={resource.id}><div className="resource-title"><strong>{resource.title}</strong><small>{resource.description}</small></div><span className="resource-track">{resource.track}</span><span>{resource.kind}</span><div className="resource-source"><span>{resource.source}</span><small>{resource.license}</small></div><a className="resource-link" href={resource.url} target="_blank" rel="noreferrer noopener" aria-label={`打开 ${resource.title}`}><ArrowUpRight size={16} /></a></div>)}</div> : <EmptyState icon={Search} title="没有匹配的公开资源" />}
      <p className="resource-note">来源链接可能随站点调整；许可证文字是索引提示，使用原文或代码前请以来源页面和仓库 LICENSE 为准。</p>
    </section>
  </>;
}
