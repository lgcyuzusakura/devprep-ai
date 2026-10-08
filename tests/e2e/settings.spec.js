import { test, expect } from "@playwright/test";

test("invalid TOML stays editable instead of breaking the settings page", async ({ page }) => {
  await page.goto("/#/settings");
  await expect(page.getByRole("heading", { name: "模型设置", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "TOML", exact: true }).click();
  await page.getByLabel("TOML 模型配置").fill([
    'model = "demo"',
    'model_provider = "broken"',
    "",
    "[model_providers.broken]",
    'name = "Broken"',
    "base_url = 123",
  ].join("\n"));
  await page.getByRole("button", { name: "解析并切换到表单" }).click();
  await expect(page.getByRole("alert")).toContainText("base_url");
  await expect(page.getByLabel("TOML 模型配置")).toBeVisible();
  await expect(page.getByRole("heading", { name: "模型设置", exact: true })).toBeVisible();
});

test("TOML form defaults match the backend for an unauthenticated gateway", async ({ page }) => {
  await page.goto("/#/settings");
  await expect(page.getByRole("heading", { name: "模型设置", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "TOML", exact: true }).click();
  await page.getByLabel("TOML 模型配置").fill([
    'model = "local-model"',
    'model_provider = "local"',
    "",
    "[model_providers.local]",
    'name = "Local gateway"',
    'base_url = "http://127.0.0.1:9/v1"',
  ].join("\n"));
  await page.getByRole("button", { name: "解析并切换到表单" }).click();
  await expect(page.getByLabel("API Base URL", { exact: true })).toHaveValue("http://127.0.0.1:9/v1");
  await expect(page.getByLabel("API Key 环境变量", { exact: true })).toHaveValue("");
  await expect(page.locator('input[type="number"]').first()).toHaveValue("15");
});

test("save retry keeps a TOML draft after backend validation fails", async ({ page }) => {
  await page.goto("/#/settings");
  await expect(page.getByRole("heading", { name: "模型设置", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "TOML", exact: true }).click();
  const draft = [
    'model = "draft-model"',
    'model_provider = "draft"',
    "",
    "[model_providers.draft]",
    'name = "Draft"',
    'base_url = "not-a-url"',
  ].join("\n");
  await page.getByLabel("TOML 模型配置").fill(draft);
  await page.getByRole("button", { name: "保存设置" }).click();
  await expect(page.getByRole("alert")).toContainText("base_url");
  await expect(page.getByLabel("TOML 模型配置")).toHaveValue(draft);
  await page.getByRole("button", { name: "重试" }).click();
  await expect(page.getByRole("alert")).toContainText("base_url");
  await expect(page.getByLabel("TOML 模型配置")).toHaveValue(draft);
});
