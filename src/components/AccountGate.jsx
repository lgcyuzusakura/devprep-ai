import { useEffect, useState } from "react";
import { SquareTerminal } from "lucide-react";
import { api, post } from "../lib/api.js";
import { Button, ErrorNotice, Loading } from "./UI.jsx";
import Account from "../pages/Account.jsx";
import { setStorageUser } from "../lib/workspaceStorage.js";

export default function AccountGate({ children }) {
  const [state, setState] = useState(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const load = () => { setError(""); api("/auth/me").then(setState).catch(e => setError(e.message)); };
  useEffect(() => {
    load();
    const expired = () => setState({ enabled: true, authenticated: false });
    window.addEventListener("devprep:login-required", expired);
    return () => window.removeEventListener("devprep:login-required", expired);
  }, []);
  const login = async event => {
    event.preventDefault(); setBusy(true); setError("");
    try {
      const result = await post("/auth/login", { username, password });
      setPassword(""); setState({ enabled: true, authenticated: true, user: result.user });
    } catch (e) { setError(e.message); } finally { setBusy(false); }
  };
  const updated = user => setState(s => ({ ...s, user }));
  const logout = async () => {
    await post("/auth/logout", {});
    setState({ enabled: true, authenticated: false }); setPassword("");
  };
  setStorageUser(state?.user || null);
  if (!state) return <div className="account-loading"><ErrorNotice message={error} retry={load} />{!error && <Loading label="正在读取账号" />}</div>;
  if (!state.enabled) return children({ enabled: false });
  if (!state.authenticated) return <div className="login-page">
    <div className="login-brand"><SquareTerminal size={23} /><span>DevPrep</span></div>
    <section className="login-panel">
      <h1>回到你的学习工作区</h1><p>练习、对话和简历，各自保存。</p>
      <form onSubmit={login}>
        <ErrorNotice message={error} />
        <label>用户名<input required autoFocus name="username" autoComplete="username" minLength={3} maxLength={32} value={username} onChange={e => setUsername(e.target.value)} /></label>
        <label>密码<input required name="password" type="password" autoComplete="current-password" minLength={8} maxLength={128} value={password} onChange={e => setPassword(e.target.value)} /></label>
        <Button type="submit" tone="primary" busy={busy}>登录</Button>
      </form>
      <p className="login-help">体验账号由管理员创建。忘记密码，请联系管理员重置。</p>
    </section>
  </div>;
  if (state.user.must_change_password) return <div className="first-password"><Account user={state.user} updated={updated} logout={logout} firstLogin /></div>;
  return children({ enabled: true, user: state.user, updated, logout });
}
