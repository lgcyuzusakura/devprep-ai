# DevPrep AI

面向计算机基础、考试与技术面试复习的本地学习工具。支持练题、答题记录、复习提醒、个人笔记检索，以及带资料来源的模型问答。公开资料索引覆盖前端、Java、后端、Go、Python、AI、Agent、考研 408 与软考。

主项目采用 **Java 17 + Spring Boot 3.4.5**，前端使用 React/Vite。学习流程和模型配置已迁移到 Java，默认启动器、测试入口和 CI 使用 Spring Boot。最近一轮验证通过 247 项 Java 测试、37 项原有浏览器流程与 2 项独立账号浏览器流程；内容导入模块此前 36 项测试通过。架构说明见 [ADR 0001](docs/architecture/adr-0001-backend.md)，其余项目的框架与独立仓库规划见 [27 个项目技术栈规划](docs/project-stack-plan.md)。

完整操作步骤见 **[使用指南](docs/usage.md)**，包含 Docker 部署、私人迁移包、模型网关配置、练习、算法、学习 Agent、简历和模拟面试。多人体验支持独立账号、数据隔离与改密，见 [账号部署说明](docs/accounts.md)。

学校电脑持续运行、家里通过公网网址访问的方案与现有限制，见 [公网访问调研](docs/research/2026-10-16-public-url-and-xiaolin.md)。该文记录部署前调研，实际入口与凭据保存在部署机器的私有文件中。

## 技术架构

| 层 | 当前选型 |
| --- | --- |
| 前端 | React、Vite、Lucide、Markdown 渲染 |
| API 与业务 | Spring Boot、Spring Web、Bean Validation、模块化单体 |
| 持久化 | Spring JDBC；默认文件型 H2；可选 PostgreSQL profile |
| 数据库版本 | Flyway，迁移脚本随 Jar 打包 |
| 模型接入 | Java HttpClient + 项目内 ModelGateway 接口 |
| 配置 | Codex 风格 TOML；密钥通过环境变量读取 |
| 构建与验证 | Maven Wrapper、JUnit/MockMvc、Mock HTTP、Playwright |

资料、题库、练习、复习和模型设置分别由应用服务维护。单选评分、简答自评和复习排期使用确定性规则。模型通过独立网关参与有来源的问答，当前没有引入 Spring AI 或 LangChain4j；后续需要工具编排和向量检索时可在这个边界接入。

## 功能

- 求职工作台：通用简历母版按岗位 JD 生成专属简历与沟通话术，支持编辑版本、Word/Markdown/话术/ZIP 下载、独立附件、公开 ATS 岗位预览、浏览器辅助导入和实际投递记录。详见 [求职工作台](docs/job-search.md)。
- 模拟面试：岗位随机抽题、JD + 简历组卷、三档难度、公司练习场景、离线面经检索、八股/算法/项目深挖、四维判分、逐轮追问与复盘导出。详见 [模拟面试](docs/mock-interview.md)。
- 简历工作区：从零编写、普通修改与 JD 定向修改、局部 AI 扩写、经历诊断与增强设想、5 种版式及自定义章节顺序、8 个技术方向原创虚构示例、修改对照与历史版本，以及 Word/Markdown/PDF 导出。详见 [简历修改](docs/resume-editing.md)。
- Docker Compose 部署、数据持久化，以及隔离容器中的多语言算法样例运行。
- 学习教练：持久会话、连续追问、资料检索、互动出题、模拟面试和学习进度工具。
- 独立算法页面：LeetCode Hot 100、题号前100、LeetCode 75、面试经典150及编程基础/动态规划/图论/二分专项；Java/Go/Python/JavaScript 编辑器和代码草稿。
- 学习对话 Agent：连续追问、流式回答、停止与重新回答、工具检索、互动出题与点评；支持会话搜索/重命名、草稿、代码复制和手机布局。详见 [Docker 与学习 Agent](docs/docker-and-learning-agent.md)。

部署和交互说明见 [Docker 与学习 Agent](docs/docker-and-learning-agent.md)。LeetCode 原题正文仅导入本地，公开仓库保留题单元数据和导入器。

- 内置知识点、示例题和学习资料，支持单选练习与简答要点自评。
- 本地多来源题库导入、出处与版本追踪；原文问答支持独立掌握自评。
- 学习概览、前置知识、选题理由、待复习主题和答题历史。
- Markdown/纯文本资料导入、按章节切分、词项检索、查看与删除。
- 公开学习资源按方向和关键词筛选，并打开原始来源。
- 可选资料问答，支持 Responses 与 Chat Completions 协议。
- Provider 表单与 TOML 编辑、本机 Codex 自定义 Provider 导入、模型列表自动发现。
- 服务端参数校验、统一错误、本机来源检查和请求体积限制。

