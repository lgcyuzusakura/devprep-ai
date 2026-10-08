import { test, expect } from "@playwright/test";

const question = {
  id: "local-event-loop",
  knowledge_point_id: "frontend-event-loop",
  topic_title: "浏览器事件循环",
  topic_summary: "理解任务调度、微任务与渲染时机。",
  domain: "前端",
  question_type: "short_answer",
  stem: "<b>Explain the event loop</b>\n\nDescribe scheduling order.\n\n" +
    "~~~js\nconst sample = '<b>keep literal</b>';\n~~~\n\n" +
    "<script>window.practiceUnsafe = true</script>",
  options: [],
  source_type: "开源原文",
  source_material_id: "",
  source_name: "Interview handbook",
  source_url: "https://github.com/example/handbook/blob/pinned-revision/docs/questions.md",
  source_path: "docs/questions.md",
  source_license: "MIT",
  source_attribution: "Handbook contributors",
  source_revision: "pinned-revision",
  source_sha256: "a".repeat(64),
  source_locale: "en",
  content_format: "verbatim-html-markdown",
  assessment_mode: "reference",
  status: "new",
  reason: "本地题库中尚未练习的题目",
};

const reference = {
  answer: "<p>保留的原文段落：<code>git reset</code> 与回滚不同。</p>\n\n### Scheduling\n\n- A task runs first.\n- Microtasks run before rendering.\n\n" +
    "~~~js\nqueueMicrotask(() => console.log('microtask'));\n~~~\n\n" +
    "![调度示意图](https://images.example.invalid/scheduling.png)\n\n" +
    "[Specification](./event-loop.md)\n\n[Unsafe link](javascript:alert(1))\n\n" +
    "<img src=\"https://images.example.invalid/unsafe.png\" onerror=\"window.practiceUnsafe = true\">",
  explanation: "对照原文补充遗漏的调度规则。",
  rubric: [],
  content_format: "verbatim-html-markdown",
  assessment_mode: "reference",
};

async function mockPractice(page, { questionData = question, referenceData = reference, correct = false } = {}) {
  const submissions = [];
  await page.route("**/api/settings", (route) => route.fulfill({ json: { model: "", providers: [] } }));
  await page.route("**/api/practice/next**", (route) => route.fulfill({ json: questionData }));
  await page.route("**/api/practice/reference?**", (route) => route.fulfill({ json: referenceData }));
  await page.route("**/api/attempts", async (route) => {
    submissions.push(route.request().postDataJSON());
    await route.fulfill({ status: 201, json: {
      correct, score: correct ? 1 : 0, answer: referenceData.answer,
      explanation: referenceData.explanation, rubric: referenceData.rubric,
      content_format: referenceData.content_format, assessment_mode: referenceData.assessment_mode,
      self_assessment: route.request().postDataJSON().self_assessment ?? null,
      review_at: "2026-10-09T00:00:00Z", topic_title: questionData.topic_title,
      streak: correct ? 1 : 0, review_hours: 24,
    } });
  });
  return submissions;
}

