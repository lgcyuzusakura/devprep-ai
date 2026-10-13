# 本地题库中文翻译

练习使用简体中文题干、选项、参考答案、解析和评分要点。Java、React、Promise、HashMap、API 等专有名词、函数名、命令和代码保持原文。翻译包与原始题库都保存在 Git 忽略的 `.cache/content/`，不随公开仓库发布。

## 生成中文包

先按 [多来源本地题库](local-learning-pack.md) 生成 `local.learning.json`。准备 Python 3.11+ 和兼容 OpenAI 的模型网关，在项目根目录执行：

```powershell
$env:OPENAI_API_KEY = '你的 API Key'
python scripts/content-import/translate_pack.py --model '可用的模型名'
```

脚本优先读取 `OPENAI_API_BASE` / `OPENAI_BASE_URL`、`OPENAI_MODEL` 与 `OPENAI_WIRE_API`。缺少的网关地址、模型名、接口类型会从本机 `.codex/config.toml` 当前选中的 provider 读取；不读取或复制 `auth.json`。也可以传入 `--base-url`、`--model`、`--wire-api responses` 或 `--wire-api chat_completions`。

建议通过环境变量传入密钥。脚本日志不会打印密钥，也不会将模型配置写入题库。

输出默认为 `.cache/content/local.learning.zh-CN.json`。脚本会扫描英文来源及中文来源里的英文自然语言，每批校验题目 ID、字段结构和受保护内容后原子保存。题目附带输入摘要，相同命令再次运行会跳过已经完成且原文未变化的译文；中断后直接重跑即可。默认只使用一个 worker，逐批处理。可通过 `--batch-size` 调整每批题数，`--limit 5` 进行抽样。脚本只在手动执行时翻译，启动应用不会触发翻译。

```powershell
python scripts/content-import/translate_pack.py --model '可用的模型名' --workers 1 --batch-size 6
npm start
```

启动器优先加载中文翻译包。直接运行 Java 时显式指定路径：

```powershell
$env:DEVPREP_LOCAL_LEARNING_FILE = (Resolve-Path '.cache/content/local.learning.zh-CN.json').Path
node scripts/start.mjs --skip-build
```

## 来源与学习记录

`source_locale` 表示原文语言，`content_locale: "zh-CN"` 表示展示语言；翻译不会把英文来源误标为中文。原 ID、URL、署名、许可证、版本和原文件 SHA-256 均保留。`translation_status: "translated"` 与 `translation_input_sha256` 用于识别和复用译文。

重启时，同 ID 的中文译文会更新数据库中的题干、选项、答案、解析和评分要点，并保留已有答题记录与复习安排。翻译后的知识点标题和摘要也会更新。未翻译内容保留在本地，练习接口只提供中文展示内容。

译文由模型生成，查看题目原文可核对专业术语和来源中的勘误。修订译文时保留稳定 ID 与翻译标记，然后重启应用即可应用修订。
