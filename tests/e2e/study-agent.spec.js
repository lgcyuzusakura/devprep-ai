import { test, expect } from "@playwright/test";
import { createServer } from "node:http";

// A local provider fixture exercises the real Spring Boot streaming path.
// No third-party model requests or API keys are used in these tests.
let provider, url, requests, active, peak;
test.beforeAll(async () => {
  requests = []; active = 0; peak = 0;
  provider = createServer(async (req, res) => {
    let raw = ""; for await (const part of req) raw += part;
    const body = JSON.parse(raw); requests.push(body); active++; peak = Math.max(peak, active);
    res.on("close", () => active--);
    res.writeHead(200, { "Content-Type": "text/event-stream" });
    const event = value => res.write(`data: ${JSON.stringify(value)}\n\n`);
    const last = body.input.filter(item => item.role === "user").at(-1)?.content || "";
    const result = body.input.filter(item => item.type === "function_call_output").at(-1);
    if (last.includes("出题") && !result) {
      event({ type: "response.completed", response: { status: "completed", output: [{ type: "function_call", call_id: "question-call", name: "pick_question", arguments: "{}" }] } });
      res.end(); return;
    }
    const text = last.includes("出题") ? "先试着回答这道题。回答后我们一起分析。" : "TCP 保证数据有序。\n\n```java\nint count = 1;\n```\n\n" + "先看序号，再看确认与重传。\n\n".repeat(last.includes("长回答") ? 70 : 1);
    event({ type: "response.output_text.delta", delta: text.slice(0, 10) });
    const delay = setTimeout(() => {
      event({ type: "response.output_text.delta", delta: text.slice(10) });
      event({ type: "response.completed", response: { status: "completed", output: [{ type: "message", content: [{ type: "output_text", text }] }] } });
      res.end();
    }, last.includes("停止") ? 20_000 : 1200);
    res.on("close", () => clearTimeout(delay));
  });
  await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));
  url = `http://127.0.0.1:${provider.address().port}/v1`;
});
test.afterAll(async () => { provider.closeAllConnections(); await new Promise(resolve => provider.close(resolve)); });
test.beforeEach(async ({ request }) => {
  requests.length = 0; peak = 0;
  await request.post("/api/settings", { data: { toml: `model = "fixture-gpt"\nmodel_provider = "fixture"\n[model_providers.fixture]\nname = "本地测试"\nbase_url = "${url}"\nwire_api = "responses"\ntimeout_seconds = 40\n` } });
});