test("original Markdown questions render safely and save an independent needs-review self-assessment", async ({ page }) => {
  const errors = [];
  const imageRequests = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => {
    if (request.url().startsWith("https://images.example.invalid/")) imageRequests.push(request.url());
  });
  const submissions = await mockPractice(page);
  await page.goto("/#/practice?topic=frontend-event-loop");

  await expect(page.locator(".question-stem")).toContainText("Explain the event loop");
  await expect(page.getByRole("heading", { name: /Explain the event loop/ })).toBeVisible();
  await expect(page.locator(".question-stem pre code")).toContainText("<b>keep literal</b>");
  await expect(page.locator(".question-stem b")).toHaveCount(0);
  await expect(page.locator("main script, main img")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "查看参考资料", exact: true })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "查看题目原文" })).toHaveAttribute("href", question.source_url);
  const source = page.locator(".question-source");
  await expect(source).toContainText("MIT");
  await expect(source).toContainText("Handbook contributors");
  await expect(source).toContainText("docs/questions.md");
  await expect(source).toContainText("英文");
  await source.locator("summary").click();
  await expect(source).toContainText(question.source_sha256);
  await expect(page.locator("footer")).toContainText("计算机学习 · 考试与面试");

  await page.getByLabel("你的回答").fill("我记得任务执行后需要清空微任务队列，但还不清楚渲染时机。");
  await page.getByRole("button", { name: "查看参考答案", exact: true }).click();
  await expect(page.locator(".reference-panel li")).toHaveCount(2);
  await expect(page.locator(".reference-panel pre code")).toContainText("queueMicrotask");
  await expect(page.locator(".reference-panel")).toContainText("保留的原文段落");
  await expect(page.locator(".reference-panel code").filter({ hasText: "git reset" })).toBeVisible();
  await expect(page.getByRole("link", { name: "查看原图" })).toHaveAttribute("href", "https://images.example.invalid/scheduling.png");
  await expect(page.getByRole("link", { name: "Specification" })).toHaveAttribute("href", "https://github.com/example/handbook/blob/pinned-revision/docs/event-loop.md");
  await expect(page.getByRole("link", { name: "Unsafe link" })).toHaveCount(0);
  await expect(page.getByRole("checkbox")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "保存自评" })).toBeDisabled();
  expect(await page.evaluate(() => window.practiceUnsafe)).toBeUndefined();

  await page.getByRole("radio", { name: /需复习/ }).check();
  await page.getByRole("button", { name: "保存自评" }).click();
  await expect(page.getByText("自评：需复习", { exact: true })).toBeVisible();
  await expect(page.locator(".feedback-heading")).toContainText("对照原文自评");
  await expect(page.locator(".feedback-heading")).not.toContainText("分");
  expect(submissions).toEqual([{
    question_id: question.id, answer: "我记得任务执行后需要清空微任务队列，但还不清楚渲染时机。",
    rubric_hits: [], self_assessment: false,
  }]);
  expect(imageRequests).toEqual([]);
  expect(errors).toEqual([]);
});

test("editing and moving to the next original question reset self-assessment on a narrow viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const submissions = await mockPractice(page, { correct: true });
  await page.goto("/#/practice?topic=frontend-event-loop");
  await page.getByLabel("你的回答").fill("任务完成后执行微任务。");
  await page.getByRole("button", { name: "查看参考答案", exact: true }).click();
  await page.getByRole("radio", { name: /^掌握/ }).check();
  await page.getByRole("button", { name: "继续修改" }).click();
  await expect(page.getByLabel("你的回答")).toBeEnabled();
  await page.getByLabel("你的回答").fill("任务结束后执行微任务队列，再进入后续渲染步骤。");
  await page.getByRole("button", { name: "查看参考答案", exact: true }).click();
  await expect(page.getByRole("radio", { name: /^掌握/ })).not.toBeChecked();
  await expect(page.getByRole("radio", { name: /需复习/ })).not.toBeChecked();
  await expect(page.getByRole("button", { name: "保存自评" })).toBeDisabled();
  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth, content: document.documentElement.scrollWidth,
  }));
  expect(dimensions.content).toBeLessThanOrEqual(dimensions.viewport);

  await page.getByRole("radio", { name: /^掌握/ }).check();
  await page.getByRole("button", { name: "保存自评" }).click();
  await expect(page.getByText("自评：已掌握", { exact: true })).toBeVisible();
  expect(submissions[0].self_assessment).toBe(true);
  await page.getByRole("button", { name: "下一题", exact: true }).click();
  await expect(page.getByLabel("你的回答")).toHaveValue("");
  await expect(page.locator(".reference-panel")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "查看参考答案", exact: true })).toBeDisabled();
});

