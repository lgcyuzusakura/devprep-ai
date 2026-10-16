# 使用指南

DevPrep 是面向个人使用的计算机学习工具，包含题库练习、算法、学习 Agent、简历工作区和文字模拟面试。默认网址为 <http://127.0.0.1:8765>。

新增「求职工作台」：从通用简历母版按 JD 生成每个岗位的简历、开场白、自我介绍与跟进草稿，下载后可手动投递。导入岗位、版本与附件、浏览器助手、进展记录的完整操作见 [求职工作台使用方法](job-search.md)。

## 方式一：私人迁移包（推荐给另一台电脑）

适用于 x86_64/amd64 Windows Docker Desktop（Linux 容器模式）与 Linux。包内已有应用镜像、算法运行镜像、源码和本地题库，不需要安装 JDK、Node.js、Maven，也不需要重新抓取资料。ARM 平台尚未验证。

1. 安装并启动 Docker，将 ZIP 完整解压到一个固定文件夹。
2. 复制 `.env.example` 为 `.env`，填写 `OPENAI_API_KEY`；不使用 AI 时可暂时留空。
3. 编辑 `config/settings.toml`，设置自己的网关 URL、协议和实际可用的 GPT 模型 ID。此文件不填写密钥。
4. Windows 双击 `start.cmd`；Linux 在解压目录执行 `sh start.sh`。等待镜像导入及健康检查完成。
5. 打开 <http://127.0.0.1:8765>。如果修改了 `.env` 中的 `DEVPREP_PORT`，请使用修改后的端口。

也可以在解压目录手动启动：

```sh
docker load -i images.tar
docker compose up -d --wait --wait-timeout 180
docker compose ps
```

私人包中的 `content/` 提供本地知识题库、算法题库、面经题库及来源说明。包内不包含原电脑的密钥、私有网关配置、个人简历、聊天或答题记录；启动后创建新的数据库。`SHA256SUMS.txt` 和 ZIP 旁的 `.sha256` 可用于校验传输完整性。

## 方式二：从 GitHub 源码部署

公开仓库提供代码、文档、原创示例与导入工具。第三方题库正文和私人迁移 ZIP 不随 GitHub 发布。

```sh
git clone https://github.com/lgcyuzusakura/devprep-ai.git
cd devprep-ai
```

如需 AI，将根目录 `.env.example` 复制为 `.env`，填写 `OPENAI_API_KEY`。随后执行：

```sh
docker compose up -d --build --wait --wait-timeout 180
```

首次构建下载依赖，需要网络。需运行算法代码时，逐个准备语言镜像：

```sh
docker pull maven:3.9-eclipse-temurin-17
docker pull golang:1.24-alpine
docker pull python:3.12-alpine
docker pull node:22-alpine
```

打开网页配置模型。公开源码启动时只有原创示例；导入更多内容参见 [本地知识包](content-import/local-learning-pack.md)、[算法与 Docker](docker-and-learning-agent.md)、[面经导入](mock-interview.md)。下载的第三方资料保存在本机忽略目录，需保留各自来源与权利说明。

## 配置 GPT 与 API 网关

在网页“模型设置”中填写 Provider、`base_url`、协议和模型 ID，也可以编辑 Codex 风格的 TOML。网关支持 `/models` 时可获取模型列表；不支持时手填实际可用的模型 ID。

```toml
model = "替换为网关实际支持的GPT模型ID"
model_provider = "gateway"

[model_providers.gateway]
name = "我的网关"
base_url = "https://your-gateway.example/v1"
wire_api = "responses"
env_key = "OPENAI_API_KEY"
models_path = "/models"
timeout_seconds = 120
```

上方域名为占位符；请替换为自己的服务地址。网关只支持 Chat Completions 时，将 `wire_api` 改为 `chat_completions`。`base_url` 不要追加 `/responses` 或 `/chat/completions`。网关在宿主机上运行时，Docker Desktop 中可用 `host.docker.internal` 访问宿主机；容器里的 `127.0.0.1` 指向容器自身。

