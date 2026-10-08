import { test, expect } from "@playwright/test";

const attempts = [
  { id: "reference-mastered", topic: "云计算", domain: "DevOps", stem: "<p>What is cloud computing?</p>",
    answer: "按需提供计算资源。", score: 1, is_correct: false, assessment_mode: "reference", self_assessment: true },
  { id: "reference-review", topic: "浏览器调度", domain: "前端", stem: "Explain the event loop",
    answer: "还有遗漏。", score: 1, is_correct: true, assessment_mode: "reference", self_assessment: false },
  { id: "graded-partial", topic: "哈希容器", domain: "Java", stem: "为什么重写 hashCode？",
    answer: "覆盖了一半评分点。", score: 0.5, is_correct: false, assessment_mode: "rubric", self_assessment: null },
  { id: "graded-correct", topic: "哈希约定", domain: "Java", stem: "相等对象的哈希值应如何？",
    answer: "相同", score: 1, is_correct: true, assessment_mode: "choice", self_assessment: null },
].map((attempt) => ({ ...attempt, created_at: "2026-10-08T01:00:00Z", question_type: "short_answer" }));

async function mockSettings(page) {
  await page.route("**/api/settings", (route) => route.fulfill({ json: { model: "", providers: [] } }));
}

test("records keep self-assessments separate from scored averages and correctness totals", async ({ page }) => {
  await mockSettings(page);
  let history = attempts;
  await page.route("**/api/history?**", (route) => route.fulfill({ json: { attempts: history } }));
  await page.goto("/#/records");
  const summaries = page.locator(".record-summary > div");
  await expect(summaries.filter({ hasText: "完整答对" })).toContainText("1 / 2 题");
  await expect(summaries.filter({ hasText: "评分题平均分" })).toContainText("75 分");
  await expect(summaries.filter({ hasText: "自评已掌握" })).toContainText("1 / 2 题");
  await expect(page.locator(".record-self-assessment")).toHaveCount(2);
  await expect(page.locator(".record-self-assessment").first()).toContainText("已掌握");
  await expect(page.locator(".record-self-assessment").nth(1)).toContainText("需复习");
  await expect(page.locator(".record-self-assessment")).not.toContainText(["得分", "得分"]);
  await expect(page.locator(".records-list")).toContainText("What is cloud computing?");
  await page.getByRole("button", { name: "答对 / 已掌握", exact: true }).click();
  await expect(page.locator(".record-item")).toHaveCount(2);
  await expect(page.locator(".records-list")).toContainText("云计算");
  await expect(page.locator(".records-list")).not.toContainText("浏览器调度");
  history = attempts.slice(0, 2);
  await page.reload();
  await expect(summaries.filter({ hasText: "评分题平均分" })).toContainText("暂无评分题");
  await expect(summaries.filter({ hasText: "完整答对" })).toContainText("0 / 0 题");
});

test("today shows local bank size and independent mastery labels in recent attempts", async ({ page }) => {
  await mockSettings(page);
  await page.route("**/api/dashboard", (route) => route.fulfill({ json: {
    studied_today: 4, total_attempts: 4, due_count: 0, topic_count: 4, question_count: 12_345,
    topics: [], due_topics: [], recent_attempts: attempts,
  } }));
  await page.goto("/#/today");
  await expect(page.locator(".page-heading")).toContainText("本机题库 12,345 道题");
  const recent = page.locator(".activity-row");
  await expect(recent.first().locator(".badge")).toHaveText("已掌握");
  await expect(recent.nth(1).locator(".badge")).toHaveText("需复习");
  await expect(recent.nth(2).locator(".badge")).toHaveText("50 分");
  await expect(recent.nth(3).locator(".badge")).toHaveText("100 分");
});
