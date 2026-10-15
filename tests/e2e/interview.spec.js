import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { mkdirSync } from 'node:fs';

// Local fixture exercises Spring Boot and its gateway without paid model calls.
let provider, url, calls, invalidNext;
const answer = '用唯一约束保护重复请求，并测试并发下的状态变化。';
test.beforeAll(async () => {
  provider = createServer(async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk;
    const body = JSON.parse(raw); calls.push(body);
    const context = JSON.parse(body.input[0].content);
    let result;
    if (body.instructions.includes('当前任务：面试组卷')) {
      result = { focus: ['项目职责、幂等与失败恢复'], questions: context.slots.map((slot, index) => ({
        kind: slot.kind, title: '订单系统的设计取舍',
        prompt: `结合你亲手实现的订单系统，说明如何处理重复请求以及失败恢复？考查 ${index + 1}：${slot.kind}`,
        rubric: ['解释个人实现', '说明失败路径'], reference: '定义重复请求与事务边界，再说明验证方法。', bank_id: '',
      })) };
    } else {
      result = { dimensions: {
        correctness: { score: 30, reason: '有幂等控制思路' }, depth: { score: 18, reason: '还需说明失败时序' },
        clarity: { score: 12, reason: '结论清楚' }, engineering: { score: 15, reason: '提出测试但没有负载条件' },
      }, strengths: ['指出唯一约束'], gaps: ['缺少失败时序和测试负载'],
      feedback: '补充事务失败后的恢复过程和压测条件。',
      sample_answer: '先定义重复请求，再说明唯一约束、事务边界和异常处理。',
      evidence: context.candidate_answer.includes('唯一约束') ? '唯一约束' : '',
      followup: context.allow_followup ? { title: '重复请求失败路径', prompt: '唯一约束冲突后，如何恢复响应并避免再次扣减库存？', rubric: ['异常恢复', '副作用幂等'], reference: '解释事务和幂等记录的状态。' } : null };
    }
    const text = invalidNext ? 'invalid JSON' : JSON.stringify(result); invalidNext = false;
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ output: [{ type: 'message', content: [{ type: 'output_text', text }] }] }));
  });
  await new Promise(resolve => provider.listen(0, '127.0.0.1', resolve));
  url = 'http://127.0.0.1:' + provider.address().port + '/v1';
});
test.afterAll(async () => { provider.closeAllConnections(); await new Promise(resolve => provider.close(resolve)); });
test.beforeEach(async ({ request }) => {
  calls = []; invalidNext = false;
  await request.post('/api/settings', { data: { toml: 'model="fixture-gpt"\nmodel_provider="fixture"\n[model_providers.fixture]\nname="Local fixture"\nbase_url="' + url + '"\nwire_api="responses"\n' } });
});
async function setup(page, title) {
  await page.goto('/#/interview');
  await page.getByLabel('面试名称', { exact: true }).fill(title);
  await page.getByLabel('主问题数量').fill('3');
  await page.getByLabel('追问强度').selectOption('gentle');
}

