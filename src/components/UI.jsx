import { LoaderCircle, RefreshCw, Search, AlertCircle, Check, X } from "lucide-react";

export function Button({ children, icon: Icon, tone = "default", busy, disabled, className = "", ...props }) {
  return <button {...props} className={`button button-${tone} ${className}`} disabled={busy || disabled}>
    {busy ? <LoaderCircle size={16} className="spin" /> : Icon ? <Icon size={16} /> : null}{children}
  </button>;
}

export function IconButton({ icon: Icon, label, ...props }) {
  return <button className="icon-button" aria-label={label} title={label} {...props}><Icon size={18} /></button>;
}

export function Badge({ children, tone = "neutral" }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export function Loading({ label = "正在读取" }) {
  return <div className="loading-state" role="status"><LoaderCircle size={20} className="spin" /><span>{label}</span></div>;
}

export function ErrorNotice({ message, retry, onDismiss }) {
  if (!message) return null;
  return <div className="error-notice" role="alert"><AlertCircle size={18} /><span>{message}</span>
    {retry && <IconButton icon={RefreshCw} label="重试" onClick={retry} />}
    {onDismiss && <IconButton icon={X} label="关闭提示" onClick={onDismiss} />}
  </div>;
}

export function EmptyState({ icon: Icon, title, action }) {
  return <div className="empty-state">{Icon && <Icon size={26} strokeWidth={1.5} />}<p>{title}</p>{action}</div>;
}

export function SearchInput({ value, onChange, placeholder = "搜索知识点" }) {
  return <div className="search-input"><Search size={17} /><input aria-label={placeholder} placeholder={placeholder} value={value} onChange={(event) => onChange(event.target.value)} /></div>;
}

export function PageHeading({ title, description, action }) {
  return <div className="page-heading"><div><h1>{title}</h1>{description && <p>{description}</p>}</div>{action}</div>;
}

export function SuccessNotice({ children }) {
  return <div className="success-notice" role="status"><Check size={16} />{children}</div>;
}
