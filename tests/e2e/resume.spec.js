import { test, expect } from "@playwright/test";
import { createServer } from "node:http";
import { mkdirSync } from "node:fs";

const original = "李同学\n本科，2026年毕业\n项目：实现 Redis 缓存，压测延迟从200ms降至120ms。";
const markdown = "# 李同学\n本科，2026年毕业\n## 项目经历\n- 实现 Redis 缓存，压测延迟从200ms降至120ms。";
let provider, url, observed;
test.beforeAll(async () => {
  observed = [];
  provider = createServer(async (req, res) => {
    let raw = ""; for await (const chunk of req) raw += chunk;
    const body = JSON.parse(raw); observed.push(body);
    const input = JSON.parse(body.input[0].content);
    if (body.instructions.includes("当前任务：局部扩写")) {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ output: [{ type: "message", content: [{ type: "output_text", text: JSON.stringify({ expanded: "- 实现 Redis 缓存，补充失效场景测试与回源处理。", reason: "把实现和可交付的验证说明写在一起。", checks: ["确认本人实现了回源处理"] }) }] }] }));
      return;
    }
    const rewrite = body.instructions.includes("当前任务：生成");
    const result = rewrite ? {
      markdown, strategy: input.mode === "普通修改" ? "突出个人实现与具体结果，不假设岗位要求。" : "对照 JD，优先展示已有缓存实践。",
      changes: [{ original: "实现 Redis 缓存", revised: "实现 Redis 缓存，压测延迟从200ms降至120ms。", reason: "把个人实现和已有数据放在一起。", evidence: "实现 Redis 缓存，压测延迟从200ms降至120ms。", source: "original" }],
      omissions: [{ text: "泛泛自我评价", reason: "把篇幅让给项目证据" }], checks: ["确认压测环境和个人职责"], missing_facts: ["负载和测量工具"],
    } : {
      positioning: input.mode === "普通修改" ? "先写清工程实践，再补测量口径。" : "围绕岗位要求，把缓存实践放在前面。",
      requirements: [{ requirement: "缓存设计", priority: "核心", status: "有证据", evidence: "实现 Redis 缓存", source: "original", advice: "补充设计选择与测试条件" }],
      strengths: ["有可追问的前后测试值"], weaknesses: [{ issue: "缺少测量环境", action: "补充", reason: "避免把本地结果误认为生产成绩" }],
      questions: [{ id: "q1", question: "请说明压测环境和个人职责", why: "限定量化结果的边界", example: "使用的工具、负载和具体负责部分" }],
      enhancements: input.task_context.endsWith("：需要") ? [{ claim: "为热点接口增加缓存失效策略", assumptions: "本人能实现并解释相关机制", verification: "复现热点请求与缓存失效场景", followup: "如何处理缓存击穿" }] : [],
      outline: ["教育背景", "项目经历", "技能"],
    };
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ output: [{ type: "message", content: [{ type: "output_text", text: JSON.stringify(result) }] }] }));
  });
  await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));
  url = `http://127.0.0.1:${provider.address().port}/v1`;
});
test.afterAll(async () => { provider.closeAllConnections(); await new Promise(resolve => provider.close(resolve)); });
test.beforeEach(async ({ request }) => {
  await request.post("/api/settings", { data: { toml: `model="fixture-gpt"\nmodel_provider="fixture"\n[model_providers.fixture]\nname="Local fixture"\nbase_url="${url}"\nwire_api="responses"\n` } });
});

