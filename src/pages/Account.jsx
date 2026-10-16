import { useEffect, useState } from "react";
import { api, post } from "../lib/api.js";
import { Button, ErrorNotice, PageHeading, SuccessNotice } from "../components/UI.jsx";

export default function Account({ user, updated, logout, firstLogin = false }) {
  const [current, setCurrent] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [accounts, setAccounts] = useState([]);
  const [name, setName] = useState("");
  const [temporary, setTemporary] = useState("");
  const [resetName, setResetName] = useState("");
  const [resetPassword, setResetPassword] = useState("");
  const refresh = () => api("/auth/accounts").then(r => setAccounts(r.accounts)).catch(e => setError(e.message));
  useEffect(() => { if (user.admin && !firstLogin) refresh(); }, [user.admin, firstLogin]);
  const run = async task => {
    setBusy(true); setError(""); setNotice("");
    try { await task(); } catch (e) { setError(e.message); } finally { setBusy(false); }
  };
  const change = e => { e.preventDefault(); run(async () => {
    if (password !== confirmation) throw new Error("两次输入的新密码不一致。");
    const result = await post("/auth/password", { current_password: current, new_password: password });
    updated(result.user); setCurrent(""); setPassword(""); setConfirmation(""); setNotice("密码已更新，其他设备上的旧登录已失效。");
  }); };
  const create = e => { e.preventDefault(); run(async () => {
    await post("/auth/accounts", { username: name, password: temporary });
    setName(""); setTemporary(""); await refresh(); setNotice("账号已创建。请单独把初始密码交给朋友，首次登录后需修改。");
  }); };
  const reset = e => { e.preventDefault(); run(async () => {
    await post(`/auth/accounts/${encodeURIComponent(resetName)}/reset-password`, { password: resetPassword });
    setResetName(""); setResetPassword(""); await refresh(); setNotice("初始密码已重置，这个账号原有登录已失效，下次登录需改密。");
  }); };
  return <div className="account-page">
    <PageHeading title={firstLogin ? "先设置你自己的密码" : "我的账号"} description={firstLogin ? "初始密码仅用于首次登录，设置完成后进入学习工作区。" : `${user.username} 的个人工作区`} />
    <ErrorNotice message={error} />{notice && <SuccessNotice>{notice}</SuccessNotice>}
    <section className="account-section"><h2>{firstLogin ? "修改初始密码" : "修改密码"}</h2>
      <form className="account-form" onSubmit={change}>
        <label>当前密码<input required type="password" autoComplete="current-password" maxLength={128} value={current} onChange={e => setCurrent(e.target.value)} /></label>
        <label>新密码<input required type="password" autoComplete="new-password" minLength={8} maxLength={128} value={password} onChange={e => setPassword(e.target.value)} /><span>至少 8 个字符，请与其他网站的密码区分。</span></label>
        <label>再输入一次新密码<input required type="password" autoComplete="new-password" minLength={8} maxLength={128} value={confirmation} onChange={e => setConfirmation(e.target.value)} /></label>
        <div><Button tone="primary" busy={busy} type="submit">{firstLogin ? "保存并进入工作区" : "保存新密码"}</Button></div>
      </form>
    </section>
    {user.admin && !firstLogin && <section className="account-section"><h2>朋友体验账号</h2><p className="muted">每个账号独立保存学习数据。朋友可使用已配置的模型，模型设置由你管理。</p>
      <ul className="account-list">{accounts.map(account => <li key={account.username}><strong>{account.username}</strong><span>{account.admin ? "管理员" : account.must_change_password ? "待修改初始密码" : "学习账号"}</span></li>)}</ul>
      <h3>创建账号</h3><form className="account-form" onSubmit={create}>
        <label>新用户名<input required pattern="[a-z][a-z0-9_]{2,31}" minLength={3} maxLength={32} autoComplete="off" value={name} onChange={e => setName(e.target.value)} /><span>3–32 位小写字母、数字或下划线，以字母开头。</span></label>
        <label>初始密码<input required type="password" autoComplete="new-password" minLength={8} maxLength={128} value={temporary} onChange={e => setTemporary(e.target.value)} /></label>
        <div><Button busy={busy} type="submit">创建体验账号</Button></div>
      </form>
      <h3>重置朋友的密码</h3><form className="account-form" onSubmit={reset}>
        <label>选择账号<select required value={resetName} onChange={e => setResetName(e.target.value)}><option value="">请选择</option>{accounts.filter(a => !a.admin).map(a => <option key={a.username} value={a.username}>{a.username}</option>)}</select></label>
        <label>新的初始密码<input required type="password" autoComplete="new-password" minLength={8} maxLength={128} value={resetPassword} onChange={e => setResetPassword(e.target.value)} /></label>
        <div><Button busy={busy} type="submit">重置密码</Button></div>
      </form>
    </section>}
    <Button busy={busy} onClick={() => run(logout)}>退出登录</Button>
  </div>;
}
