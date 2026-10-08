import { useEffect, useMemo, useRef, useState } from "react";
import { parse } from "smol-toml";
import { Check, Code2, Download, ExternalLink, Plus, RefreshCw, Save, Settings2, ShieldCheck, Trash2 } from "lucide-react";
import { api, post } from "../lib/api.js";
import { Button, ErrorNotice, PageHeading, SuccessNotice } from "../components/UI.jsx";

const emptyProvider = (id = "provider") => ({ id, name: "OpenAI 兼容网关", base_url: "https://api.example.com/v1", wire_api: "responses", env_key: "OPENAI_API_KEY", models_path: "/models", timeout_seconds: 15 });
const quote = (value) => `"${String(value ?? "").replaceAll("\\", "\\\\").replaceAll('"', '\\"').replaceAll("\n", "\\n")}"`;
const isTable = (value) => Object.prototype.toString.call(value) === "[object Object]";
const providerKey = (provider) => provider ? JSON.stringify([provider.id, provider.base_url, provider.wire_api, provider.env_key, provider.models_path, provider.timeout_seconds]) : "";
const publicConfig = (data) => ({ model: data.model || "", model_provider: data.model_provider || "", providers: data.providers || [] });

function configFromToml(raw) {
  const parsed = parse(raw);
  const text = (value, field, limit) => {
    if (typeof value !== "string" || [...value].length > limit) throw new Error(`${field} 必须是长度不超过 ${limit} 的文本。`);
    return value;
  };
  const model = text(parsed.model ?? "", "model", 160);
  const model_provider = text(parsed.model_provider ?? "", "model_provider", 64);
  const tables = parsed.model_providers ?? {};
  if (!isTable(tables)) throw new Error("model_providers 必须是 TOML 表。");
  const providers = Object.entries(tables).map(([id, item]) => {
    if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$/.test(id) || !isTable(item)) throw new Error("Provider 名称或配置无效。");
    const name = text(item.name ?? id, `${id}.name`, 100);
    const base = text(item.base_url ?? "", `${id}.base_url`, 500).trim();
    if (!/^https?:\/\/[^/?#]+/i.test(base) || /[\s\\\u0000-\u001f\u007f]/.test(base)) throw new Error(`${id}.base_url 需要是有效的 http/https 地址，且不能包含空白。`);
    let url;
    try { url = new URL(base); } catch { throw new Error(`${id}.base_url 的地址或端口无效。`); }
    const authority = base.match(/^https?:\/\/([^/?#]+)/i)[1];
    if (!url.hostname || authority.includes("@") || url.port === "0") throw new Error(`${id}.base_url 不能包含凭据，端口必须有效。`);
    if (url.search || url.hash) throw new Error(`${id}.base_url 不能包含 query 或 fragment。`);
    const base_url = `${url.protocol}//${url.host}${url.pathname.replace(/\/+$/, "")}`;
    let wire_api = item.wire_api ?? "responses";
    if (wire_api === "chat") wire_api = "chat_completions";
    if (!["responses", "chat_completions"].includes(wire_api)) throw new Error(`${id}.wire_api 支持 responses 或 chat_completions。`);
    if (item.requires_openai_auth !== undefined && typeof item.requires_openai_auth !== "boolean") throw new Error(`${id}.requires_openai_auth 必须是布尔值。`);
    const env_key = item.env_key ?? (item.requires_openai_auth ? "OPENAI_API_KEY" : "");
    if (typeof env_key !== "string" || (env_key && !/^[A-Z_][A-Z0-9_]{0,63}$/.test(env_key))) throw new Error(`${id}.env_key 应为环境变量名称，例如 OPENAI_API_KEY。`);
    const models_path = item.models_path ?? "/models";
    let decodedPath;
    try { decodedPath = typeof models_path === "string" ? decodeURIComponent(models_path) : ""; } catch { throw new Error(`${id}.models_path 包含无效的转义字符。`); }
    if (typeof models_path !== "string" || [...models_path].length > 500 || !models_path.startsWith("/") || models_path.startsWith("//") || /[?#\\\s\u0000-\u001f\u007f]/.test(decodedPath) || decodedPath.includes("..")) throw new Error(`${id}.models_path 必须是以 / 开始的站内路径。`);
    const timeout_seconds = item.timeout_seconds ?? 15;
    if (!Number.isInteger(timeout_seconds) || timeout_seconds < 2 || timeout_seconds > 120) throw new Error(`${id}.timeout_seconds 范围为 2 到 120 秒的整数。`);
    return { id, name, base_url, wire_api, env_key, models_path, timeout_seconds };
  });
  if (model_provider && !providers.some((provider) => provider.id === model_provider)) throw new Error("选中的 model_provider 不在配置列表中。");
  return { model, model_provider, providers };
}

export default function ModelSettings({ changed }) {
  const [config, setConfig] = useState(null);
  const [savedConfig, setSavedConfig] = useState(null);
  const [raw, setRaw] = useState("");
  const [mode, setMode] = useState("form");
  const [selectedId, setSelectedId] = useState("");
  const [catalog, setCatalog] = useState({ key: "", models: [] });
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [modelsLoading, setModelsLoading] = useState(false);
  const modelRequest = useRef({ generation: 0, controller: null, key: "" });
  const settingsRequest = useRef(null);
  const skipAutoDiscovery = useRef("");
  const selected = config?.providers.find((provider) => provider.id === selectedId) || null;
  const provider = useMemo(() => config?.providers.find((item) => item.id === config.model_provider), [config]);
  const savedProvider = savedConfig?.providers.find((item) => item.id === provider?.id);
  const currentProviderKey = providerKey(provider);
  const savedProviderKey = providerKey(savedProvider);
  const providerSaved = !!provider && currentProviderKey === savedProviderKey;
  const models = providerSaved && catalog.key === currentProviderKey ? catalog.models : [];
  const controlsDisabled = loading || busy;
  function fail(err, operation = null) { setError({ message: err.message, operation }); }
  function cancelDiscovery() {
    modelRequest.current.controller?.abort();
    modelRequest.current = { generation: modelRequest.current.generation + 1, controller: null, key: "" };
    setModelsLoading(false);
  }
  async function load() {
    settingsRequest.current?.abort();
    const controller = new AbortController();
    settingsRequest.current = controller;
    setLoading(true); setError(null);
    try {
      const data = await api("/settings", { signal: controller.signal });
      if (controller.signal.aborted) return;
      const next = publicConfig(data);
      setConfig(next); setSavedConfig(next);
      setRaw(data.toml || ""); setSelectedId(data.model_provider || data.providers?.[0]?.id || "");
    } catch (err) { if (!controller.signal.aborted) fail(err, "load"); }
    finally { if (!controller.signal.aborted) setLoading(false); }
  }
  useEffect(() => {
    load();
    return () => { settingsRequest.current?.abort(); modelRequest.current.controller?.abort(); modelRequest.current.generation++; };
  }, []);
  useEffect(() => {
    setError((current) => current?.operation === "discover" ? null : current);
    if (skipAutoDiscovery.current && skipAutoDiscovery.current === savedProviderKey) {
      skipAutoDiscovery.current = "";
      return;
    }
    if (!providerSaved) { cancelDiscovery(); setCatalog({ key: "", models: [] }); return; }
    if (modelRequest.current.controller && modelRequest.current.key === savedProviderKey) return;
    discover(savedProvider).catch((err) => setError((current) => current && current.operation !== "discover" ? current : { message: err.message, operation: "discover" }));
  }, [currentProviderKey, savedProviderKey]);
  async function discover(target, force = false) {
    if (!target) { cancelDiscovery(); setCatalog({ key: "", models: [] }); return null; }
    modelRequest.current.controller?.abort();
    const controller = new AbortController();
    const generation = modelRequest.current.generation + 1;
    const key = providerKey(target);
    modelRequest.current = { generation, controller, key };
    setCatalog({ key, models: [] }); setModelsLoading(true);
    try {
      const data = await api(`/models?provider=${encodeURIComponent(target.id)}${force ? "&refresh=1" : ""}`, { signal: controller.signal });
      if (controller.signal.aborted || generation !== modelRequest.current.generation) return null;
      setCatalog({ key, models: data.models });
      return data.models;
    } catch (err) {
      if (controller.signal.aborted || generation !== modelRequest.current.generation) return null;
      throw err;
    } finally {
      if (generation === modelRequest.current.generation) { modelRequest.current.controller = null; setModelsLoading(false); }
    }
  }
  function changeProvider(field, value) {
    setConfig((current) => ({ ...current, providers: current.providers.map((item) => item.id === selectedId ? { ...item, [field]: value } : item) }));
    setNotice("");
  }
  function addProvider() {
    if (!config) return;
    let suffix = 1;
    let id = "openai_compatible";
    while (config.providers.some((item) => item.id === id)) id = `openai_compatible_${suffix++}`;
    setConfig((current) => ({ ...current, providers: [...current.providers, emptyProvider(id)], model_provider: id }));
    setSelectedId(id); setNotice("");
  }
  function removeProvider() {
    if (!selected) return;
    const rest = config.providers.filter((item) => item.id !== selectedId);
    const next = rest[0]?.id || "";
    setConfig((current) => ({ ...current, providers: rest, model_provider: current.model_provider === selectedId ? next : current.model_provider }));
    setSelectedId(next); setNotice("");
  }
  function makeToml(value) {
    const lines = [`model = ${quote(value.model)}`, `model_provider = ${quote(value.model_provider)}`];
    for (const item of value.providers) lines.push("", `[model_providers.${item.id}]`, `name = ${quote(item.name)}`, `base_url = ${quote(item.base_url)}`, `wire_api = ${quote(item.wire_api)}`, `env_key = ${quote(item.env_key)}`, `models_path = ${quote(item.models_path)}`, `timeout_seconds = ${Number(item.timeout_seconds)}`);
    return `${lines.join("\n")}\n`;
  }
  async function save() {
    if (controlsDisabled || (mode === "form" && !config)) return;
    setBusy(true); setError(null); setNotice("");
    cancelDiscovery();
    try {
      const toml = mode === "toml" ? raw : makeToml(config);
      const data = await post("/settings", { toml });
      const next = publicConfig(data);
      const nextProvider = next.providers.find((item) => item.id === data.model_provider);
      skipAutoDiscovery.current = providerKey(nextProvider);
      setRaw(data.toml); setConfig(next); setSavedConfig(next);
      setSelectedId(data.model_provider || data.providers?.[0]?.id || ""); setNotice("模型设置已保存。"); changed();
      if (data.model_provider) {
        try {
          const discovered = await discover(next.providers.find((item) => item.id === data.model_provider), true);
          if (discovered) setNotice(`模型设置已保存，已从网关读取 ${discovered.length} 个模型。`);
        } catch (err) { fail(err, "discover"); }
      } else setCatalog({ key: "", models: [] });
    } catch (err) { fail(err, "save"); }
    finally { setBusy(false); }
  }
  async function importCodex() {
    setBusy(true); setError(null); setNotice("");
    try { const data = await post("/settings/import-codex", {}); setRaw(data.toml); setMode("toml"); setNotice("本机 Codex Provider 已导入安全预览，请确认后保存。"); }
    catch (err) { fail(err, "import"); }
    finally { setBusy(false); }
  }
  async function refreshModels() {
    if (controlsDisabled) return;
    if (!providerSaved) { fail(new Error("Provider 配置有未保存修改，请先保存设置再读取模型列表。")); return; }
    setBusy(true); setError(null); setNotice("");
    try { const discovered = await discover(savedProvider, true); if (discovered) setNotice(`已从网关读取 ${discovered.length} 个模型。`); }
    catch (err) { fail(err, "discover"); }
    finally { setBusy(false); }
  }
  function applyTomlToForm() {
    try {
      const next = configFromToml(raw);
      setConfig(next); setSelectedId(next.model_provider || next.providers[0]?.id || ""); setMode("form"); setError(null); setNotice("");
    } catch (err) { fail(new Error(`TOML 配置无法应用：${err.message}`)); }
  }
  const retry = error ? { load, save, discover: refreshModels, import: importCodex }[error.operation] : undefined;

  return <>
    <PageHeading title="模型设置" description="Provider 使用兼容接口；API Key 从本机环境变量读取，不会保存到配置文件。" action={<Button tone="primary" icon={Save} busy={busy} disabled={loading || (mode === "form" && !config)} onClick={() => save()}>保存设置</Button>} />
    <ErrorNotice message={error?.message} retry={controlsDisabled ? undefined : retry} />{notice && <SuccessNotice>{notice}</SuccessNotice>}
    <div className="settings-toolbar"><div className="segmented" aria-label="编辑方式"><button disabled={controlsDisabled} className={mode === "form" ? "selected" : ""} onClick={() => { if (mode === "toml") applyTomlToForm(); }}>表单</button><button disabled={controlsDisabled} className={mode === "toml" ? "selected" : ""} onClick={() => { if (mode !== "toml") setRaw(makeToml(config || { model: "", model_provider: "", providers: [] })); setMode("toml"); setNotice(""); }}>TOML</button></div><Button icon={Download} busy={busy} disabled={loading} onClick={importCodex}>导入本机 Codex 配置</Button></div>
    {mode === "form" ? <div className="settings-layout"><section className="settings-panel"><div className="section-heading"><h2>Provider</h2><Button icon={Plus} disabled={controlsDisabled || !config} onClick={addProvider}>新增</Button></div>
      {config?.providers.length > 0 && <div className="provider-tabs" role="tablist" aria-label="选择 Provider">{config.providers.map((item) => <button disabled={controlsDisabled} key={item.id} role="tab" aria-selected={selectedId === item.id} className={selectedId === item.id ? "active" : ""} onClick={() => setSelectedId(item.id)}>{item.name || item.id}</button>)}</div>}
      {!selected && <div className="provider-empty"><Settings2 size={23} /><p>{loading ? "正在读取模型配置…" : "还没有配置模型 Provider。"}</p><Button icon={Plus} disabled={controlsDisabled || !config} onClick={addProvider}>添加 Provider</Button></div>}
      {selected && <div className="settings-fields"><label>Provider ID<input value={selected.id} disabled /><small>用作配置标识，修改 Provider 时保持稳定。</small></label><label>显示名称<input value={selected.name} disabled={controlsDisabled} maxLength={100} onChange={(event) => changeProvider("name", event.target.value)} /></label><label>API Base URL<input aria-label="API Base URL" value={selected.base_url} disabled={controlsDisabled} placeholder="https://api.example.com/v1" autoComplete="url" onChange={(event) => changeProvider("base_url", event.target.value)} /><small>兼容 OpenAI 的网关地址，可以带 /v1 路径。</small></label>
        <div className="form-row"><label>接口协议<select value={selected.wire_api} disabled={controlsDisabled} onChange={(event) => changeProvider("wire_api", event.target.value)}><option value="responses">Responses API</option><option value="chat_completions">Chat Completions</option></select></label><label>模型目录路径<input value={selected.models_path} disabled={controlsDisabled} placeholder="/models" onChange={(event) => changeProvider("models_path", event.target.value)} /></label></div>
        <div className="form-row"><label>API Key 环境变量<input aria-label="API Key 环境变量" value={selected.env_key} disabled={controlsDisabled} placeholder="OPENAI_API_KEY" onChange={(event) => changeProvider("env_key", event.target.value.toUpperCase())} /><small>只保存变量名称；密钥值在启动服务前设置。</small></label><label>超时（秒）<input type="number" min="2" max="120" value={selected.timeout_seconds} disabled={controlsDisabled} onChange={(event) => changeProvider("timeout_seconds", Number(event.target.value))} /></label></div>
        <div className="key-status"><ShieldCheck size={17} /><span>{selected.env_key ? (selected.has_api_key ? `已从环境读取 ${selected.env_key}` : `等待环境变量 ${selected.env_key}`) : "此 Provider 不要求 API Key"}</span></div>
        <div className="field-actions"><Button icon={Trash2} disabled={controlsDisabled} onClick={removeProvider}>删除 Provider</Button></div>
      </div>}</section>
      <section className="settings-panel model-panel"><div className="section-heading"><h2>默认模型</h2>{provider && <Button icon={RefreshCw} busy={busy || modelsLoading} disabled={loading || !providerSaved} onClick={refreshModels}>刷新列表</Button>}</div><label className="settings-label">使用 Provider<select value={config?.model_provider || ""} disabled={controlsDisabled || !config} onChange={(event) => { setConfig((current) => ({ ...current, model_provider: event.target.value })); setNotice(""); }}><option value="">选择 Provider</option>{(config?.providers || []).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
        <div className="settings-label"><label htmlFor="model-list">模型</label><select id="model-list" disabled={controlsDisabled || !config || modelsLoading || !providerSaved} value={(config?.model && models.includes(config.model)) ? config.model : "__manual__"} onChange={(event) => { if (event.target.value !== "__manual__") { setConfig((current) => ({ ...current, model: event.target.value })); setNotice(""); } }}><option value="__manual__">手动输入模型名</option>{models.map((model) => <option key={model} value={model}>{model}</option>)}</select><input aria-label="模型名" value={config?.model || ""} disabled={controlsDisabled || !config} maxLength={160} placeholder="例如 provider/model-name" onChange={(event) => { setConfig((current) => ({ ...current, model: event.target.value })); setNotice(""); }} /></div>
        <div className="model-hint"><RefreshCw size={16} />{provider && !providerSaved ? "Provider 配置有未保存修改。保存设置后将读取模型列表。" : modelsLoading ? "正在读取模型列表…" : "网关需要鉴权时，请确认环境变量已设置，再刷新列表。"}</div><div className="model-endpoint"><span>模型目录</span><code>{provider ? `${provider.base_url.replace(/\/$/, "")}${provider.models_path}` : "尚未设置 Provider"}</code></div>
      </section></div> : <section className="toml-editor"><div className="toml-toolbar"><span><Code2 size={16} />settings.toml</span><Button icon={Check} disabled={controlsDisabled} onClick={applyTomlToForm}>解析并切换到表单</Button></div><textarea aria-label="TOML 模型配置" spellCheck="false" disabled={controlsDisabled} value={raw} onChange={(event) => { setRaw(event.target.value); setNotice(""); }} /><div className="toml-note">支持 model、model_provider 与 model_providers 子集。API Key 请放在环境变量中；保存前由本地服务验证 Provider 字段并移除不支持的配置项。</div></section>}
    <section className="config-help"><div><h2>凭据与设置文件</h2><p>设置文件仅包含模型名、Provider 地址、协议、超时和环境变量名。API Key 不会通过表单或 TOML 保存。</p></div><a href="https://github.com/lgcyuzusakura/devprep-ai#model-provider" target="_blank" rel="noreferrer">配置说明 <ExternalLink size={14} /></a></section>
  </>;
}
