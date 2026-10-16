export async function api(path, options = {}) {
  const response = await fetch(`/api${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...options.headers },
  });
  const payload = await response.json();
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith("/auth/")) window.dispatchEvent(new Event("devprep:login-required"));
    throw new Error(payload.error?.message || "请求失败，请稍后重试。");
  }
  return payload;
}

export function post(path, payload) {
  return api(path, { method: "POST", body: JSON.stringify(payload) });
}

export function navigate(page, params = {}) {
  const query = new URLSearchParams(params).toString();
  window.location.hash = `/${page}${query ? `?${query}` : ""}`;
}

export function getRoute() {
  const [path, query = ""] = window.location.hash.replace(/^#\/?/, "").split("?");
  return { page: path || "today", params: new URLSearchParams(query) };
}

export function dateLabel(value, short = false) {
  if (!value) return "尚未练习";
  const date = new Date(value);
  return date.toLocaleString("zh-CN", {
    month: "2-digit", day: "2-digit",
    ...(!short ? { hour: "2-digit", minute: "2-digit", hour12: false } : {}),
  });
}

export function reviewState(topic) {
  if (!topic.attempts) return { label: "待开始", tone: "neutral" };
  if (topic.accuracy < 0.6) return { label: "需巩固", tone: "warning" };
  if (topic.next_review_at && new Date(topic.next_review_at) <= new Date()) return { label: "待复习", tone: "warning" };
  return { label: "已练习", tone: "success" };
}
