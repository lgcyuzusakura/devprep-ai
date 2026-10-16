const $ = id => document.getElementById(id);
chrome.storage.local.get("workspace").then(value => {if (value.workspace) $("workspace").value = value.workspace;});
async function action(fn) {
  const buttons = [...document.querySelectorAll("button")];buttons.forEach(b => b.disabled = true);$("status").textContent = "";
  try {await fn();} catch (ex) {$("status").textContent = ex.message || "操作失败，请直接复制JD到工作台。";} finally {buttons.forEach(b => b.disabled = false);}
}
$("read").addEventListener("click", () => action(async () => {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id || !tab.url?.startsWith("https://")) throw new Error("请打开HTTPS招聘岗位页面后再读取。");
  const [result] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: () => ({ title: document.title, url: location.href, jd: (window.getSelection()?.toString().trim() || document.body.innerText).slice(0, 20000) }) });
  $("title").value = result.result.title.slice(0, 160);$("url").value = result.result.url;$("jd").value = result.result.jd;
  $("status").textContent = "已读取，移除导航、广告或无关信息后再导入。";
}));
$("import").addEventListener("click", () => action(async () => {
  const workspace = new URL($("workspace").value);
  if (workspace.protocol !== "http:" || !["127.0.0.1", "localhost", "[::1]"].includes(workspace.hostname) || workspace.username || workspace.password || workspace.search || workspace.hash || !["", "/"].includes(workspace.pathname)) throw new Error("工作台必须是本机HTTP地址，例如 http://127.0.0.1:8765。");
  const url = new URL($("url").value);if (url.protocol !== "https:" || url.username || url.password) throw new Error("岗位链接需要使用HTTPS。");
  if (!$("title").value.trim() || !$("jd").value.trim()) throw new Error("请先读取并核对岗位名称和JD。");
  await chrome.storage.local.set({ workspace: workspace.origin });
  await chrome.tabs.create({ url: `${workspace.origin}/#/jobs?capture=${encodeURIComponent(JSON.stringify({ title: $("title").value, url: url.href, jd: $("jd").value }))}` });
  $("status").textContent = "已在新标签页打开草稿，请核对公司名称后保存。";
}));
$("fill").addEventListener("click", () => action(async () => {
  const text = $("message").value.trim();if (!text) throw new Error("先粘贴你要填写的话术。");
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id || !tab.url?.startsWith("https://")) throw new Error("请先在HTTPS招聘网页点击目标输入框。");
  const [result] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, args: [text], func: value => {
    const field = document.activeElement;
    if (field instanceof HTMLTextAreaElement || field instanceof HTMLInputElement && ["text", "search", ""].includes(field.type)) {
      if (field.disabled || field.readOnly) return false;
      if (field.value.trim()) return "occupied";
      const setter = Object.getOwnPropertyDescriptor(field instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype, "value").set;
      setter.call(field, value);field.dispatchEvent(new Event("input", { bubbles: true }));field.dispatchEvent(new Event("change", { bubbles: true }));return true;
    }
    if (field instanceof HTMLElement && field.isContentEditable) {if (field.innerText.trim()) return "occupied";field.focus();return document.execCommand("insertText", false, value);}
    return false;
  } });
  if (result.result === "occupied") throw new Error("目标输入框已有内容，助手不会覆盖。请手动编辑或清空后重试。");
  if (!result.result) throw new Error("未找到可填写的输入框。先关闭助手、点击网页中的输入框，再重试；嵌套页面请手动粘贴。");
  $("status").textContent = "文本已填入，请在网页检查后手动发送。";
}));
