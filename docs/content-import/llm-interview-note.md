# 中文大模型原文问答导入

导入器 `scripts/content-import/llm.py` 读取 [wdndev/llm_interview_note](https://github.com/wdndev/llm_interview_note) 的本地 Git 快照。它只抽取有实际参考答案的问句标题；章节标题、空答案和代码块内的标题不计为题目。

## 下载和解析

在仓库根目录运行，Git 的稀疏检出只下载 Markdown，避免下载图片和 PDF：

```sh
git clone --depth 1 --filter=blob:none --sparse https://github.com/wdndev/llm_interview_note.git .cache/content/llm-interview-note
git -C .cache/content/llm-interview-note sparse-checkout set --no-cone '*.md' 'LICENSE*'
python scripts/content-import/llm.py
python scripts/content-import/multi_source.py
```

`llm.py` 生成 `.cache/content/llm.learning.json` 和 `.cache/content/llm.audit.json`。合并器将其加入 `local.learning.json`，Node 启动器在下次启动时自动加载合并包，同时保留内置示例题与已有学习记录。

已存在快照时无需重复克隆。更新本地 Git 快照后重新运行两条 Python 命令即可。题目 ID 根据路径、题干和答案生成；原文变化会产生新版本的题目，已导入的旧题与记录继续保留。

## 来源与使用方式

导出保留原文答案、源文件 SHA-256、中文语言标记及固定提交的 GitHub 链接。所有问答使用 `assessment_mode: "reference"`：先写答案，再对照来源选择“掌握”或“需复习”。这些原文资料尚未经过本项目逐题审核。

该快照未声明统一许可证，字段标为 `LicenseRef-Unspecified`；部分原文还引用其他材料。生成数据与下载快照全部放在 Git 忽略的 `.cache/content/`，不随公开仓库发布。
