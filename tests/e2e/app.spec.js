import { test, expect } from "@playwright/test";
import { createServer } from "node:http";

let gateway;
let gatewayURL;

test.beforeAll(async () => {
  gateway = createServer((request, response) => {
    response.setHeader("Content-Type", "application/json");
    if (request.url === "/v1/models") {
      response.end(JSON.stringify({ data: [{ id: "study-test" }, { id: "study-chat" }] }));
      return;
    }
    if (request.url === "/v1/responses") {
      response.end(JSON.stringify({ output: [{ type: "message", content: [{ type: "output_text", text: "volatile 保证可见性，但 count++ 是复合读改写操作，仍然需要原子性保护。[1]" }] }] }));
      return;
    }
    response.statusCode = 404;
    response.end(JSON.stringify({ error: "not_found" }));
  });
  await new Promise((resolve) => gateway.listen(0, "127.0.0.1", resolve));
  gatewayURL = `http://127.0.0.1:${gateway.address().port}/v1`;
});

test.afterAll(async () => {
  await new Promise((resolve) => gateway.close(resolve));
});

test("choice and rubric practice save records and survive reload", async ({ page }) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/#/practice?topic=java-equals");
  await expect(page.getByRole("heading", { name: "两个对象通过 equals 判断相等时，hashCode 必须满足什么约定？" })).toBeVisible();
  await page.getByRole("radio", { name: "A hashCode 必须相同" }).check();
  await page.getByRole("button", { name: "提交答案" }).click();
  await expect(page.getByText("回答正确", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "下一题" }).click();
  await expect(page.getByRole("heading", { name: "为什么重写 equals 时通常也要重写 hashCode？" })).toBeVisible();
  await page.getByLabel("你的回答").fill("equals 相等时 hashCode 一样。哈希集合依赖桶定位。否则查找失败。");
  await page.getByRole("button", { name: "查看要点并自评" }).click();
  await expect(page.getByRole("checkbox")).toHaveCount(3);
  for (const checkbox of await page.getByRole("checkbox").all()) await checkbox.check();
  await page.getByRole("button", { name: "保存自评" }).click();
  await expect(page.getByText("回答正确", { exact: true })).toBeVisible();
  await page.goto("/#/records");
  await expect(page.getByText("hashCode 必须相同", { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText("equals 相等时 hashCode 一样。哈希集合依赖桶定位。否则查找失败。", { exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});

test("imported material is searchable, readable, and removable", async ({ page }) => {
  await page.goto("/#/materials");
  await page.getByLabel("选择 Markdown 或 TXT 资料").setInputFiles({ name: "CacheNotes.md", mimeType: "text/markdown", buffer: Buffer.from("# 缓存策略\nNovelCacheMarker 使用 LRU 淘汰旧数据。") });
  await expect(page.getByRole("heading", { name: "缓存策略", exact: true })).toBeVisible();
  await page.goto("/#/questions");
  await page.getByLabel("你的问题").fill("NovelCacheMarker");
  await page.getByRole("button", { name: "提问", exact: true }).click();
  await expect(page.getByText("NovelCacheMarker 使用 LRU 淘汰旧数据。", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /\[1\] CacheNotes/ }).click();
  await expect(page.getByRole("heading", { name: "缓存策略", exact: true })).toBeVisible();
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "删除资料" }).click();
  await expect(page.getByRole("heading", { name: "资料库", exact: true })).toBeVisible();
  await expect(page.getByText("CacheNotes", { exact: true })).toHaveCount(0);
});

test("public resource directory loads and filters by track and keyword", async ({ page }) => {
  await page.goto("/#/materials");
  await expect(page.getByRole("heading", { name: "公开学习资源", exact: true })).toBeVisible();
  await expect(page.getByText("MDN Web 文档", { exact: true })).toBeVisible();
  await page.getByLabel("筛选资源方向").selectOption({ label: "前端" });
  await expect(page.getByText("MDN Web 文档", { exact: true })).toBeVisible();
  await expect(page.getByText("JavaGuide", { exact: true })).toHaveCount(0);
  await page.getByLabel("搜索公开学习资源").fill("浏览器");
  await expect(page.getByText("MDN Web 文档", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "打开 MDN Web 文档" })).toHaveAttribute("href", "https://developer.mozilla.org/zh-CN/");
});

test("provider save discovers models and a selected model answers with citations", async ({ page }) => {
  await page.goto("/#/settings");
  await page.getByRole("button", { name: "添加 Provider" }).click();
  await page.getByLabel("API Base URL", { exact: true }).fill(gatewayURL);
  await page.getByLabel("API Key 环境变量", { exact: true }).fill("");
  await page.getByRole("button", { name: "保存设置" }).click();
  await expect(page.getByText("模型设置已保存，已从网关读取 2 个模型。")).toBeVisible();
  await page.getByLabel("模型", { exact: true }).selectOption("study-test");
  await page.getByRole("button", { name: "保存设置" }).click();
  await expect(page.getByText("模型设置已保存，已从网关读取 2 个模型。")).toBeVisible();
  await page.getByRole("button", { name: "TOML", exact: true }).click();
  await expect(page.getByLabel("TOML 模型配置")).toHaveValue(/model = "study-test"/);
  await page.getByRole("button", { name: "解析并切换到表单" }).click();
  await expect(page.getByLabel("API Base URL", { exact: true })).toHaveValue(gatewayURL);
  await page.goto("/#/questions");
  await page.getByLabel("你的问题").fill("volatile 能保证 count++ 原子性吗？");
  await page.getByRole("button", { name: "提问", exact: true }).click();
  await expect(page.getByText("volatile 保证可见性，但 count++ 是复合读改写操作，仍然需要原子性保护。[1]")).toBeVisible();
  await expect(page.getByText("附资料引用", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /\[1\]/ }).first().click();
  await expect(page.getByRole("heading", { name: "Java 内存模型与 volatile", exact: true })).toBeVisible();
});

test("all mobile views fit within the viewport and navigation works", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/#/today");
  await expect(page.getByRole("heading", { name: "今天的复习" })).toBeVisible();
  await page.screenshot({ path: "test-results/mobile-today.png", fullPage: true });
  await page.getByRole("button", { name: "打开导航" }).click();
  await page.getByRole("link", { name: "知识点", exact: true }).click();
  await expect(page.getByRole("heading", { name: "知识点", exact: true })).toBeVisible();
  for (const route of ["today", "practice", "knowledge", "materials", "questions", "records", "settings"]) {
    await page.goto(`/#/${route}`);
    await expect(page.locator("main h1")).toBeVisible();
    const dimensions = await page.evaluate(() => ({ width: document.documentElement.clientWidth, content: document.documentElement.scrollWidth }));
    expect(dimensions.content, route).toBeLessThanOrEqual(dimensions.width);
  }
  await page.goto("/#/settings");
  await page.screenshot({ path: "test-results/mobile-settings.png", fullPage: true });
});
