# ADR 0001：Spring Boot 后端与模型适配边界

- 状态：已实现；178 项 Java 测试、6 项解析回归、15 项浏览器回归通过
- 日期：2026-10-08
- 范围：DevPrep AI 主项目

## 背景

用户要求主项目采用 Spring Boot，并能作为有完整业务流程、测试和维护文档的本科作品持续发展。原型验证了资料导入、检索、练习自评、复习记录和 Codex 风格模型配置。本次迁移保留前端交互契约，建立 Java 服务层和可测试的数据、模型边界。

## 决策

采用 Java 17、Spring Boot 3.4.5 的模块化单体。一个进程承载业务和构建后的 React 界面，通过应用服务协调写入操作。确有独立伸缩或执行隔离需求后，再拆分模块。

| 模块 | 事实和操作 |
| --- | --- |
| 内容与检索 | 文档、章节、来源、导入/删除、检索结果与可定位引用 |
| 知识与题库 | 稳定知识点 ID、前置关系、题目、答案和自评 Rubric |
| 练习与复习 | 作答、确定性客观评分、自评证据、复习排期与历史 |
| 学习概览 | 根据作答和复习状态计算学习视图及推荐理由 |
| 模型与问答 | Provider、模型目录、协议适配、超时、问答与错误映射 |
| API 与运行 | DTO 校验、统一错误、配置绑定、本机来源和体积约束 |

服务层拥有业务写权限。模型回答不直接修改题目、得分或复习状态。作答保存与复习时间更新必须在同一事务内完成。生成题库和模型主观评分尚未落地；引入前需补齐草稿审核、Rubric 版本和人工标注回归集。

## AI 框架与协议

当前使用项目内 `ModelGateway` 接口及 Java HttpClient 实现 `HttpModelGateway`，提供模型发现、Responses 和 Chat Completions。TOML 表单仍能配置模型、Provider、基础 URL、协议、环境变量名、发现路径和超时。

| 方案 | 适用场景 | 当前决策 |
| --- | --- | --- |
| 项目内 HTTP Adapter | 小范围兼容网关、双协议与模型发现 | 当前实现；有 Mock HTTP 契约测试 |
| Spring AI | ChatClient、Advisor、工具调用、向量检索与 Spring Observation | 后续需要编排时评估；目前未加入依赖 |
| LangChain4j | AI Services、框架中立的 Java 应用及替代模型能力 | 可替换候选；目前未加入依赖 |

这是按当前能力范围作出的依赖选择。业务服务使用项目内模型接口，框架对象不会传播到题库和复习模块；接入任一 AI 框架时，现有协议契约仍需验证。

模型目录和生成接口分开。`GET base_url + models_path` 支持常见 `data`/`models` 数组及模型 ID 字符串列表，目录缓存有 TTL 和强制刷新入口。模型发现成功只证明资源可列出，不能推定它支持文本、工具或流式生成。

HTTP 契约测试覆盖请求路径、鉴权、输入、输出解析、超时与错误映射；模型缓存和配置保存也需覆盖失效行为。CI 不使用真实密钥或真实模型服务。

## 配置

TOML 保持 `model`、`model_provider`、`model_providers.<id>`。配置保存采用临时文件与替换，密钥仅通过指定环境变量读取。Codex 导入只允许当前自定义 Provider 的受支持字段；不读取 `auth.json`，不导入登录令牌、MCP 或任意未知字段，不修改原文件。

应用默认监听本机，API 检查 Host/Origin 并限制请求大小。目前没有多人认证、角色权限或面向公网的部署能力。

## 数据库

采用 Spring JDBC 与 Flyway：

| 环境 | 数据库和配置 |
| --- | --- |
| 默认本地运行 | 文件型 H2，PostgreSQL 兼容模式 |
| `postgres` profile | PostgreSQL；连接 URL、用户名和密码由环境变量设置 |
| 自动化测试 | 隔离的临时数据库，不能依赖用户已有资料或配置 |