## 快速启动

推荐用 Docker：安装 Docker Desktop 并启用 Linux 容器，在源码根目录执行 `docker compose up -d --build --wait --wait-timeout 180`，然后打开 <http://127.0.0.1:8765>。首次构建需要网络。公开仓库包含原创示例及导入工具；私人迁移包另外包含本地题库与预构建镜像，按包内“先看这里-部署说明.md”启动即可。

以下为不使用 Docker 的本机源码启动方法。

准备 **JDK 17+** 和 **Node.js 20+**，推荐 Node.js 22。安装 JDK 后设置 `JAVA_HOME`，或把 `java`、`javac` 加入 PATH。无需预先安装 Maven；仓库内的 Wrapper 会下载固定版本。

在仓库根目录运行：

```powershell
# Windows PowerShell
.\scripts\start.ps1
```

Windows 也可双击 `scripts/start.cmd`。PowerShell 执行策略阻止脚本时，可直接使用下面的 Node 命令。

```sh
# 任意平台
node scripts/start.mjs
# Linux / macOS 也可使用
sh scripts/start.sh
```

启动器安装缺失的前端依赖、构建前端、通过 Maven Wrapper 打包 Spring Boot，然后在前台运行 Jar。首次构建需要访问 npm 与 Maven Central。打开 [http://127.0.0.1:8765](http://127.0.0.1:8765)，用 `Ctrl+C` 停止。

```sh
npm start
npm start -- --port 8766
npm start -- --skip-build
npm start -- --install
node scripts/start.mjs --help
```

`--skip-build` 要求已有 `dist/index.html` 与 `backend-java/target/devprep-backend-0.2.0-SNAPSHOT.jar`。`--install` 重新执行锁定依赖安装，再构建前后端。

## 开发与测试

开发时可分别启动 API 与 Vite。终端 1 进入 `backend-java/`：

```powershell
# Windows
.\mvnw.cmd spring-boot:run
```

```sh
# Linux / macOS
sh ./mvnw spring-boot:run
```

终端 2 在仓库根目录运行：

```sh
npm ci
npm run dev
```

Vite 默认把 `/api` 代理到 `127.0.0.1:8765`。更换 API 端口时应同步调整 `vite.config.js`。

```sh
# Java 后端测试
npm test

# 题库导入器回归（仅使用合成样例，不下载题库）
python -m pip install -r scripts/content-import/requirements.txt
npm run test:imports

# 前端构建与浏览器回归
npm run build
npx playwright install chromium
npm run test:e2e
```

Playwright 默认构建并启动 Spring Boot，使用临时 H2 数据库和 TOML 文件，等待健康接口后执行练习、学习记录、资料与模型设置流程。已有前后端构建时，可在终端设置 `DEVPREP_E2E_SKIP_BUILD=1` 复用产物。

GitHub Actions 在 Ubuntu 和 Windows 上执行 Java 17/Maven Wrapper 验证、前端构建与启动器参数检查；Ubuntu 还运行 Chromium 浏览器回归。模型协议测试使用本机 Mock HTTP 服务，不需要真实模型凭据。

`backend/`、`scripts/start.py` 和 Python unittest 保留为迁移对照；`npm run test:legacy` 可以验证旧实现。Python 原型的测试结果不能代表 Spring Boot 的回归结果。

## 公开学习资源

`data/seed/resources.json` 保存标题、学习用途、方向、来源链接和许可证提示。资料库通过 `/api/resources` 加载目录，筛选后打开原始来源。索引不包含外部网站的大段正文；引用代码或转载材料时应查看来源的当前许可证。

## 本地题库

公开仓库提供 40 个知识点、70 道标注 AI 辅助整理来源的原创示例题，以及导入工具。本地题库可以扩充到数千道实际问答；下载快照、题目正文与审计导出全部保存在 Git 忽略的 `.cache/content/`，不会打包到 Jar 或上传仓库。

- [多来源下载与导入](docs/content-import/local-learning-pack.md)：JavaGuide、JavaScript、React、Go、Python、DevOps。
- [小林 coding 本地导入](docs/content-import/xiaolin.md)：中文公开面试问答、来源追踪和保留原译文的合并；第三方正文仅在本地保存。
- [中文大模型问答导入](docs/content-import/llm-interview-note.md)：模型基础、微调、RAG、评估和应用。
- [考研 408 与软考题库导入](docs/content-import/exams.md)：文本单选题、完整案例与来源审计。
- [DevOps 导入说明](docs/content-import/devops-exercises.md)：保留原文与固定版本的来源信息。

准备好本地快照后运行：

```sh
python scripts/content-import/multi_source.py
npm start
```

Node 启动器优先读取 `.cache/content/local.learning.zh-CN.json` 中文翻译包，其次读取 `local.learning.json` 和 `devops.learning.json`，并与内置种子一起导入数据库。练习接口只提供中文展示内容，题目仍保留来源的原始语言与出处；同 ID 的译文会更新题面和参考答案并保留学习记录。翻译方法见 [本地题库中文翻译](docs/content-import/translation.md)。首页显示数据库中实际的题目数量。设置 `DEVPREP_LOCAL_LEARNING_FILE` 可指定其他题库；直接运行 Java 或 Maven 时也应设置这个变量。

每题保留来源 URL、许可证声明、署名、语言、版本和文件摘要。原文简答没有人工评分要点时，使用“掌握 / 需复习”自评；该结果会单独标记在练习记录中，不声称是客观评分。题目未经过逐题事实审核，可打开原始来源核对。

重复启动不会覆盖已有题目、个人资料或作答记录。`npm start -- --builtin-only` 跳过本次本地包导入，已导入的题目继续保留。具体导入数量、来源和跳过原因可查看本机 `local.audit.json`。

<a id="model-provider"></a>

## 模型与 Provider

在“模型设置”中填写 Provider 表单或编辑 TOML。保存后自动加载模型列表，也可手动刷新。选择模型后保存，便可用于资料问答。模型名来自网关的返回值；不提供 `/models` 的网关也可手动填写模型 ID。

```toml
model = ""
model_provider = "openai"

[model_providers.openai]
name = "OpenAI"
base_url = "https://api.openai.com/v1"
wire_api = "responses"
env_key = "OPENAI_API_KEY"
models_path = "/models"
timeout_seconds = 30
```

第三方中转站或 API 网关请替换 `base_url`，并选择它支持的协议。公开模板见 `config/settings.example.toml`。

| 字段 | 含义 |
| --- | --- |
| `model_provider` | 当前 Provider 的 ID |
| `model` | 实际模型 ID，可从发现列表选择 |
| `base_url` | API 基础地址，通常包含 `/v1`；不要附加 `/responses` |
| `wire_api` | `responses` 或 `chat_completions`；`chat` 归一化为后者 |
| `env_key` | 后端读取的环境变量名称，不是密钥内容；可留空 |
| `models_path` | 相对基础地址的发现路径，默认 `/models` |
| `timeout_seconds` | 单次请求超时，允许 2–120 秒 |

模型发现由后端发送 `GET base_url + models_path`。支持裸数组和 `data`/`models` 数组，元素可为模型 ID 字符串或包含 `id` 的对象。目录默认缓存 5 分钟，刷新和保存配置可使旧缓存失效。发现到的 ID 不保证支持文本生成，需与所选协议及网关权限一致。

### 密钥

在启动服务的终端设置当前 Provider 的环境变量。PowerShell 可使用隐藏输入：

```powershell
$env:OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', (Read-Host 'API Key' -AsSecureString)).Password
.\scripts\start.ps1
```

Linux/macOS 的 Bash：

```bash
read -rsp 'API Key: ' OPENAI_API_KEY
export OPENAI_API_KEY
node scripts/start.mjs
```

例如使用 `DEVPREP_API_KEY`，需在 TOML 写 `env_key = "DEVPREP_API_KEY"`。该字段留空时不发送鉴权密钥。更改进程环境后需重启服务；服务和启动器不会自动加载 `.env`。`.env.example` 是环境变量清单。

### 导入 Codex TOML

“导入 Codex 配置”读取 `$CODEX_HOME/config.toml`；未设置 `CODEX_HOME` 时读取用户目录下的 `.codex/config.toml`。导入先在编辑器显示，保存后才应用。

支持顶部 `model`、`model_provider` 和当前自定义 `model_providers.<id>` 的 `name`、`base_url`、`wire_api`、`env_key`。未声明 `env_key` 且 `requires_openai_auth = true` 时映射到 `OPENAI_API_KEY`。导入使用本项目的模型发现路径和超时默认值。

导入不会读取 `auth.json`、登录令牌或 MCP 配置，也不会修改 Codex 文件。依赖内置 Provider、profile 或登录令牌的配置，需要在 DevPrep 中补全自定义 Provider 与环境变量。本项目支持的是配置兼容子集，完整字段见 [Codex 官方配置参考](https://developers.openai.com/codex/config-reference/)。

## 数据与数据库

默认使用文件型 H2，数据目录为所有系统的 `~/.devprep-ai/`（Windows 对应 `%USERPROFILE%\.devprep-ai\`）。首次启动由 Flyway 创建表，学习种子和资源目录从 Jar 的 classpath 读取；答题记录与个人资料写入数据库，模型配置保存为 `settings.toml`。

| 环境变量 | 用途 |
| --- | --- |
| `DEVPREP_DATA_DIR` | 默认数据库与 TOML 的父目录 |
| `DEVPREP_DB_FILE` | H2 文件路径基名，例如 `D:/devprep-data/devprep`；不带 `.mv.db` |
| `DEVPREP_CONFIG_FILE` | TOML 文件路径 |
| `DEVPREP_LOCAL_LEARNING_FILE` | 与内置种子一起加载的本地题库 JSON |
| `DEVPREP_HOST` | 监听地址，默认 `127.0.0.1` |
| `DEVPREP_PORT` | 端口，默认 `8765` |
| `DEVPREP_ROOT` | 直接启动 Java 时的仓库根目录；Node 启动器自动设置 |
| `SPRING_PROFILES_ACTIVE` | 设置为 `postgres` 切换数据库 |

H2 会自动在基名后添加 `.mv.db`。备份时停止服务，再复制 H2 文件与 TOML。种子初始化不应覆盖个人资料或已有答题记录。

### PostgreSQL profile

准备已存在的 PostgreSQL 数据库及账户，设置连接信息：

```powershell
$env:SPRING_PROFILES_ACTIVE = "postgres"
$env:DEVPREP_DATABASE_URL = "jdbc:postgresql://127.0.0.1:5432/devprep"
$env:DEVPREP_DATABASE_USER = "devprep"
$env:DEVPREP_DATABASE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Database password' -AsSecureString)).Password
npm start
```

PostgreSQL 使用相同 Flyway 迁移和应用服务。这个 profile 切换持久化存储；当前服务仍面向本机单用户使用。PostgreSQL 运行验证与公网部署的账号权限需要另行完成。

旧 Python 原型的 SQLite 文件不会自动转换成 H2 或 PostgreSQL。已有个人数据时保留 SQLite 备份，使用旧后端读取；当前没有提供跨数据库的导入迁移工具。

个人资料和真实配置应存放在仓库外。`.gitignore` 已覆盖运行时 TOML、H2/SQLite、`.env`、`.codex/`、`target/`、`dist/` 与依赖目录。自定义配置文件名若放入仓库，需要加入相应忽略规则。

启用模型问答后，问题与命中的资料片段会发给选中的 Provider；练题、统计和本地资料检索无需模型联网。

## 当前范围与后续工作

- 内置示例与本地导入题库可用于完整学习流程；资料导入仍只增加检索内容，不自动生成题目或知识点。
- 简答题根据参考要点自评，没有模型自动评分；复习排期是规则建议。
- 检索采用词项匹配，尚未引入 Embedding、BM25/向量混合检索和知识图谱。
- 模型回答显示命中资料来源，仍需用户核对原文。
- 算法支持本地原创题判题和已适配原题的公开样例运行；复杂接口和会员题可能只提供原题入口，不能替代官方判题。简历编辑和文字模拟面试已实现；语音面试、多 Agent 调度及逐题人工审核尚未实现。
- 当前为本机单用户服务，没有面向公网的账号与授权体系。

## 仓库结构

```text
backend-java/            Spring Boot API、JUnit 测试、Maven Wrapper
backend-java/src/main/resources/db/migration/
                         Flyway 数据库迁移
backend/                 Python 原型（迁移对照）
config/                  公开 TOML 示例
data/seed/               学习种子与公开资源索引
src/                     React 界面
scripts/                 Node 启动器、平台包装脚本与旧 Python 启动器
tests/e2e/               Playwright 浏览器回归
.github/workflows/       跨平台构建与测试
docs/architecture/       架构决策
docs/content-import/     本地题库下载、解析与来源说明
docs/project-stack-plan.md
                         27 个独立项目的技术栈规划
```

## 许可证

仓库采用 [MIT License](LICENSE)。第三方依赖保留各自许可证；导入的外部材料应保留来源与授权信息。