test("rubric questions retain checkbox scoring and open their own source material", async ({ page }) => {
  const questionData = {
    ...question, id: "java-rubric", domain: "Java 基础", topic_title: "哈希容器",
    stem: "为什么要重写 hashCode？", content_format: "markdown", assessment_mode: "rubric",
    source_material_id: "material-hash-notes", source_name: "课程笔记", source_url: "",
    source_path: "", source_license: "", source_attribution: "", source_revision: "", source_sha256: "", source_locale: "zh-CN",
  };
  const referenceData = {
    answer: "相等对象应有相同哈希值。", explanation: "哈希容器按哈希值定位桶。",
    rubric: ["相等对象哈希值相同", "说明桶定位机制"], content_format: "plain", assessment_mode: "rubric",
  };
  const submissions = await mockPractice(page, { questionData, referenceData, correct: false });
  await page.goto("/#/practice?topic=hash");
  await expect(page.getByRole("heading", { name: questionData.stem, exact: true })).toBeVisible();
  await page.getByLabel("你的回答").fill("相等对象的 hashCode 应该相同。");
  await page.getByRole("button", { name: "查看要点并自评", exact: true }).click();
  await expect(page.getByRole("checkbox")).toHaveCount(2);
  await page.getByRole("checkbox", { name: "相等对象哈希值相同" }).check();
  await expect(page.getByRole("radio")).toHaveCount(0);
  await page.getByRole("button", { name: "保存自评" }).click();
  await expect(page.getByText("还需要巩固", { exact: true })).toBeVisible();
  expect(submissions[0].rubric_hits).toEqual([true, false]);
  expect(submissions[0]).not.toHaveProperty("self_assessment");
  await page.getByRole("button", { name: "查看参考资料", exact: true }).click();
  await expect(page).toHaveURL(/#\/materials\?id=material-hash-notes$/);
});

test("unsafe source URLs never become external navigation links", async ({ page }) => {
  await mockPractice(page, { questionData: { ...question, source_url: "javascript:alert(1)",
    source_license: null, source_attribution: null, source_revision: null, source_locale: null,
    license: "MIT", attribution: "Legacy contributor", revision: "legacy-version", locale: "zh-CN" } });
  await page.goto("/#/practice");
  await expect(page.locator(".question-source")).toContainText("Interview handbook");
  await expect(page.getByRole("link", { name: "查看题目原文" })).toHaveCount(0);
  await expect(page.locator(".question-source")).toContainText("Legacy contributor");
  await expect(page.locator(".question-source")).toContainText("MIT");
});

test("exam formulas and both table formats render while code and image isolation remain intact", async ({ page }) => {
  const imageRequests = [];
  page.on("request", (request) => {
    if (request.url().startsWith("https://images.example.invalid/")) imageRequests.push(request.url());
  });
  const formulaQuestion = {
    ...question, id: "exam-resources", question_type: "single_choice", assessment_mode: "choice",
    domain: "考研 408",
    stem: "资源分配中，进程 $P_1$ 需要哪些资源？\n\n" +
      "<table><tr><td rowspan=\"2\">进程</td><td colspan=\"2\">资源</td></tr>" +
      "<tr><td>$R_1$</td><td>$R_2$</td></tr><tr><td>$P_1$</td><td>1</td><td>2</td></tr></table>",
    options: ["$1\\mu\\mathrm{s}$", "$a/b+(c*d-e*f)/g$"],
  };
  const formulaReference = {
    answer: formulaQuestion.options[0], rubric: [], assessment_mode: "choice", content_format: "verbatim-html-markdown",
    explanation: "资源数量：\n\n| 资源 | 数量 |\n| --- | --- |\n| $R_1$ | 3 |\n\n" +
      "~~~text\n$P_1$ <b>do not strip code</b>\n~~~\n\n" +
      "$\\includegraphics{https://images.example.invalid/formula.png}$",
  };
  await mockPractice(page, { questionData: formulaQuestion, referenceData: formulaReference, correct: true });
  await page.goto("/#/practice");
  await expect(page.locator(".question-stem .katex").first()).toBeVisible();
  await expect(page.locator(".question-stem table tr")).toHaveCount(3);
  await expect(page.locator(".question-stem th").first()).toHaveAttribute("rowspan", "2");
  await expect(page.locator(".question-stem th").nth(1)).toHaveAttribute("colspan", "2");
  await expect(page.locator(".question-stem table")).toContainText("进程");
  await expect(page.locator(".question-stem table .katex")).toHaveCount(3);
  await expect(page.locator(".answer-options .katex")).toHaveCount(2);
  await page.getByRole("radio").first().check();
  await page.getByRole("button", { name: "提交答案", exact: true }).click();
  await expect(page.getByText("回答正确", { exact: true })).toBeVisible();
  await expect(page.locator(".feedback-panel table")).toContainText("数量");
  await expect(page.locator(".feedback-panel table td").last()).toHaveText("3");
  await expect(page.locator(".feedback-panel pre code")).toContainText("$P_1$ <b>do not strip code</b>");
  await expect(page.locator(".feedback-panel pre .katex")).toHaveCount(0);
  await expect(page.locator("main img")).toHaveCount(0);
  expect(imageRequests).toEqual([]);
});
