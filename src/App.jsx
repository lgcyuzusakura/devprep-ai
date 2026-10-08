import { useEffect, useState } from "react";
import { LayoutDashboard, ListChecks, Network, Library, MessagesSquare, History, Settings2, PanelLeftClose, Menu, X, ArrowUpRight, SquareTerminal } from "lucide-react";
import { api, getRoute, navigate } from "./lib/api.js";
import { IconButton, Badge } from "./components/UI.jsx";
import Today from "./pages/Today.jsx";
import Practice from "./pages/Practice.jsx";
import Knowledge from "./pages/Knowledge.jsx";
import Materials from "./pages/Materials.jsx";
import Questions from "./pages/Questions.jsx";
import Records from "./pages/Records.jsx";
import ModelSettings from "./pages/ModelSettings.jsx";

const navigation = [
  { id: "today", label: "今日复习", icon: LayoutDashboard },
  { id: "practice", label: "练习", icon: ListChecks },
  { id: "knowledge", label: "知识点", icon: Network },
  { id: "materials", label: "资料库", icon: Library },
  { id: "questions", label: "知识问答", icon: MessagesSquare },
  { id: "records", label: "学习记录", icon: History },
];

export default function App() {
  const [route, setRoute] = useState(getRoute);
  const [menuOpen, setMenuOpen] = useState(false);
  const [revision, setRevision] = useState(0);
  const [settings, setSettings] = useState(null);
  const [target, setTarget] = useState(() => {
    try { return Number(localStorage.getItem("devprep.target")) || 10; } catch { return 10; }
  });

  useEffect(() => {
    const changed = () => { setRoute(getRoute()); setMenuOpen(false); };
    window.addEventListener("hashchange", changed);
    return () => window.removeEventListener("hashchange", changed);
  }, []);
  useEffect(() => { api("/settings").then(setSettings).catch(() => setSettings(null)); }, [revision]);
  const updateTarget = (value) => {
    setTarget(value);
    try { localStorage.setItem("devprep.target", String(value)); } catch { /* Storage can be disabled. */ }
  };
  const changed = () => setRevision((value) => value + 1);
  const section = navigation.find((item) => item.id === route.page)?.label || "模型设置";
  let page;
  const common = { revision, changed };
  switch (route.page) {
    case "practice": page = <Practice key={route.params.toString()} params={route.params} target={target} {...common} />; break;
    case "knowledge": page = <Knowledge {...common} />; break;
    case "materials": page = <Materials params={route.params} {...common} />; break;
    case "questions": page = <Questions params={route.params} settings={settings} />; break;
    case "records": page = <Records {...common} />; break;
    case "settings": page = <ModelSettings changed={changed} />; break;
    default: page = <Today target={target} setTarget={updateTarget} {...common} />;
  }

  return <div className="app-shell">
    {menuOpen && <button className="nav-scrim" aria-label="关闭导航" onClick={() => setMenuOpen(false)} />}
    <aside className={`sidebar ${menuOpen ? "sidebar-open" : ""}`}>
      <a className="brand" href="#/today"><span className="brand-mark"><SquareTerminal size={23} /></span><span>DevPrep<span className="brand-subtitle">学习工作台</span></span></a>
      <div className="mobile-nav-close"><IconButton icon={X} label="关闭导航" onClick={() => setMenuOpen(false)} /></div>
      <div className="sidebar-label">学习</div>
      <nav aria-label="主导航">{navigation.map(({ id, label, icon: Icon }) => <a key={id} href={`#/${id}`} className={`nav-item ${route.page === id ? "active" : ""}`} aria-current={route.page === id ? "page" : undefined}><Icon size={18} /><span>{label}</span>{route.page === id && <span className="nav-indicator" />}</a>)}</nav>
      <div className="sidebar-bottom"><a href="#/settings" className={`nav-item ${route.page === "settings" ? "active" : ""}`}><Settings2 size={18} /><span>模型设置</span></a><div className="local-status"><span className="status-dot" />个人工作区<span>本机</span></div></div>
    </aside>
    <div className="workspace">
      <header className="topbar"><div className="breadcrumb"><span className="mobile-menu"><IconButton icon={Menu} label="打开导航" onClick={() => setMenuOpen(true)} /></span><PanelLeftClose size={17} className="desktop-nav-icon" /><span className="breadcrumb-root">工作区</span><span className="breadcrumb-divider">/</span><span>{section}</span></div><button className="model-status" onClick={() => navigate("settings")} title="打开模型设置"><span className={`status-dot ${settings?.model ? "" : "status-dot-muted"}`} /><span>{settings?.model || "模型未配置"}</span><ArrowUpRight size={14} /></button></header>
      <main className="main-content">{page}</main>
      <footer className="app-footer"><span>DevPrep <Badge>v0.1</Badge></span><span>计算机学习 · 考试与面试</span></footer>
    </div>
  </div>;
}
