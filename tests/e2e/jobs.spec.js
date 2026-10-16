import { test, expect } from "@playwright/test";
import { createServer } from "node:http";
import { mkdirSync, readFileSync } from "node:fs";

const original = "# 李同学\n本科，2026年毕业\n实现 Redis 缓存，完成本机压测与接口文档。";
const markdown = "# 李同学\n## 项目经历\n实现 Redis 缓存，完成本机压测与接口文档。\n## 教育背景\n本科，2026年毕业";
let provider, url;
const observed = [];
test.beforeAll(async () => {
  provider = createServer(async (req, res) => {
    let body = "";for await (const chunk of req) body += chunk;
    const payload = JSON.parse(body);observed.push(payload);
    const result = { markdown, greeting: "您好，想了解Java后端岗位，我实现过 Redis 缓存，可以提供项目细节。", self_introduction: "我完成了缓存实现、本机压测与接口文档，希望交流相关技术实践。", follow_up: "您好，想了解岗位目前的招聘进展。如需补充项目说明，我可以提供。", strategy: "把缓存实践前置，教育背景后置，不把JD缺口写成已有技能。", requirements: [{ requirement: "Redis", status: "有证据", source: "resume", evidence: "实现 Redis 缓存", advice: "突出本人实现" }, { requirement: "Kafka", status: "未体现", source: "resume", evidence: "", advice: "补充相关实践" }], changes: [{ source: "resume", evidence: "实现 Redis 缓存", revised: "实现 Redis 缓存，完成本机压测与接口文档。", reason: "把交付放在前面" }], gaps: ["Kafka实践尚未体现"], checks: ["核对本机压测环境"] };
    res.writeHead(200, { "Content-Type": "application/json" });res.end(JSON.stringify({ output: [{ type: "message", content: [{ type: "output_text", text: JSON.stringify(result) }] }] }));
  });
  await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));url = `http://127.0.0.1:${provider.address().port}/v1`;
});
test.afterAll(async () => {provider.closeAllConnections();await new Promise(resolve => provider.close(resolve));});
test.beforeEach(async ({ request }) => {
  await request.post("/api/settings", { data: { toml: `model="fixture-gpt"\nmodel_provider="fixture"\n[model_providers.fixture]\nname="Local fixture"\nbase_url="${url}"\nwire_api="responses"\n` } });
});
async function master(page) {
  await page.getByRole("button", { name: /^通用简历/ }).click();
  await page.getByLabel("通用简历正文", { exact: true }).fill(original);
  await page.getByLabel("通用写作偏好", { exact: true }).fill("项目在前，教育背景在后");
  await page.getByRole("button", { name: "保存通用简历", exact: true }).click();await expect(page.getByText(/通用简历已保存/)).toBeVisible();
  await page.getByRole("button", { name: "返回岗位", exact: true }).click();
}
async function create(page, title = "Java后端浏览器测试") {
  await page.getByLabel("岗位名称", { exact: true }).fill(title);await page.getByLabel("公司名称").fill("示例科技");await page.getByLabel("岗位原始链接").fill(`https://jobs.example.com/${Date.now()}`);
  await page.getByLabel("岗位JD", { exact: true }).fill("Java后端，要求Redis缓存实践，Kafka加分。");
  await page.getByRole("button", { name: "生成专属简历与话术", exact: true }).click();await expect(page.locator(".jobs-paper")).toContainText("李同学");
}
test("master to JD materials, edit versions, attach, export and record real progress", async ({ page, request }) => {
  await page.goto("/#/jobs");await master(page);await create(page);
  await expect(page.locator(".jobs-messages")).toContainText("实现过 Redis 缓存");
  await page.getByText("岗位匹配依据与修改取舍", { exact: true }).click();await expect(page.getByText("Kafka实践尚未体现", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "编辑简历和话术", exact: true }).click();await page.getByLabel("编辑岗位简历", { exact: true }).fill(`${markdown}\n\n完成项目说明与测试文档。`);
  await page.getByLabel("编辑招聘聊天开场白").fill("您好，附上我的缓存项目说明，期待进一步交流。");
  await page.getByRole("button", { name: "另存修改版", exact: true }).click();await expect(page.getByLabel("材料版本").locator("option")).toHaveCount(2);
  await page.locator('input[type="file"]').last().setInputFiles({ name: "项目说明.txt", mimeType: "text/plain", buffer: Buffer.from("缓存项目的设计取舍与本机测试说明") });
  await expect(page.locator(".jobs-attachments")).toContainText("项目说明.txt");
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "下载整套材料", exact: true }).click()]);expect(download.suggestedFilename()).toMatch(/示例科技-Java后端.*\.zip$/);
  await page.getByLabel("材料版本").selectOption({ index: 1 });await expect(page.locator(".jobs-paper")).not.toContainText("完成项目说明与测试文档");await expect(page.locator(".jobs-attachments")).not.toContainText("项目说明.txt");
  await page.getByLabel("材料版本").selectOption({ index: 0 });
  mkdirSync(".cache/jobs-qa", { recursive: true });await page.screenshot({ path: ".cache/jobs-qa/desktop.png", fullPage: true });
  await page.getByRole("tab", { name: "投递记录", exact: true }).click();await page.getByLabel("实际进展", { exact: true }).selectOption("submitted");await page.getByLabel("进展备注").fill("在官网完成投递，收到邮件确认。");await page.getByRole("button", { name: "记录进展", exact: true }).click();await expect(page.getByText("在官网完成投递，收到邮件确认。", { exact: true })).toBeVisible();
  const data = await (await request.get("/api/jobs")).json();const job = data.jobs.find(j => j.title === "Java后端浏览器测试");expect(job.status).toBe("submitted");expect(data.profile.resume).toBe(original);
  const detail = await (await request.get(`/api/jobs/${job.id}`)).json();expect(detail.events[0].material_id).toBe(detail.materials[0].id);expect(observed.at(-1).parallel_tool_calls).toBe(false);
  await page.reload();await page.getByRole("button", { name: /Java后端浏览器测试/ }).click();await expect(page.locator(".jobs-paper")).toContainText("完成项目说明与测试文档");
});
test("capture draft, mobile layout and interview handoff use the selected version snapshot", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const capture = { title: "移动端岗位", url: "https://jobs.example.com/mobile", jd: "Java岗位，要求Redis。" };
  await page.goto(`/#/jobs?capture=${encodeURIComponent(JSON.stringify(capture))}`);
  await expect(page.getByLabel("岗位名称", { exact: true })).toHaveValue("移动端岗位");expect(page.url()).not.toContain("capture=");
  await master(page);await page.getByLabel("公司名称").fill("移动端示例公司");await page.getByRole("button", { name: "生成专属简历与话术", exact: true }).click();await expect(page.locator(".jobs-paper")).toContainText("李同学");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);mkdirSync(".cache/jobs-qa", { recursive: true });await page.screenshot({ path: ".cache/jobs-qa/mobile.png", fullPage: true });
  await page.getByRole("button", { name: "准备模拟面试", exact: true }).click();await expect(page.getByLabel("目标岗位", { exact: true })).toHaveValue("移动端岗位");await expect(page.getByLabel(/^目标岗位 JD/)).toHaveValue("Java岗位，要求Redis。");
});
test("new draft recovery, custom source links and unsupported source stays manual", async ({ page }) => {
  await page.goto("/#/jobs");await page.getByLabel("岗位名称", { exact: true }).fill("未保存的岗位草稿");
  page.on("dialog", dialog => dialog.accept());await page.reload();await expect(page.getByLabel("岗位名称", { exact: true })).toHaveValue("未保存的岗位草稿");
  await page.getByRole("button", { name: "找岗位", exact: true }).click();await page.getByLabel("自定义来源名称").fill("自定义公司招聘");await page.getByLabel("招聘来源链接").fill("https://careers.example.com/");await page.getByRole("button", { name: "保存入口", exact: true }).click();await expect(page.getByRole("link", { name: /自定义公司招聘/ })).toHaveAttribute("href", "https://careers.example.com/");
  await page.getByRole("button", { name: "预览公开岗位", exact: true }).click();await expect(page.getByRole("alert")).toContainText("其他网站请使用粘贴JD或浏览器助手");
});
test("browser helper transfers a reviewable draft and rejects nonlocal workspace addresses", async ({ page }) => {
  await page.goto("/");await page.setContent(readFileSync("browser-helper/popup.html", "utf8"));
  await page.evaluate(() => {
    window.helperOpened = [];
    const helper = { storage: { local: { get: async () => ({}), set: async () => {} } }, tabs: { query: async () => [{ id: 1, url: "https://jobs.example.com/role" }], create: async data => window.helperOpened.push(data.url) }, scripting: { executeScript: async () => [{ result: { title: "Java岗位", url: "https://jobs.example.com/role", jd: "招聘Java工程师" } }] } };
    Object.assign(window.chrome, helper);
  });
  await page.addScriptTag({ content: readFileSync("browser-helper/popup.js", "utf8") });await page.getByRole("button", { name: "读取当前页面", exact: true }).click();await expect(page.getByLabel("JD草稿")).toHaveValue("招聘Java工程师");
  await page.getByLabel("本机工作台地址").fill("http://evil.example.com");await page.getByRole("button", { name: "导入DevPrep草稿", exact: true }).click();await expect(page.locator("#status")).toContainText("工作台必须是本机HTTP地址");expect(await page.evaluate(() => window.helperOpened.length)).toBe(0);
  await page.getByLabel("本机工作台地址").fill("http://127.0.0.1:8765");await page.getByRole("button", { name: "导入DevPrep草稿", exact: true }).click();expect(await page.evaluate(() => window.helperOpened[0])).toContain("/#/jobs?capture=");
});