迁移包首次启动会把 `config/settings.toml` 复制进持久数据卷。之后在网页保存配置即可；修改外部模板不会覆盖已有设置。修改 `.env` 中的密钥后执行：

```sh
docker compose up -d --force-recreate app
```

模型请求串行执行，默认每次请求完成后间隔 10 秒，不自动切换到 DeepSeek。题库浏览、普通练习、离线面试抽题、手工简历编辑与导出无需模型；AI 讲解、生成、扩写和判分需要有效模型配置。详细字段见 [README 模型配置](../README.md#model-provider)。

## 日常使用

| 功能 | 使用方法 |
| --- | --- |
| 题库练习 | 选择方向与知识点，完成选择题或简答自评；记录进入学习历史与复习安排。部分原文简答使用掌握程度自评。 |
| 算法 | 打开 `#/algorithms`，筛选 Hot 100、题号前 100 或其他题单，选择 Java、Go、Python、JavaScript 后编写代码。运行仅验证实际执行的样例，复杂接口可打开官方题目。 |
| 学习 Agent | 打开“学习教练”，直接提问，如“解释 Redis 持久化，然后出一道题”。支持连续追问、工具检索、题卡、停止生成及重新回答。 |
| 简历 | 打开 `#/resume`，从零编写、普通修改或填写目标 JD 定向修改；可选择版式、调整章节顺序、局部扩写、比较历史版本，导出 Word、Markdown 或打印 PDF。示例简历均为虚构，待核验建议需要自己确认。 |
| 模拟面试 | 打开 `#/interview`，选择岗位、经验、难度、题型和追问强度。题库随机模式离线抽题；JD 定向模式结合 JD 与本人项目材料生成问题。AI 判分、追问和复盘需要模型。 |

模拟面试分基础、进阶、高阶，支持小厂、中厂、大厂训练侧重；这些是产品训练配置。面经保留来源及核验状态，候选人回忆不代表官方标准。第三方原文仅保存在本地包中，来源见 [本次面经更新](updates/2026-10-15-domestic-interview-memories.md)。

## 停止、升级和备份

在相应部署目录执行：

```sh
docker compose stop
docker compose up -d
docker compose logs --tail 100 app
```

数据与网页保存的模型设置放在 Docker 卷中，普通停止或 `docker compose down` 保留数据；`down -v` 会删除数据卷。备份个人数据前先停止 app，备份对应数据卷以及自己的配置。私人迁移包是新电脑的初始安装包，后续产生的个人数据需要另行备份。

源码部署升级用 `git pull` 后执行 `docker compose up -d --build --wait --wait-timeout 180`。升级私人包时停止旧服务、用新包替换应用和题库文件，保留原 `.env`、使用相同 Compose 项目名称，再加载新镜像并启动；换文件夹或项目名可能创建新数据卷。

## 常见问题

- 无法打开网页：运行 `docker compose ps` 和 `docker compose logs --tail 100 app`，确认 Docker 正在运行且服务健康；检查端口是否被占用。
- 模型列表为空：检查网关 URL、密钥及 `/models` 支持情况，可以手动填写模型 ID。
- AI 提示鉴权或模型错误：确认 `.env` 的密钥、TOML 的 `env_key`、协议和实际模型 ID一致；修改密钥后重建 app 容器。
- 算法不能运行：确认 runner 健康、对应语言镜像已导入，并检查该题是否支持本地执行。
- 解压后缺少大量题目：确认是在迁移包根目录启动，`content/` 未遗漏；从公开 GitHub 启动只有原创示例，需自行导入本地包。

进一步说明：[简历工作区](resume-editing.md)、[模拟面试](mock-interview.md)、[Docker 与学习 Agent](docker-and-learning-agent.md)。
