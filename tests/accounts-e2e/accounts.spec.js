import { test, expect } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { createServer } from "node:http";

test("login, password change, shared-browser drafts, administration and mobile", async ({ page, browser }) => {
  mkdirSync(".cache/accounts-qa", { recursive: true });
  const login = async (name, password) => {
    await page.getByLabel("用户名", { exact: true }).fill(name);
    await page.getByLabel("密码", { exact: true }).fill(password);
    await page.getByRole("button", { name: "登录", exact: true }).click();
  };
  await page.goto("/#/today");
  await expect(page.getByRole("heading", { name: "回到你的学习工作区" })).toBeVisible();
  await page.screenshot({ path: ".cache/accounts-qa/login.png" });
  await login("owner", "owner-test-password");
  await expect(page.getByRole("link", { name: "模型设置", exact: true })).toBeVisible();
  // Legacy private drafts can only be inherited by the owner.
  await page.evaluate(() => localStorage.setItem("devprep.chat.draft.new", "仅属于管理员的旧草稿"));
  await page.goto("/#/questions");
  await expect(page.locator("textarea")).toHaveValue("仅属于管理员的旧草稿");
  await page.goto("/#/account");
  await expect(page.getByRole("heading", { name: "朋友体验账号" })).toBeVisible();
  await page.getByRole("button", { name: "退出登录" }).click();
  await login("guest", "guest-test-password");
  await expect(page.getByRole("heading", { name: "先设置你自己的密码" })).toBeVisible();
  await page.getByLabel("当前密码", { exact: true }).fill("guest-test-password");
  await page.getByLabel(/^新密码/).fill("guest-updated-password");
  await page.getByLabel("再输入一次新密码", { exact: true }).fill("guest-updated-password");
  await page.getByRole("button", { name: "保存并进入工作区" }).click();
  await expect(page.getByRole("link", { name: "我的账号", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "模型设置", exact: true })).toHaveCount(0);
  await page.goto("/#/questions");
  await expect(page.locator("textarea")).toHaveValue("");
  await page.locator("textarea").fill("朋友自己的草稿");
  await page.goto("/#/account");
  await page.getByRole("button", { name: "退出登录" }).click();
  await login("owner", "owner-test-password");
  await page.goto("/#/questions");
  await expect(page.locator("textarea")).toHaveValue("仅属于管理员的旧草稿");
  await page.goto("/#/account");
  await page.getByLabel(/^新用户名/).fill("visitor");
  await page.getByLabel("初始密码", { exact: true }).fill("visitor-test-password");
  await page.getByRole("button", { name: "创建体验账号" }).click();
  await expect(page.locator(".account-list")).toContainText("visitor");
  await page.getByRole("combobox", { name: /^选择账号/ }).selectOption("guest");
  await page.getByLabel("新的初始密码", { exact: true }).fill("guest-reset-password");
  await page.getByRole("button", { name: "重置密码", exact: true }).click();
  await expect(page.locator(".account-list li").filter({ hasText: "guest" })).toContainText("待修改初始密码");
  await page.screenshot({ path: ".cache/accounts-qa/account.png", fullPage: true });
  // A second browser has its own session; logging in there leaves the owner signed in.
  const second = await browser.newContext({ baseURL: "http://127.0.0.1:8877" });
  const response = await second.request.post("/api/auth/login", { data: { username: "guest", password: "guest-reset-password" } });
  expect(response.ok()).toBeTruthy();
  expect((await (await second.request.get("/api/auth/me")).json()).user.username).toBe("guest");
  expect((await (await page.request.get("/api/auth/me")).json()).user.username).toBe("owner");
  await second.close();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: ".cache/accounts-qa/mobile.png", fullPage: true, animations: "disabled" });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
});

test("streaming worker uses the friend's model and saves only to the friend's workspace", async ({ playwright }) => {
  const seen = [];
  const provider = createServer(async (req, res) => {
    let raw = ""; for await (const part of req) raw += part;
    seen.push(JSON.parse(raw));
    res.writeHead(200, { "Content-Type": "text/event-stream" });
    const reply = { type: "response.completed", response: { status: "completed", output: [
      { type: "message", content: [{ type: "output_text", text: "仅朋友可见的测试回答" }] },
    ] } };
    res.end(`data: ${JSON.stringify(reply)}\n\n`);
  });
  await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));
  const owner = await playwright.request.newContext({ baseURL: "http://127.0.0.1:8877" });
  const friend = await playwright.request.newContext({ baseURL: "http://127.0.0.1:8877" });
  try {
    expect((await owner.post("/api/auth/login", { data: { username: "owner", password: "owner-test-password" } })).ok()).toBeTruthy();
    expect((await owner.post("/api/auth/accounts", { data: { username: "streamguest", password: "stream-test-password" } })).ok()).toBeTruthy();
    expect((await owner.post("/api/settings", { data: { toml: `model = "owner-fixture"\nmodel_provider = "fixture"\n[model_providers.fixture]\nname = "Local test"\nbase_url = "http://127.0.0.1:${provider.address().port}/v1"\nwire_api = "responses"\n` } })).ok()).toBeTruthy();
    await friend.post("/api/auth/login", { data: { username: "streamguest", password: "stream-test-password" } });
    expect((await friend.post("/api/auth/password", { data: { current_password: "stream-test-password", new_password: "stream-updated-password" } })).ok()).toBeTruthy();
    const session = await (await friend.post("/api/study/sessions", { data: { mode: "mentor" } })).json();
    const stream = await friend.post(`/api/study/sessions/${session.id}/messages`, { data: { content: "讲解测试知识" } });
    expect(stream.ok()).toBeTruthy();
    expect(await stream.text()).toContain("仅朋友可见的测试回答");
    expect(seen).toHaveLength(1);
    expect(seen[0].model).toBe("gpt-6.1-sol");
    expect((await (await owner.get("/api/settings")).json()).model).toBe("owner-fixture");
    expect((await owner.get(`/api/study/sessions/${session.id}`)).status()).toBe(404);
    const detail = await (await friend.get(`/api/study/sessions/${session.id}`)).json();
    expect(detail.messages.at(-1).content).toContain("仅朋友可见的测试回答");
  } finally {
    await owner.dispose(); await friend.dispose();
    provider.closeAllConnections(); await new Promise(resolve => provider.close(resolve));
  }
});
