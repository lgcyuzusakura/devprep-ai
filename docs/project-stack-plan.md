# 27 个独立项目的技术栈与仓库规划

日期：2026-10-08。项目名称和编号来自本机原始清单 `D:\项目\321.txt`，共 27 项：通用方向 6 项、Go 方向 11 项、Java 方向 10 项。J1 后续扩展为主推的 **DevPrep AI：计算机学习、考试与面试项目**。

按用户最新要求，每项拥有独立 GitHub 仓库，后端使用 Java、Go 或 Python 框架。每个仓库应能独立安装、启动、测试和发布，可以复用公开包或协议。当前已建立的仓库是 [lgcyuzusakura/devprep-ai](https://github.com/lgcyuzusakura/devprep-ai)；下表其余 26 项为技术规划和建议仓库名，尚未创建或实现。旧报告中的合并建议不作为本次仓库组织方案。

## 通用方向：6 项

| ID | 原始选题 | 推荐后端框架 | 建议仓库名 | 核心交付与验收重点 |
| --- | --- | --- | --- | --- |
| 3 | 基于多智能体协作的软件需求分析与测试生成系统 | Python FastAPI + LangGraph + SQLAlchemy/PostgreSQL | `reqtest-agents` | 需求版本、角色工作流、设计/测试产物、人工修改与追踪；比较单 Agent 和多角色方案的需求覆盖率 |
| 4 | 面向 GitHub Issue 的代码缺陷定位与修复 Agent | Python FastAPI + 独立沙箱 Runner + PostgreSQL | `issue-fix-agent` | Issue 到定位、补丁、独立测试报告；在固定版本仓库和保留测试上统计修复率、回归与预算 |
| 5 | 面向程序设计课程的代码评审与辅导 Agent | Java Spring Boot + LangChain4j + PostgreSQL | `course-code-tutor` | 静态检查、学生状态、分层提示、提交历史；用课程题集验证问题定位和提示质量 |
| 6 | 基于浏览器操作的跨网站任务自动化 Agent | Python FastAPI + Playwright + 持久化任务状态机 | `cross-site-agent` | 页面观察、动作与结果记录、暂停/恢复、页面变化重试；固定本地网站集验证任务成功率 |
| 11 | 面向安全日志的告警分析 Agent | Python FastAPI + LangGraph + OpenSearch | `security-log-analyst` | 日志导入、时间线、关联证据、告警解释与建议；在标注事件集测误报/漏报与证据覆盖 |
| 14 | Agent 自动评测与可观测性平台 | Java Spring Boot API + Python 评测 Runner + PostgreSQL | `agent-eval-observatory` | 数据集版本、任务批次、调用记录、指标与对照报告；相同输入可重复运行并追溯评分来源 |

## Go 方向：11 项

| ID | 原始选题 | 推荐后端框架 | 建议仓库名 | 核心交付与验收重点 |
| --- | --- | --- | --- | --- |
| G1 | 基于 Go 的高并发网络信息调研 Agent | Go Chi + Eino + Colly + PostgreSQL | `go-research-agent` | 并发采集、来源去重、证据摘要与报告；比较并发限制下的耗时、完整率和来源准确率 |
| G2 | 基于浏览器控制的网页任务自动化 Agent | Go Chi/Cobra + chromedp + SQLite/PostgreSQL | `go-browser-agent` | 任务 API/CLI、浏览器会话、动作日志和恢复；突出 Go 会话管理与并发控制，与通用 6 分开演示 |
| G3 | 面向 Kubernetes 集群的智能运维 Agent | Go Chi + client-go + Eino | `go-k8s-ops-agent` | 资源、事件和日志证据、诊断与操作计划；用本地集群注入故障验证定位率与操作审批记录 |
| G4 | 基于 MCP 的统一工具调用网关 | Go Chi/ConnectRPC + mcp-go + PostgreSQL | `go-mcp-gateway` | 工具注册、协议转换、访问控制、限流和审计；测试工具调用隔离、取消和故障映射 |
| G5 | Go 命令行编程辅助 Agent | Go Cobra + Eino 或 openai-go + 隔离执行器 | `go-code-assistant` | 代码搜索、修改建议、补丁预览和测试；保留可重放任务轨迹并验证仓库版本和补丁适用性 |
| G6 | 面向微服务系统的故障诊断 Agent | Go Chi + errgroup + OpenTelemetry/Prometheus 客户端 | `go-service-diagnoser` | 关联日志、链路和指标，输出证据与排查路径；在故障注入样例上测根因定位与耗时 |
| G9 | 高并发智能客服与工单分配 Agent | Go Chi + Eino + Asynq/Redis + PostgreSQL | `go-support-dispatcher` | 会话隔离、检索回答、意图分类、工单与异步任务；测并发响应、重复提交和工单一致性 |
| G10 | 面向安全日志的告警研判 Agent | Go Chi + go-elasticsearch + PostgreSQL | `go-alert-triage` | 流式日志、窗口聚合、关联事件与研判证据；测吞吐、延迟、误报/漏报及事件重放 |
| G12 | 具有长期记忆的个人知识管理 Agent | Go Chi + Eino + PostgreSQL/pgvector | `go-memory-notes` | 来源、事实版本、时间衰减、冲突更新和删除；测记忆命中率、过期事实与跨会话一致性 |
| G14 | 多智能体任务协作与调度平台 | Go Chi + Eino + NATS 或 Asynq + PostgreSQL | `go-agent-scheduler` | DAG、可靠状态机、持久化检查点、重试和转交；通过进程中断和重复消息验证恢复与幂等 |
| G15 | Agent 调用链追踪与自动评测平台 | Go Chi + OpenTelemetry Go + PostgreSQL | `go-agent-trace-eval` | 有界采集、Trace 查询、数据脱敏与评测批次；测采集开销、丢弃策略和评分可追溯性 |

## Java 方向：10 项

| ID | 原始选题 | 推荐后端框架 | 建议仓库名 | 核心交付与验收重点 |
| --- | --- | --- | --- | --- |
| J1 | 基于 Spring AI 的高校知识库 Agent[期末，考研】 | **Java 17 + Spring Boot 3.4.5 + Spring JDBC/Flyway**；本地 H2、可选 PostgreSQL；项目内 ModelGateway | **`devprep-ai`（已建）** | 扩展为计算机学习、考试与面试主项目；当前先验证资料、题库、作答、复习、记录、配置与带来源问答的完整流程 |
| J4 | 面向 Java 项目的代码审查 Agent | Spring Boot + Picocli Runner + SpotBugs/OpenRewrite + LangChain4j | `java-code-review-agent` | 文件/行号定位、静态规则与模型解释、补丁建议；标注缺陷集验证准确率、误报和修改可编译性 |
| J5 | 软件需求到测试用例生成 Agent | Spring Boot + Spring AI + JUnit/Testcontainers Runner | `java-requirement-tests` | 需求到场景、JUnit 和测试数据，执行后可定位失败；以覆盖率、需求覆盖和变异测试检验生成质量 |
| J6 | 基于 MCP 的企业应用集成 Agent | Spring Boot + Spring Security + MCP Java SDK 或 Spring AI MCP | `java-enterprise-mcp` | 企业连接器、角色权限、工具审计和执行状态；测试跨角色访问、超时、重试和连接器错误 |
| J8 | 面向微服务的故障分析与恢复建议 Agent | Spring Boot + Actuator/Micrometer + OpenTelemetry + Spring AI | `java-service-recovery` | Spring 服务拓扑、故障证据、恢复建议与验证记录；用明确故障类型测定位和建议有效性 |
| J10 | 软件供应链安全分析 Agent | Spring Boot + OWASP Dependency-Check + OpenRewrite + Spring AI | `java-supply-chain-agent` | 依赖图、漏洞证据、受影响路径、升级方案；验证升级前后构建、测试与依赖兼容性 |
| J11 | 基于工作流的多智能体任务调度系统 | Spring Boot + Flowable + Spring AI + PostgreSQL | `java-agent-workflow` | 工作流版本、任务分派、人工节点、暂停/恢复和失败补偿；突出流程审计与长任务生命周期 |
| J12 | 个性化程序设计教学 Agent | Spring Boot + LangChain4j + PostgreSQL + 隔离 Runner | `java-personal-code-tutor` | 学习画像、题目推荐、分层提示和错题反馈；以教学流程和学习记录验证个性化策略 |
| J13 | 具有长期记忆的个人学习规划 Agent | Spring Boot + Spring Scheduler + LangChain4j + PostgreSQL/pgvector | `java-study-planner` | 目标、学习证据、计划版本和记忆更新；使用时间推进样例验证重排、冲突与用户修改保留 |
| J15 | Java Agent 自动评测与监控平台 | Spring Boot + Micrometer/OpenTelemetry + Spring AI Advisor + Testcontainers | `java-agent-eval-monitor` | Java SDK/Starter 采集、批量评测和模型对比；验证接入开销、任务隔离、指标计算与重复执行 |

J1 表中保留原始标题的文字与标点以便核对清单。当前仓库没有引入 Spring AI；用户允许自主选择模型调用方式，因此现阶段用独立 HTTP Adapter 覆盖 Responses、Chat Completions 与模型发现。后续加入工具编排、向量检索或 Advisor 时，再评估 Spring AI/LangChain4j 版本。

## 每个独立项目的完成标准

每个项目应围绕一个能演示的核心任务形成完整流程：输入、持久化状态、执行、失败处理、结果和可追溯记录。README 需说明环境、启动、配置、样例数据与真实限制；数据库需有版本化迁移；业务与模型边界需有自动化验证；发布产物应可由干净环境重建。

选题的创新点需要可重复的对照实验支撑。根据项目分别记录任务成功率、来源准确率、误报/漏报、延迟、费用或评分一致性，保留样本与失败案例。不同项目的相似模块可以共享依赖，但各自必须有独立业务、数据和验收场景。

模型配置沿用可修改的 Provider 思路：模型 ID、基础 URL、协议、发现路径、超时和密钥环境变量名。Java、Go 与 Python 通过各自适配层实现同一契约；不支持模型发现的网关允许手动选择模型。真实网关地址、凭据和个人资料保留在运行环境。

## 本地参考目录的实际阅读记录

本次通过 `D:\coding-agents-catalog\_manifests\projects.csv` 定位仓库，针对性读取以下本地 README。目录清单的 `cloneStatus` 与实际落盘内容可能不同，选型以读到的文件为依据。

| 本地参考 | 本次读取 | 借鉴点与适用项目 |
| --- | --- | --- |
| `repositories/FoundationAgents/MetaGPT/README.md` | 多角色、SOP、配置与启动说明 | 需求产物与角色协作流程，用于 3；不照搬整套组织复杂度 |
| `repositories/OpenHands/OpenHands/README.md`、`LICENSE` | 当前 Agent Canvas 的前后端边界、可切换执行后端、模型设置和本地启动 | 作业/执行器分离、状态和配置界面，用于 4、G5、G14；读到的 README 已是 Agent Canvas，不作为旧版运行架构证据 |
| `repositories/SWE-agent/SWE-agent/README.md` | Issue 修复任务、配置与基准，并推荐 mini-swe-agent | 修复任务的输入、补丁和评测流程，用于 4；遵循其当前后继项目提示 |
| `repositories/SWE-agent/mini-swe-agent/README.md` | Agent、模型、环境拆分，轨迹与独立动作执行 | 沙箱执行、可回放轨迹、可比较的基线，用于 4、G5、14 |
| `repositories/vercel-labs/agent-browser/README.md` | snapshot/元素引用、动作失败、重新观察和浏览器安装 | 观察—动作—再观察及页面变化恢复，用于 6、G2；参考项目本身采用 Rust/Node，两个项目按本表使用 Python/Go 实现 |

上述记录描述实际读到的设计内容，未复制这些项目的代码。若后续复用代码或资产，应以对应版本的 LICENSE 和 NOTICE 为准。框架版本需要在各项目建立时单独锁定；不能由参考项目的宣传性基准结果推断新项目效果。

## 后续选版的官方入口

以下是各框架的官方仓库入口，用于后续依赖兼容和版本核验；并非声称它们均已包含在本地参考目录或已接入当前项目。

- Java：[Spring Boot](https://github.com/spring-projects/spring-boot)、[Spring AI](https://github.com/spring-projects/spring-ai)、[LangChain4j](https://github.com/langchain4j/langchain4j)、[MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)、[Flowable](https://github.com/flowable/flowable-engine)。
- Go：[Eino](https://github.com/cloudwego/eino)、[Chi](https://github.com/go-chi/chi)、[mcp-go](https://github.com/mark3labs/mcp-go)、[chromedp](https://github.com/chromedp/chromedp)、[Cobra](https://github.com/spf13/cobra)、[Asynq](https://github.com/hibiken/asynq)。
- Python：[FastAPI](https://github.com/fastapi/fastapi)、[LangGraph](https://github.com/langchain-ai/langgraph)、[Playwright Python](https://github.com/microsoft/playwright-python)、[SQLAlchemy](https://github.com/sqlalchemy/sqlalchemy)。
- 观测与测试：[OpenTelemetry](https://github.com/open-telemetry)、[Testcontainers Java](https://github.com/testcontainers/testcontainers-java)、[OWASP Dependency-Check](https://github.com/dependency-check/DependencyCheck)。