test('offline bank starts Chinese questions, preserves drafts, skips and exports history', async ({ page, request }) => {
  await request.post('/api/settings', { data: { toml: 'model=""\nmodel_provider=""\n' } });
  await setup(page, '离线面试测试');
  await page.getByRole('button', { name: '开始面试' }).click();
  await expect(page.getByText('面试进行中')).toBeVisible();
  await expect(page.locator('.interview-turn').first()).toContainText(/[\u4e00-\u9fff]/);
  await page.getByLabel('面试回答').fill(answer); await page.reload();
  await expect(page.getByLabel('面试回答')).toHaveValue(answer);
  await page.getByRole('button', { name: '提交回答' }).click();
  await expect(page.getByText(/先在模型设置中配置 GPT/)).toBeVisible();
  await expect(page.locator('.interview-turn')).toHaveCount(1);
  page.on('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: '跳过本题' }).click();
  await expect(page.locator('.interview-turn')).toHaveCount(2);
  await page.getByRole('button', { name: '结束并复盘' }).click();
  await expect(page.getByRole('heading', { name: '这场面试的复盘' })).toBeVisible();
  await expect(page.locator('.interview-report')).toContainText('0 题已评分，1 题跳过，2 题未作答');
  const download = page.waitForEvent('download');
  await page.getByRole('link', { name: /导出复盘 Markdown/ }).click();
  expect((await download).suggestedFilename()).toMatch(/\.md$/);
  await page.getByRole('button', { name: '新建面试' }).click();
  await page.getByRole('button', { name: /离线面试测试/ }).click();
  await expect(page.getByRole('heading', { name: '这场面试的复盘' })).toBeVisible();
  expect(calls).toHaveLength(0);
});
test('difficulty and company settings stay independent and are saved for grading', async ({ page, request }) => {
  await setup(page, '基础大厂校招训练');
  await page.getByLabel('候选人阶段').selectOption('none');
  await page.getByLabel('面试难度').selectOption('basic');
  await page.getByLabel('练习场景').selectOption('large');
  await expect(page.getByText(/离线面试素材：48 条/)).toBeVisible();
  await page.getByRole('button', { name: '开始面试' }).click();
  await expect(page.getByText('面试进行中')).toBeVisible();
  const id = new URL(page.url()).hash.split('session=')[1];
  const session = await (await request.get('/api/interviews/' + id)).json();
  expect(session.difficulty_resolved).toBe('basic'); expect(session.company_style).toBe('large');
  expect(session.difficulty_standard.criteria).toContain('不要求生产经验');
  expect(session.turns[0].difficulty).toBe('basic'); expect(session.turns[0].source_kind).toBe('original');
  await page.getByText('本场难度与判分标准').click();
  await expect(page.getByText(/基础：能解释概念、完成简单应用/)).toBeVisible();
});

test('JD interview grades four dimensions and follows project answers', async ({ page }) => {
  await setup(page, 'JD 项目深挖测试');
  await page.getByRole('button', { name: 'JD 定向面试', exact: true }).click();
  await page.getByLabel('目标岗位 JD', { exact: true }).fill('Java校招：订单系统、Redis、SQL、幂等处理和测试。');
  await page.getByLabel('项目经历 / 原始简历').fill('本人实现课程订单系统，使用唯一约束保护幂等。');
  await page.getByRole('checkbox', { name: '八股与场景' }).uncheck();
  await page.getByRole('checkbox', { name: '算法', exact: true }).uncheck();
  await page.getByLabel('追问强度').selectOption('deep');
  await page.getByLabel('反馈时机').selectOption('after_each');
  await page.getByRole('button', { name: '开始面试' }).click();
  await expect(page.getByRole('heading', { name: '订单系统的设计取舍' })).toBeVisible();
  await page.getByLabel('面试回答').fill(answer); await page.getByRole('button', { name: '提交回答' }).click();
  await expect(page.getByRole('heading', { name: '重复请求失败路径' })).toBeVisible();
  await page.locator('.interview-evaluation summary').click();
  await expect(page.locator('.interview-dimensions')).toContainText('正确性');
  await expect(page.locator('.interview-dimensions')).toContainText('工程判断');
  await expect(page.locator('.interview-evaluation')).toContainText('75 / 100');
  await expect(page.locator('.interview-evaluation blockquote')).toContainText('唯一约束');
  const context = JSON.parse(calls.at(-1).input[0].content);
  expect(context.config.jd).toContain('Java校招'); expect(context.config.resume).toContain('课程订单系统');
  expect(context.candidate_answer).toBe(answer); expect(calls.at(-1).parallel_tool_calls).toBe(false);
  await page.getByLabel('面试回答').fill(answer); await page.getByRole('button', { name: '提交回答' }).click();
  await expect(page.getByText('主问题 1 / 3 · 追问 2', { exact: true })).toBeVisible();
  await page.getByLabel('面试回答').fill(answer); await page.getByRole('button', { name: '提交回答' }).click();
  await expect(page.getByText('主问题 2 / 3', { exact: true })).toBeVisible();
  page.on('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: '结束并复盘' }).click();
  await expect(page.locator('.interview-report-score')).toContainText('75');
  await expect(page.locator('.interview-report')).toContainText('缺少失败时序和测试负载');
  mkdirSync('.cache/interview-qa', { recursive: true });
  await page.screenshot({ path: '.cache/interview-qa/desktop.png', fullPage: true });
});

test('invalid model JSON preserves answer and revision then permits manual retry', async ({ page, request }) => {
  await setup(page, '无效输出重试测试'); await page.getByRole('button', { name: '开始面试' }).click();
  await expect(page.getByLabel('面试回答')).toBeVisible();
  const id = new URL(page.url()).hash.split('session=')[1];
  const before = await (await request.get('/api/interviews/' + id)).json(); invalidNext = true;
  await page.getByLabel('面试回答').fill(answer); await page.getByRole('button', { name: '提交回答' }).click();
  await expect(page.getByText(/面试结果未通过检查/)).toBeVisible();
  expect((await (await request.get('/api/interviews/' + id)).json()).revision).toBe(before.revision);
  await expect(page.getByLabel('面试回答')).toHaveValue(answer); expect(calls).toHaveLength(1);
  await page.getByRole('button', { name: '提交回答' }).click();
  await expect(page.locator('.interview-turn')).toHaveCount(2); expect(calls).toHaveLength(2);
});

test('full interview reports three categories and fits mobile', async ({ page, request }) => {
  await setup(page, '三类题完整面试'); await page.getByRole('button', { name: '开始面试' }).click();
  for (let i = 0; i < 3; i += 1) {
    await expect(page.getByLabel('面试回答')).toBeVisible();
    await page.getByLabel('面试回答').fill(answer); await page.getByRole('button', { name: '提交回答' }).click();
    if (i < 2) await expect(page.locator('.interview-turn')).toHaveCount(i + 2);
  }
  await expect(page.getByRole('heading', { name: '这场面试的复盘' })).toBeVisible();
  const id = new URL(page.url()).hash.split('session=')[1];
  const report = (await (await request.get('/api/interviews/' + id)).json()).report;
  expect(report.score).toBe(75); expect(report.categories.every(group => group.count === 1)).toBe(true);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: '.cache/interview-qa/mobile.png', fullPage: true });
  await page.getByRole('button', { name: '新建面试' }).click();
  await expect(page.getByRole('button', { name: '开始面试' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

