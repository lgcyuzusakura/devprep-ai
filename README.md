# DevPrep AI

面向计算机基础和 Java 面试复习的本地学习工具。可练习示例题、记录答题与复习时间、导入自己的笔记，并用可配置模型解释检索到的资料。

当前是可运行的个人学习版本：React/Vite 前端、Python 3.11+ 标准库后端与 SQLite。没有模型 Key 也能练题、查看答题历史与资料命中原文。

## 当前功能

- 内置 13 个知识点、16 道示例题，覆盖 Java 基础、集合、并发/JVM、网络与数据库。
- 单选题按参考答案判分；简答题按参考要点自评，记录得分和下一次复习时间。
- 学习概览、知识点列表、前置知识与选题理由、待复习主题与答题历史。
- 导入、查看 Markdown/纯文本资料，按章节切分并进行词项检索；支持删除用户导入资料。
- 公开学习资源目录：覆盖前端、Java、后端、Go、Python、AI、Agent、计算机考研 408 与软考，可按方向和关键词筛选并打开来源。
- 可选的带资料来源问答，支持 Responses 与 Chat Completions 两类接口。
- 表单/TOML 两种 Provider 配置方式、当前 Codex 自定义 Provider 导入及模型列表自动发现。

## 快速启动

准备 Python 3.11+ 和 Node.js 20+（推荐 Node.js 22）。进入仓库目录后运行：

```powershell
# Windows PowerShell
.\scripts\start.ps1
```

```sh
# Linux / macOS
python3 scripts/start.py
```

也可以在 Windows 双击 `scripts/start.cmd`，或在任意平台使用 `python scripts/start.py`。如果 PowerShell 策略阻止 `.ps1`，直接使用 `.cmd` 或 Python 命令。