test("ordinary edit: import, diagnose, supplement, rewrite, compare, edit and export", async ({ page, request }) => {
  await page.goto("/#/resume");
  await page.getByLabel("工作稿名称").fill("普通修改浏览器测试");
  await page.locator('input[type="file"]').setInputFiles({ name: "resume.txt", mimeType: "text/plain", buffer: Buffer.from(original) });
  await expect(page.getByLabel("原始简历")).toHaveValue(original);
  await page.getByRole("button", { name: "分析简历", exact: true }).click();
  await expect(page.getByText("先写清工程实践，再补测量口径。", { exact: true })).toBeVisible();
  await page.getByLabel("请说明压测环境和个人职责").fill("本机测试，由本人实现缓存逻辑。");
  await expect(page.getByRole("button", { name: "生成改写稿", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "将回答加入补充材料" }).click();
  await page.getByRole("button", { name: "采用到补充材料" }).click();
  await expect(page.getByLabel("补充材料")).toHaveValue(/增强设想/);
  await page.getByRole("button", { name: "生成改写稿", exact: true }).click();
  await expect(page.locator(".resume-paper")).toContainText("李同学");
  await page.getByRole("tab", { name: "修改对照" }).click();
  await expect(page.getByText("把个人实现和已有数据放在一起。", { exact: true })).toBeVisible();
  await page.getByRole("tab", { name: "简历正文" }).click();
  await page.getByRole("button", { name: "编辑正文", exact: true }).click();
  await page.getByLabel("编辑简历正文").fill(`${markdown}\n\n## 补充\n完成缓存测试与文档。`);
  const [word] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Word", exact: true }).click()]);
  expect(word.suggestedFilename()).toMatch(/\.docx$/);
  await page.reload();
  await page.getByRole("button", { name: /普通修改浏览器测试/ }).click();
  await page.getByRole("tab", { name: "简历正文" }).click();
  await expect(page.locator(".resume-paper")).toContainText("完成缓存测试与文档");
  await expect(page.getByLabel("简历版本").locator("option")).toHaveCount(2);
  await page.getByLabel("简历版本").selectOption({ index: 1 });
  await expect(page.locator(".resume-paper")).not.toContainText("完成缓存测试与文档");
  expect(observed.at(-2).parallel_tool_calls).toBe(false);
  const workspaces = (await (await request.get("/api/resumes")).json()).workspaces;
  const workspace = workspaces.find(w => w.title === "普通修改浏览器测试");
  const detail = await (await request.get(`/api/resumes/${workspace.id}`)).json();
  expect(detail.versions[0].inputs.facts).toContain("增强设想");
});

test("targeted edit and mobile layout, changed JD requires fresh diagnosis", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/#/resume");
  await page.getByRole("button", { name: "JD 定向修改", exact: true }).click();
  await page.getByLabel("目标岗位 JD").fill("Java校招：要求 Redis 和 SQL，优先有项目测试经验。");
  await page.getByLabel("原始简历").fill(original);
  await page.getByLabel("同时提出合理的增强设想").uncheck();
  await page.getByRole("button", { name: "分析简历", exact: true }).click();
  await expect(page.getByText("围绕岗位要求，把缓存实践放在前面。", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "采用到补充材料" })).toHaveCount(0);
  await page.getByRole("button", { name: "生成改写稿", exact: true }).click();
  await expect(page.locator(".resume-paper")).toContainText("李同学");
  await page.getByLabel("目标岗位 JD").fill("Go 后端开发岗位");
  await expect(page.getByRole("button", { name: "生成改写稿", exact: true })).toBeDisabled();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  mkdirSync(".cache/resume-qa", { recursive: true });
  await page.screenshot({ path: ".cache/resume-qa/mobile.png", fullPage: true });
});

test("print view contains resume only; unsaved draft recovers locally", async ({ page }) => {
  await page.goto("/#/resume");
  await page.getByLabel("原始简历").fill(original);
  page.on("dialog", dialog => dialog.accept());
  await page.reload();
  await expect(page.getByLabel("原始简历")).toHaveValue(original);
  await page.getByRole("button", { name: "分析简历", exact: true }).click();
  await page.getByRole("button", { name: "生成改写稿", exact: true }).click();
  await expect(page.locator(".resume-paper")).toContainText("李同学");
  mkdirSync(".cache/resume-qa", { recursive: true });
  await page.screenshot({ path: ".cache/resume-qa/desktop.png", fullPage: true });
  await page.emulateMedia({ media: "print" });
  await expect(page.locator(".resume-inputs")).toBeHidden();
  await expect(page.locator(".resume-paper")).toBeVisible();
  await page.pdf({ path: ".cache/resume-qa/print.pdf", format: "A4", preferCSSPageSize: true });
});

