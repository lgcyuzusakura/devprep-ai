# DevOps Exercises 本地导入

导入器读取 [bregman-arie/devops-exercises](https://github.com/bregman-arie/devops-exercises) 的本地 Git 快照。脚本不联网下载内容，源仓库和生成的问答文件均保存在 Git 已忽略的 `.cache/content/` 下。

## 下载与生成

在项目根目录首次下载：

```sh
git clone --filter=blob:none --depth 1 https://github.com/bregman-arie/devops-exercises.git .cache/content/devops-exercises
```

准备 Python 3.10+，运行：

```sh
python scripts/content-import/devops.py
```

默认生成：

- `.cache/content/devops.questions.json`：原文问答、逐文件 SHA-256、Git 快照提交号及来源校验统计。
- `.cache/content/devops.learning.json`：应用可加载的 `topics`/`questions` 本地学习包。

学习包中的题目为 `short_answer`，使用 `assessment_mode: "reference"`，`options` 和 `rubric` 为空。应用提供一个“对照参考答案自评”的检查项；导入器不编造题目评分要点。题干、答案及其 HTML/Markdown 代码块保留原文。

可以指定其他本地路径：

```sh
python scripts/content-import/devops.py --source-root .cache/content/devops-exercises --output .cache/content/devops.questions.json --learning-output .cache/content/devops.learning.json
```

脚本要求源目录自身是 Git 工作区根目录，拒绝把父级项目的提交号误记为题库版本。更新题库时，在源目录内更新 Git 快照后重新导入。

## 本地加载

指定额外学习包后启动应用。公开内置种子仍会加载，本地包在其基础上补充题目。

```powershell
$env:DEVPREP_LOCAL_LEARNING_FILE = (Resolve-Path .cache/content/devops.learning.json).Path
.\scripts\start.ps1
```

```sh
export DEVPREP_LOCAL_LEARNING_FILE="$PWD/.cache/content/devops.learning.json"
node scripts/start.mjs
```

修改环境变量后需要重启应用。源快照、导出数据和本地运行设置保持为 Git 忽略项，不要把导出题目复制到受版本管理的公开种子文件。

每题携带 `source_name`、`source_url`、`source_path`、`source_license`、`source_attribution`、`source_revision` 和 `source_sha256`。`content_format: "verbatim-html-markdown"` 表示保留原始标记，外部题目使用 `source_material_id: null`。

## 许可证与署名

上游 README 声明 **Creative Commons Attribution-NonCommercial-NoDerivs 3.0 Unported（CC BY-NC-ND 3.0）**：需要署名、禁止商业使用、不授予改编权。公开仓库、在线服务及重新打包的学习产品涉及再分发和改编问题，不能把这份本地导出当作可任意公开的题库；公开使用前需核对具体条目的权利并在需要时获得作者许可。

导出记录署名 `bregman-arie/devops-exercises contributors`，每题链接到源文件和确切 Git 提交。进行许可允许的使用时保留原仓库及 [许可证链接](https://creativecommons.org/licenses/by-nc-nd/3.0/)。本工具保留原文，仅将数据写入本地缓存供核对和在许可范围内使用。
