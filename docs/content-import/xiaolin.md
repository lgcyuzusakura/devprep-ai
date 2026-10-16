# 小林 coding 本地题库

小林 coding 官网为 <https://xiaolincoding.com/>，公开面试题目录为 <https://xiaolincoding.com/interview/>，作者公开图解资料仓库为 <https://github.com/xiaolincoder/CS-Base>。

本地导入器只读取公开面试题文章，逐页串行获取，默认间隔 1.5 秒，不调用模型，不登录，不处理付费内容。题干及参考答案保持中文，Java、Redis 等专有名词保留；原文 HTML 转为 Markdown，嵌套小节、代码块、列表、表格保留。题目使用原文标题，不把文章宣传内容编成题目。

## 使用

```powershell
python -m pip install -r scripts/content-import/requirements.txt
python scripts/content-import/xiaolin.py --fetch
```

输出默认在 Git 忽略的 `.cache/content/xiaolin/`：

- `html/`：公开文章 HTML 快照
- `xiaolin.learning.json`：独立题库
- `xiaolin.audit.json`：逐篇 URL、SHA-256、时间、条数和失败记录

合并进已有本地题库：

```powershell
python scripts/content-import/xiaolin.py --merge .cache/content/local.learning.zh-CN.json
```

合并前自动保存带内容哈希的旧题库备份，原有题目和翻译元数据保留。重复执行只替换 `local-xiaolin-` 前缀的数据，不重复叠加相同题目。文章以缓存为准，需要更新时先备份、再移走指定文章的缓存文件并重新 `--fetch`。

只要有一篇下载或解析失败，脚本就退出并保留成功缓存，不合并部分结果；先核对 `xiaolin.audit.json` 再重试。题量不照抄官网的宣传数字，而以实际解析到有效中文题干和参考答案的数量为准。

应用在启动时读取 `DEVPREP_LOCAL_LEARNING_FILE` 指定的文件。本机已有 Docker 本地覆盖配置时，更新合并后的文件，再执行：

```powershell
docker compose -f compose.yaml -f .cache/docker.local.yaml up -d --no-build --no-deps --force-recreate --wait --wait-timeout 180 app
```

这个命令依赖该电脑已有的 `.cache/docker.local.yaml`，其他电脑请按自己的 Compose 挂载配置操作，不要复制他人的绝对路径。导入无需重建应用镜像。

## 本轮实际结果

2026-10-16 从 23 篇公开问答文章解析 **1,653 道题、131 个知识分组**；合并后的本地题库为 **11,663 道题、314 个知识分组**，另有独立的算法与模拟面经库。应用接口已读到新增分组，并能返回题目与中文参考答案。

覆盖 Java 基础/集合/并发/JVM/Spring、C++、Go、Python、MySQL、Redis、操作系统、计算机网络、数据结构与算法、消息队列、分布式、系统设计、Linux/Git/Docker，以及业务、自动化、性能测试。

另下载了作者公开仓库的 `381b846137c4b7c25373804f44eb44ed8a31ea55` 快照，保存在本机 `.cache/content/xiaolin/`，供查看图解 Markdown；本轮题数仅统计面试问答，不把整篇文章和问答重复计数。AI/Agent 专题指向另一网站，本轮未导入这些内容。

## 出处和分发

每题保存原始 URL（含章节锚点）、文章路径、作者署名、语言、抓取时间和文件 SHA-256。没有发现该内容可按 MIT/Apache 等自由再分发，故标为作者保留版权，下载原文、生成的第三方题库和私人 ZIP 不提交公共仓库。

正文可离线练习，原图仍是来源链接，离线时不能保证显示；本轮没有镜像图片。HTML 转换可能改变排版，遇到含复杂图示的题目可点击原文核对。题目属于导入参考答案、自评模式，没有声称逐题人工核验，也不证明“大厂统一按这个题库出题”。