test("write from scratch, customize layout, reorder sections and adopt local expansion", async ({ page, request }) => {
  page.on("dialog", dialog => dialog.accept());
  await page.goto("/#/resume");
  await page.getByText("从零编写简历 / 查看方向示例", { exact: true }).click();
  const draftText = "# 李同学\n## 教育背景\n本科，2026年毕业\n## 项目经历\n- 实现 Redis 缓存\n## 校园经历\n维护课程文档";
  await page.getByLabel("从零编写简历", { exact: true }).fill(draftText);
  await page.getByRole("button", { name: "创建并继续编辑" }).click();
  await expect(page.getByLabel("编辑简历正文")).toHaveValue(draftText);
  await page.getByText("版式与章节顺序", { exact: true }).click();
  await page.getByRole("button", { name: /一页紧凑/ }).click();
  await page.getByRole("button", { name: "下移教育背景", exact: true }).click();
  expect(await page.getByLabel("编辑简历正文").inputValue()).toMatch(/## 项目经历[\s\S]*## 教育背景/);
  await page.getByRole("button", { name: "下移教育背景", exact: true }).click();
  await page.getByLabel("新章节名称").fill("作品链接");
  await page.getByRole("button", { name: "添加", exact: true }).click();
  await page.getByText("AI 局部扩写", { exact: true }).click();
  await page.getByLabel("待扩写内容").fill("- 实现 Redis 缓存");
  await page.getByLabel("扩写要求").fill("突出失效场景与验证");
  await page.getByRole("button", { name: "扩写这一段" }).click();
  await expect(page.getByText("把实现和可交付的验证说明写在一起。", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "替换原段落" }).click();
  await expect(page.getByLabel("编辑简历正文")).toHaveValue(/回源处理/);
  await page.getByRole("button", { name: "保存新版本", exact: true }).click();
  await expect(page.locator(".resume-paper")).toHaveClass(/template-compact/);
  await expect(page.getByLabel("简历版本").locator("option")).toHaveCount(2);
  const all = (await (await request.get("/api/resumes")).json()).workspaces;
  const detail = await (await request.get(`/api/resumes/${all.find(w => w.title === "自主编写简历").id}`)).json();
  expect(detail.versions[0].layout.template).toBe("compact");
  expect(detail.versions[0].markdown).toMatch(/## 项目经历[\s\S]*## 校园经历[\s\S]*## 教育背景/);
  const [word] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Word", exact: true }).click()]);
  expect(word.suggestedFilename()).toMatch(/\.docx$/);
  await page.getByRole("button", { name: "正文作为新材料", exact: true }).click();
  await expect(page.getByLabel("原始简历")).toHaveValue(/回源处理/);
});

test("fictional examples cover eight directions and create an independent draft", async ({ page }) => {
  page.on("dialog", dialog => dialog.accept());
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/#/resume");
  await page.getByText("从零编写简历 / 查看方向示例", { exact: true }).click();
  await page.getByRole("button", { name: "方向示例 · 8", exact: true }).click();
  await expect(page.locator(".resume-example-index button")).toHaveCount(8);
  await page.getByRole("button", { name: /Agent 学习助手/ }).click();
  await expect(page.locator(".resume-example-source")).toContainText("虚构示例");
  await page.getByRole("button", { name: "复制为独立工作稿" }).click();
  await expect(page.getByLabel("编辑简历正文")).toHaveValue(/工具调用循环/);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  mkdirSync(".cache/resume-qa", { recursive: true });
  await page.screenshot({ path: ".cache/resume-qa/studio-mobile.png", fullPage: true });
});