test("first reply streams immediately; next draft, retry and session rename survive reload", async ({ page }) => {
  const errors = []; page.on("pageerror", err => errors.push(err.message));
  await page.goto("/#/questions");
  await page.getByLabel("发给学习教练").fill("讲讲 TCP");
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await expect(page.locator('[data-streaming="true"]')).toContainText("TCP 保证数据有序");
  await expect(page.getByRole("button", { name: "停止生成" })).toBeVisible();
  await page.getByLabel("发给学习教练").fill("下一条草稿");
  await expect(page.getByRole("button", { name: "重新回答" })).toBeVisible();
  await expect(page.locator(".study-message-user")).toHaveCount(1);
  await expect(page.getByLabel("发给学习教练")).toHaveValue("下一条草稿");
  await page.getByRole("button", { name: "复制代码" }).click();
  await expect(page.locator(".study-code-toolbar")).toContainText("已复制");
  await page.getByRole("button", { name: "重新回答" }).click();
  await expect(page.getByRole("button", { name: "发送", exact: true })).toBeVisible();
  await expect(page.locator(".study-message-user")).toHaveCount(1);
  await expect(page.locator(".study-message-assistant")).toHaveCount(1);
  expect(requests).toHaveLength(2);
  expect(requests[1].input.filter(item => item.role === "user")).toHaveLength(1);
  expect(peak).toBe(1);
  await page.getByRole("button", { name: "重命名 讲讲 TCP" }).click();
  await page.getByLabel("对话名称", { exact: true }).fill("TCP 复习笔记");
  await page.getByRole("button", { name: "保存对话名称" }).click();
  await expect(page.locator(".study-header")).toContainText("TCP 复习笔记");
  await page.reload();
  await expect(page.getByLabel("发给学习教练")).toHaveValue("下一条草稿");
  await expect(page.locator(".study-message-assistant")).toContainText("确认与重传");
  await page.getByLabel("搜索对话").fill("TCP 复习笔记");
  await expect(page.locator(".session-row")).toHaveCount(1);
  await page.screenshot({ path: ".cache/study-agent-desktop.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByLabel("发给学习教练")).toBeInViewport();
  await page.getByRole("button", { name: "打开对话列表" }).click();
  await expect(page.getByLabel("搜索对话")).toBeVisible();
  await page.getByRole("button", { name: "关闭对话列表" }).last().click();
  await page.screenshot({ path: ".cache/study-agent-mobile.png", fullPage: true });
  expect(errors).toEqual([]);
});

test("stop cancels provider and preserves partial output; following turn keeps context", async ({ page, request }) => {
  await page.goto("/#/questions");
  await page.getByLabel("发给学习教练").fill("讲 TCP，等我停止");
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await expect(page.locator('[data-streaming="true"]')).toContainText("TCP");
  await page.getByRole("button", { name: "停止生成" }).click();
  await expect(page.getByRole("button", { name: "发送", exact: true })).toBeVisible();
  await expect(page.locator(".study-message-assistant")).toContainText("已停止");
  await expect.poll(() => active).toBe(0);
  const session = new URL(page.url()).hash.split("session=")[1];
  const detail = await (await request.get(`/api/study/sessions/${session}`)).json();
  expect(detail.messages[1].payload.cancelled).toBe(true);
  expect(detail.messages[1].content).toContain("TCP");
  await page.reload();
  await page.getByLabel("发给学习教练").fill("接着说确认机制");
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await expect(page.getByRole("button", { name: "发送", exact: true })).toBeVisible();
  await expect(page.locator(".study-message-user")).toHaveCount(2);
  expect(requests.at(-1).input.some(item => item.role === "assistant" && item.content.includes("TCP"))).toBe(true);
  expect(peak).toBe(1);
});

test("tool activity shows a question in conversation and waits for learner answer", async ({ page }) => {
  await page.goto("/#/questions");
  await page.getByLabel("发给学习教练").fill("给我出题");
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await expect(page.locator(".chat-question")).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole("button", { name: "发送", exact: true })).toBeVisible({ timeout: 20_000 });
  await expect(page.locator(".tool-trace > summary")).toContainText("学习工具");
  await page.getByRole("button", { name: "在对话里作答" }).click();
  await expect(page.getByLabel("发给学习教练")).toHaveValue("我的答案是：");
  expect(requests.at(-1).input.some(item => item.type === "function_call_output")).toBe(true);
  const toolOutput = requests.at(-1).input.find(item => item.type === "function_call_output");
  expect(JSON.parse(toolOutput.output)).not.toHaveProperty("answer");
});

test("reading older messages does not force scroll; jump returns to newest output", async ({ page }) => {
  await page.goto("/#/questions");
  await page.getByLabel("发给学习教练").fill("给一个长回答");
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await expect(page.getByRole("button", { name: "发送", exact: true })).toBeVisible();
  await page.locator(".study-transcript").evaluate(el => { el.scrollTop = 0; el.dispatchEvent(new Event("scroll")); });
  await expect(page.getByRole("button", { name: "回到最新消息" })).toBeVisible();
  await page.getByRole("button", { name: "回到最新消息" }).click();
  await expect(page.getByRole("button", { name: "回到最新消息" })).toBeHidden();
  await expect(page.getByLabel("发给学习教练")).toBeInViewport();
});
