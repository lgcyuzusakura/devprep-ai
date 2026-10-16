# 求职助手第二轮研究：更多项目、技术文章与工程取舍

日期：2026-10-16（北京时间）。承接[第一轮可行性报告](2026-10-16-job-search-assistant-feasibility.md)，当前产品源码仍为 `ae3d296`。

## 本轮结论

推荐继续采用 **Spring Boot 主程序 + React 求职工作台 + 本机 Chrome/Edge 扩展**。本轮新增审阅 9 个相关仓库或产品资料，连同上轮共 18 个；补充了中文实现教程、国内工具介绍、AWS 与 Stripe 工程文章、Playwright Java 官方文档和 Chrome 官方文档源码。没有运行第三方求职程序、调用模型或真实投递。

新增资料使技术方案进一步明确：

1. 岗位发现、岗位匹配、简历改写和表单填写需要不同机制，不应全部交给模型反复浏览页面。
2. 建立结构化求职资料、按岗位保存简历快照，能复用已有简历模块，也能减少重复填写。
3. 字段填充先用规则和站点控件适配，AI 只补充不确定的语义映射；重复教育、工作经历必须识别所属段落。
4. 长任务状态放 Java 数据库，扩展按短步骤执行并回报；页面关闭、扩展后台休眠和 Docker 重启都不能触发盲目重投。
5. 对外部招聘系统无法普遍保证 exactly-once。正确目标是本地去重、逐次提交留痕、核对回执，以及结果不明时停止发送。
6. 第一版做好“岗位收集 → 定向材料 → 辅助填写 → 记录回执 → 面试准备”，再依据真实站点验证扩大自动化范围。

## 新增项目与实际核查深度

下表中的“支持”若来自 README，仅代表作者声明。没有把仓库下载成功、代码存在或 Star 数当作真实站点验收证据。

