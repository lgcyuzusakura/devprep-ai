import { test, expect } from "@playwright/test";

test("algorithm collections filter real metadata and language drafts survive switching", async ({ page }) => {
  await page.goto("/#/algorithms?problem=two-sum");
  await expect(page.getByRole("heading", { name: "两数之和", exact: true })).toBeVisible();
  await page.getByLabel("算法题单").selectOption("first100");
  await expect(page.locator(".algorithm-list-row")).toHaveCount(100);
  await page.getByLabel("算法题单").selectOption("hot100");
  await expect(page.locator(".algorithm-list-row")).toHaveCount(100);
  for (const [collection, count] of [["leetcode75",75], ["interview150",150], ["programming",33], ["dp",46], ["graph",36], ["binary",32]]) {
    await page.getByLabel("算法题单").selectOption(collection);
    await expect(page.locator(".algorithm-list-row")).toHaveCount(count);
    await expect(page.getByRole("status")).toHaveText(`当前筛选：${count} 道题`);
  }
  await page.getByLabel("算法题单").selectOption("");
  await expect(page.locator(".algorithm-list-row")).toHaveCount(360);
  await page.getByLabel("搜索算法题").fill("999999");
  await expect(page.getByText("没有符合筛选条件的题目。")).toBeVisible();
  await page.getByLabel("搜索算法题").fill("");
  await page.getByLabel("编程语言").selectOption("python");
  const editor=page.locator(".cm-content");
  await editor.click(); await page.keyboard.press("Control+A"); await page.keyboard.insertText("print('draft-marker')");
  await page.getByLabel("编程语言").selectOption("java");
  await expect(editor).toContainText("public class Main");
  await page.getByLabel("编程语言").selectOption("python");
  await expect(editor).toContainText("draft-marker");
  await page.reload(); await expect(page.locator(".cm-content")).toContainText("draft-marker");
  await page.getByRole("button", { name: "向学习教练要个提示" }).click();
  await expect(page.getByLabel("发给学习教练")).toHaveValue(/两数之和/);
});

test("new plan questions open Chinese statements and official language templates", async ({ page }) => {
  await page.goto("/#/algorithms");
  await page.getByLabel("算法题单").selectOption("leetcode75");
  await page.getByLabel("搜索算法题").fill("1071");
  await page.locator(".algorithm-list-row").click();
  await expect(page.getByRole("heading", { name: "1071. 字符串的最大公因子" })).toBeVisible();
  await expect(page.locator(".problem-content .markdown-body")).toContainText("字符串");
  await page.getByLabel("编程语言").selectOption("python");
  await expect(page.locator(".cm-content")).toContainText("gcdOfStrings");
  await expect(page.getByRole("link", { name: "打开 LeetCode 原题与官方判题 ↗" })).toHaveAttribute("href", "https://leetcode.cn/problems/greatest-common-divisor-of-strings/");
});

test("learning conversation sends SSE, renders tools and reloads persisted history", async ({ page, request }) => {
  await request.post("/api/settings", { data: { toml: 'model = ""\nmodel_provider = ""\n' } });
  await page.goto("/#/questions");
  await page.getByLabel("发给学习教练").fill("HashMap");
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await expect(page.locator(".study-message-assistant")).toContainText("模型尚未配置");
  await expect(page.locator(".tool-trace > summary")).toContainText("学习工具");
  const url=page.url(); await page.reload();
  await expect(page.locator(".study-message-user")).toContainText("HashMap");
  expect(page.url()).toBe(url);
  await page.getByRole("button", { name: "新对话" }).click();
  await expect(page.getByRole("heading", { name: "今天想学什么？" })).toBeVisible();
});

test("algorithm and chat layouts fit a mobile viewport", async ({ page }) => {
  await page.setViewportSize({ width:390,height:844 });
  for(const route of ["algorithms?problem=two-sum", "questions"]){
    await page.goto("/#/"+route); await expect(page.locator("main h1")).toBeVisible();
    const size=await page.evaluate(()=>({width:document.documentElement.clientWidth,content:document.documentElement.scrollWidth}));
    expect(size.content).toBeLessThanOrEqual(size.width);
  }
});
