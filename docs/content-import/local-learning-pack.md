# 多来源本地题库

`scripts/content-import/multi_source.py` 将已下载的 Markdown 快照解析为实际可练习的简答题，再合并为 `.cache/content/local.learning.json`。源正文和生成数据保留在 Git 忽略目录，公开仓库中只放工具与使用说明。

## 快照目录与来源

导入器按下表读取本地目录。JavaGuide、JavaScript、React、Go 可以使用对应仓库的 GitHub 源码归档解压；目录名需与下表一致。Python 与 DevOps 推荐使用 Git 克隆，以便记录准确提交号。

| 本地目录（位于 `.cache/content/`） | 来源 | 本地快照声明 |
| --- | --- | --- |
| `devprep-java/JavaGuide-main` | [Snailclimb/JavaGuide](https://github.com/Snailclimb/JavaGuide) | Apache-2.0 |
| `devprep-javascript/javascript-interview-questions-master` | [sudheerj/javascript-interview-questions](https://github.com/sudheerj/javascript-interview-questions) | 未核实到许可证文件 |
| `devprep-react/reactjs-interview-questions-master` | [sudheerj/reactjs-interview-questions](https://github.com/sudheerj/reactjs-interview-questions) | MIT |
| `devprep-go/interview-go-master` | [lifei6671/interview-go](https://github.com/lifei6671/interview-go) | Apache-2.0 |
| `python-questions` | [learning-zone/python-interview-questions](https://github.com/learning-zone/python-interview-questions) | 未声明许可证 |
| `devops-exercises` | [bregman-arie/devops-exercises](https://github.com/bregman-arie/devops-exercises) | CC BY-NC-ND 3.0 |

有 Git 元数据时，来源链接固定到本地提交号。源码归档没有可核实提交号时记录 `source_revision: "archive-unknown"`，并记录每份文件的 SHA-256；来源链接指向仓库分支，不声称分支当前内容与本地归档完全相同。

## 一次下载

首次使用时，在项目根目录依次执行以下命令。目标目录已存在时，直接使用已有快照，或在对应 Git 目录内更新后再导入。

```sh
git clone --filter=blob:none --depth 1 https://github.com/Snailclimb/JavaGuide.git .cache/content/devprep-java/JavaGuide-main
git clone --filter=blob:none --depth 1 https://github.com/sudheerj/javascript-interview-questions.git .cache/content/devprep-javascript/javascript-interview-questions-master
git clone --filter=blob:none --depth 1 https://github.com/sudheerj/reactjs-interview-questions.git .cache/content/devprep-react/reactjs-interview-questions-master
git clone --filter=blob:none --depth 1 https://github.com/lifei6671/interview-go.git .cache/content/devprep-go/interview-go-master
git clone --filter=blob:none --depth 1 https://github.com/learning-zone/python-interview-questions.git .cache/content/python-questions
git clone --filter=blob:none --depth 1 https://github.com/bregman-arie/devops-exercises.git .cache/content/devops-exercises
git clone --filter=blob:none --depth 1 --sparse https://github.com/wdndev/llm_interview_note.git .cache/content/llm-interview-note
git -C .cache/content/llm-interview-note sparse-checkout set --no-cone '*.md' 'LICENSE*'
```

前六个来源由 `multi_source.py` 解析。LLM 中文题库先运行 `python scripts/content-import/llm.py`，再运行合并脚本；详细来源与限制见 [LLM 本地导入说明](llm-interview-note.md)。

## 生成与加载

准备 Python 3.10+，在项目根目录运行：

```sh
python scripts/content-import/multi_source.py
```

脚本生成每个来源的独立 `*.learning.json`、合并包 `local.learning.json`，以及计数、逐文件摘要和跳过原因所在的 `local.audit.json`。如果 `.cache/content/llm.learning.json` 或 `.cache/content/exams.learning.json` 已存在，会自动作为额外包合并；考试包的来源与生成方法见 [408 与软考本地题库](exams.md)。其他本地包可通过重复的 `--additional-pack`（或同义的 `--extra-pack`）参数加入。

```sh
python scripts/content-import/multi_source.py --additional-pack .cache/content/another.learning.json
```

导入器按来源的标题和正文边界抽取已有参考答案，跳过空答案、纯链接目录和没有答案的问题清单。代码题的输入代码保留在题干中，参考答案保留原始 Markdown/HTML。稳定题目 ID 由来源路径、题干和答案生成哈希。

本地可选勘误文件为 `.cache/content/known-errata.json`：`excluded_question_ids` 是需要排除的稳定 ID 数组，`reason` 可以是 ID 到原因的对象，也可以是通用原因文本。勘误过滤覆盖所有来源及额外学习包，原始快照保持完整；实际排除数和未匹配 ID 记录在 `local.audit.json`。可使用 `--errata-file` 指定其他本地勘误清单。

```powershell
$env:DEVPREP_LOCAL_LEARNING_FILE = (Resolve-Path .cache/content/local.learning.json).Path
.\scripts\start.ps1
```

```sh
export DEVPREP_LOCAL_LEARNING_FILE="$PWD/.cache/content/local.learning.json"
node scripts/start.mjs
```

公开内置种子与本地包同时加载。Markdown 问答使用 `assessment_mode: "reference"`；来源正文没有经过事实审核，自评时可打开原始来源核对版本、适用条件和勘误。额外包也可以包含已有确定答案的单选题，使用 `assessment_mode: "rubric"` 并校验答案属于选项。

## 中文 JavaScript 选择题

新增导入器读取 [lydiahallie/javascript-questions 简体中文版](https://github.com/lydiahallie/javascript-questions/blob/master/zh-CN/README-zh_CN.md)，保留 MIT 署名、中文解析、代码和固定提交链接。2026-10-19 核验可解析 150 道单选题；它们属于前端题库。下载串行执行，不调用模型，也不需要牛客账号。

已有私人学习包时，在项目根目录运行：

```sh
python scripts/content-import/javascript_choices.py --merge .cache/content/local.learning.zh-CN.json --output .cache/content/local.learning.next.json
```

检查 `.cache/content/javascript-choices/audit.json` 的题量和跳过记录。先停止应用并备份原学习包，再用输出文件替换应用加载的学习包，按原配置启动。重复导入使用相同题目 ID，不会清除其他来源的题目或已有翻译。原始快照、MIT 许可证和输出包保存在忽略目录，不推送 GitHub。

牛客的在线选择题入口是 [专项练习](https://www.nowcoder.com/exam/intelligent)。本项目提供外部入口，未将牛客题目批量复制成本地题库；其网站题量与本地题量分别统计。

## 署名与分发

需要把自己编写的中文选择题合并到已有译文包时，可使用 `scripts/content-import/merge_choices.py`；题目结构、幂等规则与本机原创题包说明见 [原创选择题补充](../updates/2026-10-19-original-choice-bank.md)。该工具只合并本地文件，不会请求模型或重新生成其他来源的题目。

每题附带名称、URL、相对文件路径、许可证声明、署名、版本和文件 SHA-256。导入数据不包含或复制本机的模型密钥配置。

Apache-2.0、MIT 来源仍需保留相应许可证与版权通知；仓库许可证不能替代其中转载内容的具体权利。没有声明许可证的来源标记为 `LicenseRef-Unspecified`，不会据此获得公开再分发授权。DevOps 的非商业、禁止改编限制见 [DevOps 本地导入说明](devops-exercises.md)。

`.cache/content/` 已在 `.gitignore` 中。不要把这些快照或导出的题库加入 Git，也不要将整个合并包作为本项目的公开题库发布。
