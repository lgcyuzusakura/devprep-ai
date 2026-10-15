export const DEFAULT_LAYOUT = { template: "classic", font: "sans", density: "normal", accent: "green", size: 13 };
export const TEMPLATES = [
  { id: "classic", name: "工程标准", description: "单栏、清晰分节，适合技术岗位投递。", layout: DEFAULT_LAYOUT },
  { id: "compact", name: "一页紧凑", description: "压缩段间留白，适合校招与项目密集的经历。", layout: { ...DEFAULT_LAYOUT, template: "compact", density: "compact", size: 12, accent: "black" } },
  { id: "serif", name: "书面叙事", description: "宋体正文、居中姓名，适合研究和综合岗位。", layout: { ...DEFAULT_LAYOUT, template: "serif", font: "serif", accent: "black" } },
  { id: "technical", name: "技术项目", description: "蓝色章节标记，优先展示工程实现与技术取舍。", layout: { ...DEFAULT_LAYOUT, template: "technical", accent: "blue" } },
  { id: "minimal", name: "极简文字", description: "减少分隔线，让内容和层级自己说话。", layout: { ...DEFAULT_LAYOUT, template: "minimal", accent: "black", density: "relaxed" } },
];

export const SECTIONS = ["项目经历", "专业技能", "工作与实习", "教育背景", "校园经历", "开源与成果", "补充信息"];
export const SECTION_HINTS = {
  项目经历: "### 项目名称 · 个人职责 · 时间\n- 业务问题或使用场景：\n- 个人实现、关键技术与设计取舍：\n- 交付结果与验证方法：",
  专业技能: "- 实际使用过的技术及对应场景：",
  工作与实习: "### 单位 · 岗位 · 时间\n- 个人负责的具体任务：\n- 交付物、问题解决与团队协作：",
  教育背景: "学校 · 专业 · 学历 · 起止时间\n- 与岗位相关的课程或成果（可选）：",
  校园经历: "组织或活动 · 角色 · 时间\n- 个人贡献与具体结果：",
  开源与成果: "- 仓库、作品或论文链接；个人贡献：",
  补充信息: "- 证书、语言能力或其他相关信息：",
};
export function blankResume(sections = SECTIONS.slice(0, 5)) {
  return "# 待填写姓名\n意向岗位 · 手机 · 邮箱 · GitHub/作品链接\n\n" + sections.map(s => `## ${s}\n${SECTION_HINTS[s] || "填写具体经历与交付。"}`).join("\n\n");
}
// AI-assisted original fictional examples. These illustrate writing choices, not real candidates.
export const EXAMPLES = [
  { id: "java", direction: "Java 后端", title: "校招：订单与缓存项目", note: "把课程项目边界写清，突出幂等、缓存和测量口径。", markdown: `# 陈同学（虚构示例）
Java 后端开发 · 联系方式自行填写
## 项目经历
### 校园二手交易服务 · 个人课程项目
- 使用 Spring Boot、MySQL 实现商品发布、订单创建和交易状态流转，以唯一索引和请求幂等键避免重复提交生成重复订单。
- 针对商品详情读多写少的访问特点引入 Redis 缓存，采用缓存旁路模式与过期时间抖动；为失效场景保留数据库回源路径。
- 在本机固定测试数据、相同负载下，用 JMeter 对比改造前后结果：查询接口 P95 延迟从 210ms 降至 135ms；该值仅代表测试环境。
## 专业技能
- Java、Spring Boot、MySQL、Redis；能结合项目解释事务边界、索引选择和缓存一致性取舍。
## 教育背景
某大学 · 计算机科学与技术 · 本科
## 校园经历
- 为课程团队维护接口文档与测试用例，整理订单异常状态的复现步骤。` },
  { id: "go", direction: "Go 后端", title: "工程实践：异步任务与服务恢复", note: "用失败处理和可观测性体现工程能力，避免泛称高并发。", markdown: `# 林同学（虚构示例）
Go 后端开发
## 项目经历
### 文件处理任务服务 · 个人项目
- 使用 Go、Gin 和 PostgreSQL 实现任务提交、状态查询与结果下载，将耗时处理移出 HTTP 请求路径。
- 以任务状态机和租约控制重复执行，工作进程退出后允许超时任务重新领取；设计幂等输出路径，避免重试覆盖成功结果。
- 为队列堆积、执行耗时和失败原因提供 Prometheus 指标；用故障注入覆盖进程退出、超时和重复消息场景。
## 专业技能
- Go 的 context 取消、goroutine 生命周期、数据库事务；实际编写过服务关闭与任务重试逻辑。
## 教育背景
某大学 · 软件工程 · 本科` },
  { id: "frontend", direction: "前端", title: "实习：后台交互与性能", note: "说明具体页面、测量场景、交互边界和个人交付。", markdown: `# 周同学（虚构示例）
前端开发
## 工作与实习
### 某团队 · 前端实习生（虚构）
- 使用 React、TypeScript 实现工单筛选、批量处理与详情面板，将查询条件同步到 URL，使分享链接和浏览器回退可恢复筛选状态。
- 针对长列表滚动卡顿采用虚拟列表，补充键盘操作、空状态和失败重试；避免筛选切换时旧请求覆盖新结果。
- 在同一设备、浏览器与测试数据下记录优化结果：5000 条数据的首次渲染从 1.8s 降至 0.6s；测量范围不包含后端网络耗时。
## 专业技能
- React、TypeScript、CSS、浏览器调试；通过实际页面处理请求竞态和组件状态复用。
## 教育背景
某大学 · 计算机相关专业 · 本科` },
  { id: "python", direction: "Python", title: "数据工具：可恢复的数据流程", note: "没有漂亮百分比也能写出清晰交付和问题解决。", markdown: `# 许同学（虚构示例）
Python 开发
## 项目经历
### 公开数据整理工具 · 个人项目
- 使用 Python、Pandas 整理多个公开数据源，统一字段与时间格式，将解析失败的记录写入隔离文件，保留来源与错误原因。
- 实现增量处理和运行检查点，中断后从已完成文件继续执行；为重复输入采用内容摘要去重。
- 为缺失字段、编码异常和重复数据编写回归样例，交付命令行工具、数据字典及可复现的运行说明。
## 专业技能
- Python、Pandas、SQL；能解释数据清洗规则、异常隔离和幂等处理。
## 教育背景
某大学 · 信息相关专业 · 本科` },
  { id: "ai", direction: "AI 应用", title: "RAG：检索评估与引用", note: "区分检索指标、回答质量与线上效果，展示评估设计。", markdown: `# 赵同学（虚构示例）
AI 应用开发
## 项目经历
### 课程资料问答助手 · 个人项目
- 使用 FastAPI 构建资料导入、分块、向量检索与问答接口，回答保留章节引用，材料不足时提示缺少依据。
- 自建 80 条课程问答评估集，在相同语料快照下对比纯向量检索与混合检索；Top-5 命中率从 68% 提升到 79%，另行记录回答引用是否支持结论。
- 对提示注入文本、空文档和检索失败设计降级路径；把模型地址、密钥与模型名从代码移到配置中。
## 专业技能
- Python、FastAPI、检索增强生成、评估集设计；能讨论分块、召回和答案证据的取舍。
## 教育背景
某大学 · 计算机相关专业 · 本科` },
  { id: "agent", direction: "Agent", title: "学习助手：工具与可控执行", note: "突出状态、工具边界、停止行为与失败恢复。", markdown: `# 吴同学（虚构示例）
Agent 应用开发
## 项目经历
### 学习对话助手 · 个人工程项目
- 用 Spring Boot 实现持久会话、流式消息和工具调用循环，提供资料检索、出题与学习记录工具。
- 将工具参数校验、最大轮次和取消信号纳入执行流程；模型请求串行处理，避免用户重复操作引发并发调用。
- 通过本机模拟网关验证流式中断、工具失败与会话恢复，区分部分输出和完整回答，防止停止后的后台请求继续写入消息。
## 专业技能
- Java、HTTP/SSE、模型协议适配、工具编排、集成测试。
## 教育背景
某大学 · 软件工程 · 本科` },
  { id: "qa", direction: "测试开发", title: "质量实践：故障与回归", note: "写出具体缺陷、复现条件和自动化价值。", markdown: `# 郑同学（虚构示例）
测试开发
## 项目经历
### 订单服务质量验证 · 课程团队项目
- 将重复提交、库存不足、支付回调重放等业务边界转为接口测试，使用 Pytest 管理测试数据与清理流程。
- 用并发请求复现库存超卖，记录触发条件与数据库状态，协助团队增加条件更新后补充回归用例。
- 用 Playwright 覆盖下单、失败重试和订单状态展示，把失败截图与请求日志保存在测试报告中。
## 专业技能
- Python、Pytest、Playwright、SQL；能够从异常现象追到接口与数据状态。
## 教育背景
某大学 · 计算机相关专业 · 本科` },
  { id: "sre", direction: "运维 / 云原生", title: "部署实践：可重复交付", note: "区分本地实验与生产经验，写清交付和恢复流程。", markdown: `# 孙同学（虚构示例）
运维开发 / 平台工程
## 项目经历
### 多服务部署实验 · 个人项目
- 为 Java API、前端与数据库编写 Docker Compose 部署，配置健康检查、环境变量和持久卷，交付可复现的启动与备份说明。
- 以 GitHub Actions 执行构建与测试，生成带提交版本的镜像；在独立环境验证升级、回滚和数据卷恢复。
- 模拟数据库不可用、磁盘空间不足和服务重启，记录告警信号与恢复步骤；项目为实验环境，不声称承担生产值班。
## 专业技能
- Linux、Docker、GitHub Actions、日志分析与备份恢复。
## 教育背景
某大学 · 计算机相关专业 · 本科` },
];

export function splitSections(markdown) {
  const lines = markdown.split("\n"), sections = []; let intro = [], current;
  for (const line of lines) {
    if (/^##\s+/.test(line)) { current = { title: line.replace(/^##\s+/, "").trim(), lines: [line] }; sections.push(current); }
    else if (current) current.lines.push(line); else intro.push(line);
  }
  return { intro: intro.join("\n").trimEnd(), sections };
}
export function moveSection(markdown, index, offset) {
  const { intro, sections } = splitSections(markdown); const target = index + offset;
  if (target < 0 || target >= sections.length) return markdown;
  [sections[index], sections[target]] = [sections[target], sections[index]];
  return [intro, ...sections.map(s => s.lines.join("\n").trim())].filter(Boolean).join("\n\n");
}
