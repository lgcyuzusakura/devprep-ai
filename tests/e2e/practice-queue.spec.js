import { test, expect } from "@playwright/test";

test("a two-question topic ends, survives reload, and only repeats when explicitly requested", async ({ page, request }) => {
  const topics = (await (await request.get("/api/knowledge")).json()).topics;
  const topic = topics.find(item => item.question_count === 2 && item.new_question_count === 2);
  expect(topic).toBeTruthy();
  async function answerCurrent() {
    if (await page.getByRole("radio").count()) {
      await page.getByRole("radio").first().check();
      await page.getByRole("button", { name: "提交答案", exact: true }).click();
    } else {
      await page.locator(".written-answer").fill("还不能完整解释，稍后再复习。");
      await page.getByRole("button", { name: "查看要点并自评", exact: true }).click();
      await page.getByRole("button", { name: "保存自评", exact: true }).click();
    }
    await expect(page.getByRole("button", { name: "下一题", exact: true })).toBeVisible();
    await page.getByRole("button", { name: "下一题", exact: true }).click();
    await expect(page.locator(".feedback-panel")).toHaveCount(0);
  }
  await page.goto(`/#/practice?topic=${topic.id}&type=all`);
  await expect(page.locator(".practice-bank-count")).toContainText("可练 2 题");
  await expect(page.locator(".question-stem")).toBeVisible();
  const first = await page.locator(".question-stem").innerText();
  await answerCurrent();
  await expect(page.locator(".question-stem")).toBeVisible();
  const second = await page.locator(".question-stem").innerText();
  expect(second).not.toBe(first);
  await answerCurrent();
  await expect(page.getByRole("heading", { name: "这个范围的新题已做完" })).toBeVisible();
  await expect(page.locator(".question-stem")).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole("heading", { name: "这个范围的新题已做完" })).toBeVisible();
  await page.getByRole("button", { name: "重练本知识点", exact: true }).click();
  const stems = [];
  for (let i = 0; i < 2; i++) {
    await expect(page.locator(".question-stem")).toBeVisible();
    stems.push(await page.locator(".question-stem").innerText());
    await answerCurrent();
  }
  expect(new Set(stems).size).toBe(2);
  await expect(page.getByRole("heading", { name: "本轮已做题已全部重练" })).toBeVisible();
  await page.screenshot({ path: ".cache/practice-audit/completed.png", fullPage: true });
  await page.getByRole("button", { name: `练习整个${topic.domain}方向`, exact: true }).click();
  await expect(page.getByRole("heading", { name: `${topic.domain}混合练习`, exact: true })).toBeVisible();
  await expect(page.locator(".question-stem")).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: ".cache/practice-audit/mixed-mobile.png", fullPage: true, animations: "disabled" });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
});

test("main directory groups topics into banks and starts with actual choice questions", async ({ page, request }) => {
  const banks = (await (await request.get("/api/practice/banks")).json()).banks;
  const java = banks.find(bank => bank.id === "java");
  await page.goto("/#/knowledge");
  await expect(page.getByRole("heading", { name: "专项题库", exact: true })).toBeVisible();
  await expect(page.locator(".knowledge-row")).toHaveCount(0);
  await expect(page.locator(".bank-entry")).toHaveCount(banks.length);
  await expect(page.locator(".bank-entry").filter({ has: page.locator("strong", { hasText: /^Java$/ }) })).toContainText(`${java.choice_count}选择题`);
  await page.screenshot({ path: ".cache/practice-audit/banks-desktop.png", fullPage: true });
  await page.locator(".bank-entry").filter({ has: page.locator("strong", { hasText: /^Java$/ }) }).click();
  await expect(page.getByLabel("题型", { exact: true })).toHaveValue("single_choice");
  await expect(page.getByRole("heading", { name: "Java练习", exact: true })).toBeVisible();
  await expect(page.getByRole("radio").first()).toBeVisible();
  await page.getByLabel("题型", { exact: true }).selectOption("short_answer");
  await expect(page.locator(".written-answer")).toBeVisible();
  await page.goto("/#/knowledge");
  await page.getByRole("button", { name: "查看细分知识点", exact: true }).click();
  await expect(page.locator(".knowledge-row").first()).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "收起细分知识点", exact: true }).click();
  await page.screenshot({ path: ".cache/practice-audit/banks-mobile.png", fullPage: true, animations: "disabled" });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
});
