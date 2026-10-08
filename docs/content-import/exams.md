# 考研 408 与软考本地题库

`scripts/content-import/exams.py` 下载或读取已有的文本 JSON 快照，生成实际包含题干、选项和答案的本地练习包。数据写入 `.cache/content/`，该目录已被 Git 忽略；公开仓库只提供导入代码、使用说明和来源信息。

## 来源与版本

| 来源 | 输入文件 | 固定版本 | 内容许可记录 |
| --- | --- | --- | --- |
| [系统架构设计师练题台](https://github.com/Zhang-986/ruankao-architect-practice) | `architect/data/bank.json` | `51217a582b93e4266eae8ec745fb308e55259f82` | `LicenseRef-Exam-Study-Only` |
| [408 考研刷题库](https://github.com/lij768423-svg/408-) | `cs408/data.json` | `267d0d815b1eee770dad12e88bbd560d08ba3f66` | `LicenseRef-Unspecified` |

表中输入路径均相对于 `.cache/content/exam-sources/`。下载器只获取题库 JSON、README、LICENSE 及存在时的 NOTICE，不下载整个网站的图片与视频。

系统架构设计师来源的 [NOTICE](https://github.com/Zhang-986/ruankao-architect-practice/blob/51217a582b93e4266eae8ec745fb308e55259f82/NOTICE.md) 明确：MIT 适用于应用代码；题库来自 `wujiaming88/awesome-ruankao` 及其补充资料，上游汇编声明 CC BY-SA 4.0，原始考试题的版权仍归相关权利人，仅供个人学习交流。这里用 `LicenseRef-Exam-Study-Only` 保留该边界，不把题目正文标成 MIT。

408 来源仓库声明 ISC，但其中的第三方考试与教材题目没有核实到独立的公开再分发许可，因此正文记录 `LicenseRef-Unspecified`。这两个 `LicenseRef` 是本项目的来源记录，不能替代权利人的许可证，也不赋予公开发布题目正文的权利。

## 下载、生成与加载

需要 Python 3.10+。在项目根目录运行：

```sh
python scripts/content-import/exams.py --download
python scripts/content-import/multi_source.py
node scripts/start.mjs
```

第一条命令下载固定提交的快照并导出考试包；第二条命令将 `exams.learning.json` 与其他已准备的来源合并为 `local.learning.json`。启动器在启动时自动加载合并包，同时保留内置示例题和已有学习记录。

已存在快照时，无需重新下载：

```sh
python scripts/content-import/exams.py
python scripts/content-import/multi_source.py
```

默认生成：

```text
.cache/content/exam-sources/architect/    # JSON 与来源说明快照
.cache/content/exam-sources/cs408/        # JSON 与来源说明快照
.cache/content/exams.learning.json       # topics / questions 学习包
.cache/content/exams.audit.json          # 每一条候选记录的导入或跳过原因
.cache/content/local.learning.json       # 多来源合并包
```

`--source-root` 可指定已有快照目录；每个来源的子目录和输入文件需要符合上表。没有 Git 元数据或可验证的下载清单时，版本记录为 `unknown`，文件 SHA-256 仍可用于核对。本项目父目录的 Git HEAD 不会被当成外部快照的版本。下载清单中的版本、URL 和文件 SHA-256 均需对应当前快照，才能记录确切提交号。

直接运行 Java 或 Maven 时，显式指定合并包：

```powershell
$env:DEVPREP_LOCAL_LEARNING_FILE = (Resolve-Path .cache/content/local.learning.json).Path
```

如只想使用考试包，也可将该变量指向 `exams.learning.json`；本项目内置示例题仍会一起加载。题目按稳定 ID 增量导入，重启和重复运行导入器不会清空既有学习记录。

## 实际导入内容

对上述固定版本的首次导入结果如下。数量来自生成的学习包，不包括扫描 PDF、资源链接或没有答案的问题清单。

| 来源 | 可练习题目 | 知识点分组 |
| --- | ---: | ---: |
| 软考系统架构设计师 | 1,710 | 16 |
| 考研 408 四科 | 2,135 | 26 |
| 合计 | 3,845 | 42 |

软考覆盖综合知识、软件工程、架构、数据库、网络、安全、项目管理等单选题与可完整读取背景、各小问和参考答案的案例。408 覆盖数据结构、计算机组成原理、操作系统和计算机网络。

导入规则：

- 单选题必须有四个完整且不同的选项，以及一个能映射到完整选项文本的答案。使用 `assessment_mode: "rubric"` 和空 `rubric`，由后端自动判题。
- 一个完整案例作为一道简答题，保留所有小问与原文参考答案；使用 `assessment_mode: "reference"` 和空 `rubric`，答题后自行对照答案评估。上游某答案混入后续小问答案时，只在明确的章节边界截断。
- 保留题干、选项与解析中的 Markdown、LaTeX 和 HTML 表格，来源 SHA-256 始终对应原始 JSON 文件。前端负责安全显示文本与公式。
- 跳过上游隐藏题、多选题、空或重复选项、无实际解释的题目，以及依赖未导入图片或缺失表格的题目。软考论文只有写作要点时保留在快照中，不算作已导入的练习题。
- 相同题干、选项和正确答案经过空白、全角字符与数学排版命令规范化后重复的记录只导入一次。不同版本或表述的相似题仍可能存在，计数代表实际导入记录数。

每题状态为 `imported`，不是人工核验通过。导入器不会补写或生成缺失的答案；解析的适用版本、勘误与论述口径应结合原始来源核对。

## 来源审计与 Git 检查

每题附带 `source_name`、`source_url`、`source_path`、`source_license`、`source_attribution`、`source_revision`、`source_sha256` 和 `source_locale`。来源链接指向实际读取的题库 JSON 文件；另外保留原题 ID 和上游原文路径，便于沿来源继续核对。外部题目使用 `source_material_id: null`，不会被标成项目内置的 Java 资料。

查看 `exams.audit.json` 可得到逐来源题量、题型统计、文件摘要和每道候选题的处理结果。源正文及生成题库必须留在忽略目录中。发布之前可检查：

```sh
git check-ignore .cache/content/exams.learning.json .cache/content/exams.audit.json
git ls-files .cache/content
```

第一条应输出两个路径，第二条应没有输出。不要使用 `git add -f` 添加快照和题库正文；导入器也会拒绝将生成包写到 `.cache/content/` 以外。