Flyway 迁移存放在 `backend-java/src/main/resources/db/migration/`，随 Jar 打包；后续修改通过新版本脚本表达。种子由 classpath 加载，首次初始化应可重复启动且不覆盖学习记录。H2 和 PostgreSQL 共用表约束及业务 SQL，需要分别确认兼容性。

默认数据目录是 `~/.devprep-ai/`。`DEVPREP_DATA_DIR` 覆盖默认父目录，`DEVPREP_DB_FILE` 覆盖 H2 基名（不带 `.mv.db`），`DEVPREP_CONFIG_FILE` 覆盖 TOML。`postgres` 通过 `DEVPREP_DATABASE_URL`、`DEVPREP_DATABASE_USER`、`DEVPREP_DATABASE_PASSWORD` 设置。

旧 SQLite 文件属于 Python 原型，不会被 Java 服务读取或自动转换。当前没有跨数据库迁移工具；已有数据须保留备份与原型读取入口。PostgreSQL profile 已提供，实际数据库运行验证应单独记录，不能由 H2 测试代替。

## 构建与运行

Maven Wrapper 位于 `backend-java/`。Node 启动器检查 JDK、构建 React、通过 Wrapper 打包，然后运行可执行 Jar，并设置 `DEVPREP_ROOT` 指向仓库根目录。静态资源随 Jar 打包，同时支持仓库内 `dist/`；学习种子从 classpath 读取。

`npm start` 和平台脚本默认启动 Spring Boot。`npm test` 运行 Java 测试。Python 原型仅保留作迁移对照，不是当前启动入口。CI 在 Ubuntu 和 Windows 使用 Java 17 验证，浏览器测试使用临时 H2/TOML 的 Java 服务。

## 迁移验收

1. Spring API 覆盖前端调用，保留请求、响应与错误语义，尤其是既有 snake_case JSON。
2. JUnit/MockMvc 覆盖 DTO 校验、评分和复习事务、资料分块/删除、内置资料保护和重复启动。
3. 模型测试覆盖配置、导入、发现缓存、双协议、无 Key 与网关错误。
4. Maven 验证和前端构建通过；Playwright 使用 Spring 后端完成学习、资料、记录和设置流程。
5. 跨平台启动器、Wrapper、CI、README 与实际入口一致，前台服务可停止。
6. PostgreSQL 与旧 SQLite 数据迁移的未验收范围在文档明确，不以原型测试成绩替代 Java 验收。

## 本次验证记录

2026-10-08 在 Windows/JDK 17 环境通过 Maven `clean verify` 与更新后的 `test`，编译 43 个 Java 源文件，178 项 Java 测试无失败。覆盖评分、自评、UTC 排期、事务回滚、并发作答、2,500 道题选题查询、本地包增量导入、来源字段与模型协议。`npm run test:imports` 的 6 项合成样例解析回归通过。

`npm run test:e2e`（本机 Edge）通过 15 项浏览器测试。Node 启动器构建前后端并启动 Spring Boot，覆盖练习与记录、资料检索/删除、资源筛选、Provider 发现/问答、移动端、设置错误恢复、原文自评、来源元数据、公式、Markdown/HTML 表格以及图片隔离。启动器语法、PS/CMD 帮助、非法参数退出码和 27 个项目名称核对也通过。

本机实际数据库已加载 10,080 道题、223 个知识点，包含 70 道原创示例与 ignored cache 中的 10,010 道外部题目。17 个导入领域的选题和参考答案接口均核验通过，验证未写入个人作答记录。题库来源、逐题审计及下载快照保留在本机；外部正文不进入公开仓库或 Jar。

该记录不包含 PostgreSQL 实际运行、旧 SQLite 数据转换及远端 CI 的结果；后续验证须更新记录。

## 后续产品完善

后续按真实学习流程扩展正式题库、来源和审核、混合检索、主观评分回归集、数据库迁移工具、备份恢复与权限。论文和演示材料应给出可重复的评测集、失败案例与实际测试结果，区分当前实现和规划。