启动器会安装缺失的前端依赖、执行 `npm run build`，然后在前台运行后端。打开 [http://127.0.0.1:8765](http://127.0.0.1:8765)，用 `Ctrl+C` 停止。

```sh
python scripts/start.py --port 8766
python scripts/start.py --skip-build
python scripts/start.py --install
```

`--port` 更换服务端口；`--skip-build` 使用已有 `dist/`；`--install` 重新安装锁定依赖后构建。首次安装需要访问 npm，后端无第三方 Python 依赖。

## 开发与测试

```sh
# 终端 1：本地 API，默认 8765
python backend/devprep_server.py

# 终端 2：Vite 开发界面，默认 5173
npm ci
npm run dev
```

Vite 将 `/api` 代理到 `127.0.0.1:8765`。开发时若更换后端端口，应同步调整 `vite.config.js`；使用构建后的单服务启动器无需代理。

```sh
python -m unittest discover -s tests -v
npm run build
npx playwright install chromium
npm run test:e2e
```

GitHub Actions 在 Ubuntu 和 Windows 上执行后端测试、前端构建及启动器参数检查；Ubuntu 还安装 Chromium 并运行 Playwright 端到端测试，不依赖真实模型凭据。

## 公开学习资源

`data/seed/resources.json` 只保存公开资料的索引信息：标题、学习用途、方向、来源链接和许可证提示。资料库页面通过 `/api/resources` 加载目录，筛选后在新标签页打开原始来源；项目没有复制外部网站的大段正文。许可证字段是便于复习时判断来源的提示，引用代码或转载内容前应再次查看来源页面和仓库的最新 LICENSE。

<a id="model-provider"></a>

## 模型与 Provider

在“模型设置”中填写 Provider 表单或编辑 TOML。保存后自动加载模型列表，也可手动刷新；选择模型并保存即可用于资料问答。模型名来自 Provider，未写死在前端；不提供 `/models` 的网关可在表单的“模型名”或 TOML 顶部填写模型 ID。

下面是公开接口的示例。`model` 初始为空，选定模型后会写入实际 ID；第三方兼容网关请替换地址与协议。

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

| 字段 | 含义 |
| --- | --- |
| `model_provider` | 当前 Provider 表的 ID，例如 `openai` |
| `model` | 调用的实际模型 ID，可从发现列表选择 |
| `base_url` | API 基础地址，通常包含 `/v1`；不要重复附加 `/responses` |
| `wire_api` | `responses` 或 `chat_completions`；`chat` 也会归一化为后者 |
| `env_key` | 后端读取的环境变量名称，不是密钥内容 |
| `models_path` | 相对基础地址的模型发现路径，默认 `/models` |
| `timeout_seconds` | 请求超时，允许 2-120 秒 |

模型发现请求由后端发送，使用 `GET base_url + models_path`。返回值支持裸数组或带 `data`/`models` 数组的 JSON，数组元素可以是模型 ID 字符串或 `{"id":"..."}` 对象；列表缓存 5 分钟，刷新可跳过缓存。返回的 ID 不保证都适合文本生成，调用协议和权限需与网关一致。

### 密钥

启动服务前在同一终端设置环境变量。PowerShell 可使用隐藏输入：

```powershell
$env:OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', (Read-Host 'API Key' -AsSecureString)).Password
.\scripts\start.ps1
```

Linux/macOS 的 Bash：

```bash
read -rsp 'API Key: ' OPENAI_API_KEY
export OPENAI_API_KEY
python3 scripts/start.py
```

后端只读取当前 Provider 的 `env_key` 指定变量。例如使用 `DEVPREP_API_KEY` 时，需在 TOML 中设置 `env_key = "DEVPREP_API_KEY"`；留空则不发送鉴权密钥。更改终端环境后需重启服务。`.env.example` 是变量清单模板，当前服务与启动器不会自动加载 `.env`。

### 导入 Codex TOML

“导入 Codex 配置”从 `$CODEX_HOME/config.toml` 读取；未设置 `CODEX_HOME` 时读取用户目录下的 `.codex/config.toml`。导入先显示配置，保存后才应用到 DevPrep。

本项目只导入顶部 `model`、`model_provider` 和当前选中的自定义 `model_providers.<id>`，保留 `name`、`base_url`、`wire_api`、`env_key`；没有显式 `env_key` 且 `requires_openai_auth = true` 时会映射到 `OPENAI_API_KEY`。不读取 `auth.json`、登录令牌、MCP 配置或其他 Codex 设置，也不会修改 Codex 文件。

若原配置依赖内置 Provider、配置 profile 或登录令牌而未声明自定义 Provider，请在 DevPrep 中补全上述示例和环境变量。这里是本项目支持的兼容子集，Codex 的完整配置请参照 [官方配置参考](https://developers.openai.com/codex/config-reference/)。

## 数据与配置

启动时会在本机数据目录初始化示例资料和题目。导入资料、答题与复习记录存入 `devprep.sqlite3`；模型配置保存为 `settings.toml`，密钥只从服务进程环境读取。

| 系统 | 默认数据目录 |
| --- | --- |
| Windows | `%LOCALAPPDATA%\DevPrep AI\` |
| Linux / macOS | `$XDG_DATA_HOME/devprep-ai/`，或 `~/.local/share/devprep-ai/` |

| 环境变量 | 用途 |
| --- | --- |
| `DEVPREP_DATA_DIR` | 覆盖整个数据目录 |
| `DEVPREP_DB_FILE` | 覆盖 SQLite 文件路径 |
| `DEVPREP_CONFIG_FILE` | 覆盖 TOML 文件路径 |
| `DEVPREP_HOST` | 监听地址，默认 `127.0.0.1` |
| `DEVPREP_PORT` | 服务端口，默认 `8765` |

备份时先停止服务，再复制 SQLite 与 TOML 文件。个人资料和配置应保存到仓库外；提交前检查差异，保留 `.env`、SQLite、运行时 `settings.toml`、本地 `.codex/`、`node_modules/` 与 `dist/` 的忽略规则。公开配置示例是 `config/settings.example.toml`；若用 `DEVPREP_CONFIG_FILE` 将配置改名并保存在仓库内，需把该文件的路径加入 `.gitignore`。

启用模型问答时，问题和命中的资料片段会发给选中的 Provider；本地练题、统计和资料检索不要求模型联网。

## 当前限制

- 当前题库是示例题；导入资料只增加检索内容，不会自动生成题目或新知识点。
- 简答题使用参考要点自评，没有模型自动打分；复习时间是规则建议，不是经实验验证的掌握度模型。
- 检索使用词项匹配，没有 Embedding、BM25/向量混合检索或跨文档知识图谱；资料未命中时返回无来源提示。
- 模型问答展示资料来源，但未做逐句引用核验，仍应核对原文。
- 未实现算法代码 Judge、语音面试、简历编辑、多 Agent 调度或完整工具规划循环。
- 当前为单用户本地服务，没有账号权限或公网部署认证，默认只监听本机。

## 仓库结构

```text
backend/                 Python API 与 SQLite
config/                  公开 TOML 示例
data/seed/               示例学习资料
data/seed/resources.json 公开学习资源索引（仅元数据）
src/                     React 界面
scripts/                 跨平台启动器与包装脚本
tests/                   后端 unittest 与 Playwright 端到端测试
.github/workflows/        构建与测试 CI
```

## 许可证

仓库采用 [MIT License](LICENSE)。React、Vite、Lucide 等第三方依赖保留各自许可证；导入的外部学习材料应保留来源与授权信息。