| 新增项目 | 本轮实际读取内容 | 值得学习的部分 | 使用边界与判断 |
| --- | --- | --- | --- |
| [OpenJobAutofill](https://github.com/Br1an67/OpenJobAutofill) | 仓库元数据、完整中英文入口 README 中的中文正文、MIT LICENSE、Manifest V3 配置、`src/background.js` 的映射与页面分析逻辑。 | 字段目录与资料值分离；模型返回字段路径，本地再取值。绿色/橙色区分已填和待处理，资料可搜索复制。 | 优先参考。源码提示词禁止返回个人值和决定提交；但“提示词写了隐私规则”不等于完整数据流经过审计，页面邻近文本也可能带个人值，需要自己做脱敏与出站验证。没有运行扩展。 |
| [JobNavigator](https://github.com/vesaias/JobNavigator) | 元数据、README；MIT。源码归档获取未完成，没有逐项验证其功能。 | Docker 工作台 + Manifest V3 扩展、基础简历与每岗改写稿、个人问答资料、岗位快照和申请状态。 | 是很贴近我们产品方向的参考。作者声明的 11 类 ATS、Gmail 与采集能力不能直接计入我们的支持范围；其 iframe 解限、被动采集等机制不随参考流程一起照搬。 |
| [JobOps](https://github.com/DaKheera47/job-ops) | README、完整 LICENSE、两个产品技术文档，以及固定提交中的岗位匹配、已投去重、邮件路由、人工审核和同步幂等性测试源码。 | 来源适配器注册、来源健康状态、岗位归一化、跨来源去重、投递后时间线、匹配不明确时人工审核。 | 最值得参考业务建模。LICENSE 是 AGPLv3 加 Commons Clause，有商业限制；以独立实现产品流程为主，不能直接复制代码后作为 MIT 发布。它明确不自动申请，不能当作自动投递引擎。 |
| [Get Jobs / 工作无忧](https://github.com/loks666/get_jobs) | 国内介绍文章、当前 README、PolyForm Noncommercial 1.0.0 LICENSE。README 声称 Java 21、Spring Boot 3.5、Gradle、Patchright；尝试的旧源码路径及 `pom.xml` 为 404，未据此验证当前构建。 | 国内筛选条件、已沟通岗位默认跳过、黑名单、配置界面、附件与招呼语区别。 | 是补充的 Java 方向参考。README 明确主要维护 BOSS、猎聘，其余部分平台停止维护；文章中的多平台介绍不等于当前稳定可用。非商业许可不适合直接作为现有 MIT 功能依赖。作者提到的上限、低成本和成功率没有独立实测。 |
| [find-job](https://github.com/noBaldAaa/find-job) | 元数据、包含完整实现教程的 README，介绍 Selenium 找 DOM、扫码后读取 JD、逐岗请求模型并进入聊天。 | 中文从零实现资料，能看清“读取 → 生成 → 发送”的基本过程。 | GitHub 未识别到许可证，最后推送为 2024-01-08；依赖 DOM 选择器与旧网关介绍，适合原理学习，不视为 2026 年可直接运行的成品。不要沿用教程中的特定免费网关。 |
| [auto_job__find__chatgpt__rpa](https://github.com/Frrrrrrrrank/auto_job__find__chatgpt__rpa) | 元数据和 README：Python、Selenium、Robot Framework、简历与 JD 生成招呼语，含旧版 Assistants 依赖及迁移说明。 | 简历材料、JD 和中文沟通语结合的早期产品流程。 | GitHub 未识别到许可证；最后推送为 2024-10-09。旧 API、账户和版本限制不能直接复用；无需为它引入第二套模型服务。 |
| [JobPilot](https://github.com/suxrobGM/jobpilot) | 元数据、README，MIT；描述本机代理、搜索 Campaign、每岗简历、日志、人工接管与任务恢复。 | 用户在界面确定目标，后台按步骤工作，能查看过程、恢复与查看每岗材料。 | 仅参考交互和任务组织。README 中依靠其他编程工具订阅、模型默认值或验证码相关行为不构成本项目的接入承诺。DevPrep 继续使用既有 GPT API 网关。没有安装它的插件或执行安装脚本。 |
| [FormFilla](https://github.com/AhmadHassan-BTed/FormFilla) | 元数据和公开技术 README；内容包含字段信号归一化、重复区块、二分图匹配和框架事件。 | 可借鉴的算法方向是结合标签、占位符、所属段落进行多信号匹配，避免每个字段各自贪心匹配。 | README 明确核心引擎和 DOM 逻辑闭源，公开仓库是文档及发行入口；只能评估公开设计，不能宣称读过核心源码。也不能因为 GitHub 有仓库就称其引擎开源。 |
| [New Grad Positions](https://github.com/SimplifyJobs/New-Grad-Positions) | 仓库元数据与当前 README。岗位清单按方向、应届身份和开放状态分类，链接实际申请入口。 | 说明“来源”也可以是人工维护的岗位清单，能补充校招、应届岗位来源发现。 | 主要是海外机会，作为补充。仓库未识别到许可证，清单中的链接可供发现，不默认打包转载全部内容。Simplify Copilot 的广告不代表该清单仓库公开了其扩展源码。 |

## 技术文章与官方资料：读到了什么，如何用于当前项目

| 资料 | 核实和学习到的内容 | 对 DevPrep 的具体影响 |
| --- | --- | --- |
| [find-job 中文实现教程](https://github.com/noBaldAaa/find-job#二整体思路) | 实际读到从浏览器打开、扫码登录、读取 JD 到生成并发送话术的逐步示例。 | 原型流程可参考；将 DOM 选择器、材料生成、发送结果分开处理，避免把所有操作写在一个循环里。旧登录流程不直接当作可用适配器。 |
| [Get Jobs 工具介绍](https://ai-bot.cn/get-jobs/) | 国内二手资料提到 JDK21、Maven、ChromeDriver 和多个平台。回到仓库后发现当前 README 已列 Gradle，并缩小主要维护平台。 | 技术博客用来发现线索；以当前许可证、代码和维护范围校验，发现过时部分要记录。该文属于工具介绍，不作为底层技术证明。 |
| [AWS：Making retries safe with idempotent APIs](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/) | 请求 ID 应由调用者提供；重复 ID 对应不同参数需要报冲突；记录 token 与业务变更要有原子性；还要考虑迟到请求和保留时间。 | 任务绑定材料摘要，同一提交标识不能偷偷换简历或话术。数据库先登记尝试，再发给扩展；重连先查尝试状态。外部站点没有对应幂等能力时，不能把内部事务扩大解释为外部只执行一次。 |
| [Stripe：Designing robust and predictable APIs with idempotency](https://stripe.com/blog/idempotency) | 网络中断可能发生在服务端已执行之后。Stripe 通过服务端支持的 `Idempotency-Key` 安全重试，文章还讨论退避。 | 招聘站多数不提供求职者可控的提交键，因此超时不自动再点。退避可用于允许重试的读取操作，不能替代提交结果核查。 |
| [AWS：Timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) | 阅读到超时覆盖范围、重试放大、带副作用操作及退避的讨论；页面已重定向至 AWS Builder Center。 | 读取任务和提交任务采用不同错误策略。设置总预算与单次超时，不在每一层都加重试。当前 GPT 调用继续不自动重试，遵守现有单请求和间隔限制。 |
| [Playwright Java：Authentication](https://playwright.dev/java/docs/auth) | 浏览器 Context 的登录态可保存；不同应用可能依赖 cookies、localStorage、IndexedDB 等；sessionStorage 不由 storageState 自动持久保存。 | 不承诺导入一个 Cookie 文件就能支持所有站点。本人登录和登录态检查应独立实现；任何保存的状态均不进入公开仓库或分发包。 |
| [Playwright Java：Actions](https://playwright.dev/java/docs/input) | `fill`、`selectOption`、单选/复选控件、`setInputFiles` 和动态 FileChooser 有不同操作方式。 | 上传附件是独立步骤；对文本、日期、普通下拉与自定义控件分别设计驱动，并读取页面回显。 |
| [Playwright Java：Auto-waiting](https://playwright.dev/java/docs/actionability) | click 等动作等待唯一元素、可见、稳定、可接收事件、可用；动作成功并不证明业务已提交。 | 等页面条件而非固定 sleep。按钮点击完成后，继续核对岗位、附件、确认页和服务端回执。失败时保留“结果不明”，不能一律记失败。 |
| [Playwright Java：BrowserType](https://playwright.dev/java/docs/api/class-browsertype) | `connectOverCDP` 仅限 Chromium，官方提示其能力低于 Playwright 协议连接；默认 Chrome 用户目录自动化也有兼容限制，持久 Context 应使用独立目录。 | “直接复用用户平时 Chrome 的默认 Profile”不是可靠安装方案。优先扩展作用于本人已打开页面；若以后增加 Java 浏览器助手，使用独立 Profile 或明确管理的连接。 |
| [Chrome 官方 activeTab 文档源码](https://github.com/GoogleChrome/developer.chrome.com/blob/main/site/en/docs/extensions/mv3/manifest/activeTab/index.md) | 官方 GitHub 文档说明：用户触发后临时授权当前标签页，同源导航可继续，跨 origin 后授权撤回。 | 第一期当前页采集/填写可用 activeTab + scripting；从招聘站跳到另一家 ATS 时，需要重新触发或申请该站点权限。自填 URL 不代表自动拥有页面操作权。 |
| [Chrome 官方 Service Worker 生命周期文档源码](https://github.com/GoogleChrome/developer.chrome.com/blob/main/site/en/docs/extensions/mv3/service_workers/service-worker-lifecycle/index.md) | 读取的是标注更新于 2023-09-27 的官方仓库文档，包含休眠、全局变量丢失、需要持久化，以及不同 Chrome 版本的差别。 | 扩展 worker 不保存唯一任务真相，不能靠内存变量和 setTimeout维持整批任务。具体生命周期限制随最低浏览器版本再核对，此处不将旧秒数当作 2026 年全版本保证。 |
| [JobOps：Extractor System](https://jobops.dakheera47.com/docs/extractors/overview/) | 来源通过 manifest 注册，各来源有依赖、能力、字段与健康情况；手动导入作为正式来源，而不是异常补丁。 | 设计 `JobSourceAdapter`，独立声明搜索、详情、字段与申请辅助能力；每个来源失败不拖垮整个任务。健康检查和真实搜索无结果应区分，不能统一说“站点坏了”。 |
| [JobOps：Post-Application Tracking](https://jobops.dakheera47.com/docs/features/post-application-tracking/) | 邮件关联岗位，不确定时进入人工审核；使用只读 Gmail 权限。文档中的 95/50 等是产品阈值。 | 将来可增加只读导入投递回执，但不把模型自报置信度当作统计概率，也不直接照用阈值。本轮不接邮箱、不发邮件。 |

## 固定源码里确认的工程细节

### JobOps：岗位相似不等于同一申请

阅读提交 [`cb8803c`](https://github.com/DaKheera47/job-ops/tree/cb8803c0566a1d8ec789f1f1ecf121f58ac8b769) 中的：

- [applied-duplicate-matching.ts](https://github.com/DaKheera47/job-ops/blob/cb8803c0566a1d8ec789f1f1ecf121f58ac8b769/orchestrator/src/server/services/applied-duplicate-matching.ts)：对公司与标题归一化并计算相似度，检查已投状态和时间窗，给出重复提示。
- [job-matching.ts](https://github.com/DaKheera47/job-ops/blob/cb8803c0566a1d8ec789f1f1ecf121f58ac8b769/shared/src/job-matching.ts)：还涉及地点意图、字段合并与模糊去重。
- [gmail-sync.idempotency.test.ts](https://github.com/DaKheera47/job-ops/blob/cb8803c0566a1d8ec789f1f1ecf121f58ac8b769/orchestrator/src/server/services/post-application/ingestion/gmail-sync.idempotency.test.ts)：测试同一封消息重复同步时，仅在首次转换时创建阶段事件。
- [email-router.ts](https://github.com/DaKheera47/job-ops/blob/cb8803c0566a1d8ec789f1f1ecf121f58ac8b769/orchestrator/src/server/services/post-application/ingestion/email-router.ts) 与 [review/service.ts](https://github.com/DaKheera47/job-ops/blob/cb8803c0566a1d8ec789f1f1ecf121f58ac8b769/orchestrator/src/server/services/post-application/review/service.ts)：模型只从候选岗位集合选择，人工复核另走业务服务。

这些源码只阅读，没有执行测试。我们应优先用“站点 + 外部岗位 ID”识别同一岗位；公司与标题相似仅作提示，不能直接删掉不同地点、职级或届别的岗位。时间窗也应由产品规则决定，不能照抄一个天数就阻止所有再次申请。

### OpenJobAutofill：让 AI 只返回资料路径

阅读 [Manifest](https://github.com/Br1an67/OpenJobAutofill/blob/main/manifest.json) 和 [background.js](https://github.com/Br1an67/OpenJobAutofill/blob/main/src/background.js)：

- Manifest 请求 activeTab、scripting、storage 等权限，额外站点权限为可选项；不是安装时就长期获得所有招聘网站访问权。
- `mapFields` 接收页面字段和资料目录，要求模型返回已有 `fieldId` 与 `sourcePath`；`buildMessages` 明确不提供真实资料值，也不允许模型决定提交。
- `analyzePageStructure` 与资料路径映射分开，关注字段标签、所属段落和控件类型。
- 提示词特别处理“家属姓名 / 紧急联系人电话 / 证明人单位”等上下文，避免误填求职者自己的资料。
- 页面邻近文本有规则脱敏，但不能推导出“绝不泄漏任何个人信息”的保证。我们应先构造允许出站的字段结构，再发送模型；不要先发整页 HTML 后寄希望于提示词。

此次读取来自 `main` 当日快照，没有取得完整固定提交归档；本地保存了原文。字段映射后台文件的 SHA256 另存于本地研究清单，后续复用前应再固定版本并检查 MIT 署名要求。

## 架构比较与取舍

| 路线 | 优点 | 具体代价 | 本项目建议 |
| --- | --- | --- | --- |
| Spring Boot + 浏览器扩展 | 主业务保持 Java；本人浏览器登录；配合 Docker；页面可见、容易接手。 | 新增一小部分 JS/TS 扩展；跨域跳转、复杂控件、安装配对需要处理。 | 主路线。浏览器代码是必要客户端，不等于后端改成 Node。 |
| Spring Boot + 本机 Playwright Java 助手 | Java 可直接编排浏览器与上传文件；独立 Profile 和可见浏览器便于调试。 | 另装本机进程、浏览器运行库、与 Docker 通信；不能随意占用用户默认 Profile。 | 后续针对需要的站点增加，不要求第一版用户全部安装。 |
| Docker 内 Playwright 浏览器 | 环境和版本可控，便于自建测试表单回归。 | 本人扫码、可见操作与接管需要远程显示/额外 UI；真实网站表现需验证。 | 可作测试环境或后续选项，不作为国内网站第一版唯一入口。 |
| 每一步都让通用视觉 Agent 决定 | 原型可能较快适应简单新页面。 | 模型请求更多、等待更长、页面重复操作和结果判定不稳定。 | 作为复杂页面的后续辅助，不作为默认流程；不另开模型并发。 |
| 纯后端 HTTP/私有接口请求 | 对明确公开 API 的岗位读取简单。 | 普通求职者缺少招聘方申请 API 权限；私有接口易变、鉴权机制各异。 | 公开读取接口使用 Java HttpClient；申请通过已支持的页面流程。 |

现有 H2/PostgreSQL、Flyway、简历编辑导出、模型网关和串行协调器可以继续使用。第一版任务量不要求增加 Redis、Kafka、微服务或新的 Agent 框架；用数据库持久任务和一个后台执行器即可支持单用户串行任务。浏览器重连不能自动触发“执行所有未完成任务”，应先检查状态和授权是否仍有效。

## 建议补充的核心设计

### 结构化求职资料与岗位材料版本

简历正文用于展示与表达，网申填表还需要结构化资料。建立可由用户核对的资料表：基本信息、教育、实习/工作、项目、技能、证书，以及期望地点、可到岗时间等。导入简历后先展示提取结果，不将模型推断默认为确认资料。

每个岗位的材料包绑定：JD 快照、确认资料版本、简历版本、导出文件摘要、沟通话术和筛选问题回答。用户改动材料后，原来待提交的确认应失效；历史已投记录继续保留旧版本。

附件上传不只确认文件名：本地保留文件摘要与版本，页面核对所选文件及处理结果。招聘系统自动解析附件可能覆盖已有字段，上传后需要重新检查。若站点只能发送账户默认附件，就明确显示这一点，不能保证每岗临时换附件。

### 表单映射：先认区块，再认字段

按“页/步骤 → 教育/经历区块 → 第几条记录 → 字段”组织。标签、ARIA、name、placeholder、邻近标题和控件选项作为证据。模型只在规则无法确定时给出受限的候选映射，不生成任意 JS 或选择器执行。

FormFilla 公开文档提出的二分图最大权匹配可作为研究方向，但不能强制把每个字段一一匹配：表单可能重复要求同一个姓名、手机号，而多段经历又必须属于不同记录。实际需要允许未匹配、按区块限制候选和处理一对多关系。不要把“用了 Hungarian 算法”当作准确率证明。

识别控件后再执行适配：普通 input/textarea、原生 select、搜索选择器、级联地点、日期范围、富文本、文件上传、iframe/Shadow DOM。跨域 iframe 需要相应权限，关闭的 Shadow Root 等结构可能无法通用读取，保留手动处理。

### 扩展权限与任务恢复

activeTab 适合用户点击后处理当前页；批量打开新站点不是同一个权限场景。跨站 ATS 或特定站点持续任务需单独声明和申请权限。网站支持表应同时显示“适配能力”和“当前权限”，不能混成一个“支持”标签。

Java 数据库保存任务、授权范围、当前步骤、材料摘要和结果。扩展领取一个短任务后返回步骤记录；每次重新执行前检查标签页、origin、岗位 ID 与材料版本是否仍相同。页面导航后旧 DOM 操作应失效。

扩展只与本机配对服务通信，真实 GPT 密钥仍放后端。无须扩展再保存第二份模型配置、再开第二个模型调用通道。配对凭据、消息来源和任务类型要由程序验证；不能让网页发一段消息就调用发送简历能力。

### 模型次数与处理顺序

先用明确的用户条件、岗位状态和去重做规则过滤；再对用户选中的候选岗位生成匹配分析。优先岗位才做完整定向简历，其他岗位可先出简短话术草稿，避免每看到一个岗位就重写一遍简历。

JD、资料和改写要求未变时复用已生成结果；缓存键包含资料与提示词版本，不能只按岗位 URL 缓存。模型仍复用现有 GPT Provider，全局串行、请求结束后默认间隔 10 秒，无后台并发、无自动切换 DeepSeek。

排序提供“要求、证据、缺口”而不是假称某分数代表拿 Offer 的概率。模型自报 0.95 也不代表经过校准的 95% 正确率；是否允许自动操作由已验证能力和明确规则决定。

### 队列去重和投递回执

内部每次提交有唯一尝试 ID，绑定材料摘要和授权；同 ID 参数变化应报冲突。执行端返回尝试 ID，数据库保存最后已确认步骤。这样可避免刷新页面、重复点击或连接重建在内部多派发任务。

对外部提交，仍存在“站点已收到，回报还没到”的时间段。除非外部站点明确支持幂等键，否则不能保证 exactly-once。发生超时应进入“结果不明”，检查原站申请记录、确认页或可靠回执后再决定是否允许下一次尝试。

采集失败可在允许范围内使用有限退避；发送消息、上传与最终提交分别定义策略。不要把某个上传失败等同于整份申请未提交，不把停止任务等同于撤销已发送的消息。

## 从参考项目中明确不沿用的部分

- 不使用项目 README 推荐的免费中转或硬编码模型；沿用用户现有 GPT 网关。
- 不将插件市场宣传、岗位清单规模、作者成功率和模型自报分数写成本产品实测数据。
- 不把招聘网页嵌在 iframe 中并修改对方响应头作为核心浏览方式；原站在独立标签页打开，工作台保留可读快照。
- 不加入自动识别绕过验证码、账号轮换或规避平台限制的能力；出现校验就交给本人。
- 不把自动 HR 续聊、邮件发送、交换联系方式、读取邮箱或招聘定时任务默认为本次功能授权。可先生成草稿，发送功能按独立范围设计。
- 不把第三方闭源引擎或带限制的源码直接放入现有 MIT 仓库。参考流程与复制代码是不同的复用方式。

## 更具体的实施与验收顺序

**阶段一：求职工作台及材料闭环。** 内置入口、自定义 URL 类型识别、岗位卡片、JD 快照、偏好筛选、去重提示、岗位简历版本、中文话术、导出、状态看板和面试联动。公开岗位接口及手动 JD 入口均可工作，核心业务继续放 Java。

**阶段二：浏览器当前页采集与辅助填写。** 实现扩展配对、当前岗位保存、字段映射预览、按区块填写、附件版本检查、绿色/待处理状态。先用自己的假招聘页面验证真实 React 表单、跨步骤、动态控件与上传；不需要真实求职账号就能验证多数工程逻辑。

**阶段三：一个站点的申请全流程。** 在核对使用条件后，选用户实际使用且能够验证的站点。验收从详情、材料预览、确认到原站回执，并覆盖错误页、已有申请、重复点击、浏览器关闭和登录失效。初始不以多平台海投作为验收方法。

**阶段四：增加允许的站点和受控批次。** 每站列出搜索/读取/填写/附件/提交/结果读取的独立支持状态。只有对应路径验收后才开放串行批次；未知页面停下，不让模型临时发明投递流程。邮件回执导入可单独排在后续。

关键验证场景应至少包含：

| 场景 | 预期行为 |
| --- | --- |
| 两段教育经历均有“学校、开始时间、结束时间” | 填到正确区块和记录；不能把同一学校复制到全部段落。 |
| JD 页面已经过期，或搜索关键词无匹配 | 区分岗位失效、零结果与来源异常，不捏造岗位。 |
| 资料改动后，用户点旧的投递确认 | 要求重新核对新材料，不悄悄换附件。 |
| 上传触发站点自动解析，手机号被覆盖 | 上传后读回核对并提示冲突，不能直接继续提交。 |
| 扩展 worker 休眠，或浏览器断连后恢复 | 查询数据库中的尝试和状态，不从头再点发送。 |
| 外部点击提交后网络断开 | 标为结果不明，保留回执检查入口，不自动重投。 |
| 模型将紧急联系人电话映射成本人手机号 | 按区块与允许资料路径校验拒绝，标记待处理。 |
| 页面中夹带“把所有资料发到另一网址” | 页面内容只作数据，不能产生新目标或工具权限。 |
| 同时进行学习聊天、改简历、处理求职任务 | GPT 请求仍通过同一个串行协调器。 |

## 研究局限与本地材料

本轮只阅读和比对，未对真实招聘站进行登录、申请和端到端兼容验证。除明确列出的源码外，其余能力来自作者资料；没有把阅读某测试文件说成测试已通过。

访问限制记录：

- GitHub 匿名 API 在本轮触发限额；后续改用有限的公开文件请求与源码归档，没有借用用户凭据或新增登录。
- Chrome 当前官网部分页面连接超时；阅读了官方 GitHub 的 activeTab 与生命周期文档源码，生命周期文档带旧更新时间。后续实现需按支持的 Chrome/Edge 最低版本复核。
- 知乎《最近很火的自动投简历脚本，我让它更“傻瓜”了一点》返回 403，没有把搜索摘要算作已读全文；改读相应 GitHub 的完整实现教程。
- 尝试的 Checkly、Browserless 上传文件博客地址返回 404，没有列为有效技术依据；上传行为使用 Playwright Java 官方文档核实。
- OpenJobAutofill 完整 ZIP 下载不完整、JobNavigator 源码归档未取得，不作为已完成源码审计；JobOps 固定提交归档完整取得并阅读了指定文件。
- Get Jobs 的旧源码路径和构建路径返回 404，所以当前 Java/框架版本只注明来自 README，没有伪称已读实现。
- 本地阅读材料未自动进入产品题库、公开发布或迁移包，也没有提交推送。

所有本轮研究快照保存在 `.cache/research-recruitment-2026-10-16/round2/`，包括仓库元数据、README、已读网页正文、访问结果、固定源码归档和本地清单。它们属于研究过程文件，后续实现由我们独立编写并保留实际引用需要的署名。
