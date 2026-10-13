# Docker 与计算机学习 Agent

应用采用 Spring Boot，学习会话、消息、练习进度与算法提交保存在同一个持久数据库里。前端提供“学习教练”和独立“算法练习”入口。

## 启动 Docker

安装 Docker Desktop，并准备四种语言运行镜像：

```sh
docker pull python:3.12-alpine
docker pull node:22-alpine
docker pull golang:1.24-alpine
docker pull maven:3.9-eclipse-temurin-17
docker compose up -d --build
```

打开 http://127.0.0.1:8765 。默认只在本机开放端口。应用镜像包含前端与 Java 后端；runner 为内部判题服务，不开放宿主机端口。数据库和模型配置保存在 `devprep-data` 卷中，`docker compose down` 会保留；不要在需要保留学习记录时使用 `down -v`。

已有本机 JDK/Node 构建产物时，可将 Compose 的 app.build.dockerfile 改为 `Dockerfile.runtime`，复用已经验证的 Jar，避免容器里重复下载构建依赖。

迁移已有本地数据库前先停止本机 Java 服务、备份数据库，再把本机 `.devprep-ai` 目录挂载到 `/data`。本机服务和容器不能同时打开同一 H2 文件。此电脑的路径、题库挂载等保存在忽略目录 `.cache/docker.local.yaml`：

```sh
docker compose -f compose.yaml -f .cache/docker.local.yaml up -d --build
docker compose -f compose.yaml -f .cache/docker.local.yaml ps
docker compose -f compose.yaml -f .cache/docker.local.yaml logs --tail 100 app
```

密钥使用环境变量 `OPENAI_API_KEY` 传入。自定义 Provider、网关地址和模型名仍在“模型设置”中修改；只挂载 Codex `config.toml` 即可导入 Provider，不需要挂载认证文件。容器中 `CODEX_HOME` 应指向所挂载配置的目录。

## 学习教练

参考本机 `D:\coding-agents-catalog` 中 OpenCode 的会话/工具流程、Kimi Code 的输入框与取消交互、DeepSeek Harness 的连续对话与滚动恢复，独立用 Java 实现学习用途的 Agent。参考目录对应 `MoonshotAI/kimi-cli/web/src/features/chat` 和 `deepseek-ai/deepseek-harness/apps/web/tests`；实现没有复制它们的代码、运行它们的 CLI 或绑定它们的模型。

支持知识辅导、互动出题、模拟面试和算法指导。默认是自由对话，可自然地从讲知识点切换到出题、点评和追问；模式只提供初始偏好。会话保存在服务端，可搜索、重命名与删除。输入草稿按会话保存在浏览器，回答期间可继续编辑下一条消息。

消息支持 Markdown、表格、代码和公式，可复制消息与代码。工具运行过程按顺序显示，结果默认折叠；出题时显示独立题卡，选择选项或点击“在对话里作答”可把答案带入输入框。题库工具不提前提供答案；助教收到作答后可读取该题参考答案，给出点评。对话中的点评不会自动写成练习成绩，正式评分/自评仍通过练习页保存。

可调用的工具有本地笔记检索、中文题库检索、学习进度、知识点查询、选题、参考答案和算法题面。每轮读取最近20条消息与其中的题卡作为上下文；完整历史保存在数据库。每个会话最多300条消息，达到后应新建会话。

模型兼容 Responses 与 Chat Completions 原生函数调用和 SSE 文本流。文字由模型生成后立即显示；只返回普通 JSON 的网关在同一次请求内显示完整回答。每轮最多三轮工具调用，随后要求回答；单次最多四次模型请求。全应用一次只处理一条学习消息，关闭并行工具调用，前一个模型请求结束后至少等待10秒再发下一个请求。不会后台翻译题库。

“停止生成”会中断正在等待的模型请求，并保留已收到的文字与题卡；中断和错误均保存到历史。点击最后一条回答的“重新回答”重用上一条用户消息，不会插入重复用户消息。刷新页面后仍在处理的回合会显示等待状态并定期读取最终结果，不会再次发送模型请求。阅读旧消息时不会被新内容强制拉到底部，可点击“最新消息”返回。

自动测试使用本地模拟网关验证流式中文、分段工具参数、停止、重试、题卡和上下文，不发送真实模型请求。浏览器测试将测试进程的请求间隔设为0，仅用于本地模拟；正常服务和 Docker 保持10秒间隔。

没有配置模型时提供本地检索与配置引导。真实互动讲解需要在模型设置中配置可用模型、网关与密钥。默认沿用用户配置，不自动切换到 DeepSeek。

## 算法题

题单覆盖以下官方合集，共348道去重原题；另外提供12道原创本地练习，用于完整边界测试。页面从后端读取题单名称与实际数量，支持题单、分类和题号/标题筛选。同一道题属于多个题单时复用题面、代码草稿与提交记录。

| 题单 | 题数 |
| --- | ---: |
| LeetCode Hot 100 | 100 |
| 题号1–100 | 100 |
| LeetCode 75 | 75 |
| 面试经典150 | 150 |
| 编程基础 | 33 |
| 动态规划 | 46 |
| 图论 | 36 |
| 二分查找 | 32 |

公开仓库仅保存题单元数据、原创练习和导入工具。原题中文题面、官方语言模板与样例存放在忽略目录，使用以下命令自行导入：

```sh
python scripts/content-import/leetcode.py
```

脚本从 LeetCode 中国站公开 GraphQL 获取信息，一个进程逐个请求，默认在前一次请求结束后至少等待2秒，不调用任何模型。已经下载的题单与题目直接使用缓存，失败后重新执行即可续跑。`--delay 5` 可进一步放慢请求；`--offline` 仅从缓存重建题库，不发送网络请求。

输出 `.cache/content/leetcode.learning.json`；Node 启动器自动读取。Docker 挂载文件并设置 `DEVPREP_ALGORITHM_FILE=/content/leetcode.learning.json`，更新本地题库后重新创建 app 容器加载。部分会员题或复杂接口仅提供元数据与官方入口。没有中文题面时显示说明，不回退到英文题面，也不调用模型翻译。

Java 17、Go 1.24、Python 3.12、JavaScript/Node.js 22 使用不同代码模板。题目与语言草稿独立保存。编辑器提供高亮、缩进、补全、折叠和行号；支持分类、题号搜索、分级提示、解题思路、提交记录和恢复代码。

常规函数、链表和二叉树题可以运行公开样例。涉及共享节点、设计类、交互接口等暂未适配的题目保留官方模板与原题入口，不提供本地执行按钮。本地公开样例通过仅说明这些样例通过，最终正确性应到 LeetCode 官方判题验证。本地原创题提交时会额外运行不展示输入的边界测试。

每次运行创建临时语言容器，关闭网络、使用只读根文件系统、非 root 用户、去除 capabilities，限制1 CPU、512 MB、128进程和每例3秒；编译最多35秒，结束后删除临时容器。应用容器没有 Docker socket；仅内部 runner 拥有创建判题容器所需的 socket。这里是个人学习工具，runner 不应公开到网络。
