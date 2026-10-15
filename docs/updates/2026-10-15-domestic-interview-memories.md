# 国内面经补充

本次把第三方面经作为本机私人题库导入，Docker 使用本地文件加载；题目正文不进入公开仓库。整理过程只使用 Python、本地文件和浏览器/HTTP 原帖，不调用任何 AI 模型。

## 数量

- 私人面经包：4,720 道中文问题，337 份来源记录，90 个来源公司标签。
- 加上公开版自有的 48 道演练，运行时题库目录合计 4,768 条。
- 其中 2,206 道来自带牛客原帖链接的 aqjsp 归档，覆盖 127 个原帖；12 份原帖已打开核对正文与作者。其余条目标注为归档提取，不能视为逐帖核验。
- 844 道来自 0voice 候选人逐轮记录，491 道来自 offerseed 社区投稿，205 道来自 bkfish 2020 实习面经 PDF；另保留原私人包内 980 道题。
- 582 道标为公司题合集，不与候选人亲历记录混称。所有题目按技术内容筛选并估算难度，筛选和分类不能证明面试真实性。

## 国内来源与固定版本

- [aqjsp/Interview-experience](https://github.com/aqjsp/Interview-experience)，版本 `215bc72eb04cdcde434845ed650d5015978db145`。归档文件提供牛客原帖链接；优先用原帖作为来源，归档链接作为题目行号备查。上游未声明许可证。
- [0voice/interview_experience](https://github.com/0voice/interview_experience)，版本 `f294cbee013861518ef0e1fac445d1f4f7afd24f`。候选人逐轮文本；上游未声明许可证。
- [bkfish/2020-Interview-experience](https://github.com/bkfish/2020-Interview-experience)，版本 `abd89336036cf5985a826adad3264794430077d0`。2020 届 PDF 面经；上游未声明许可证。
- [offerseed/interview-experience](https://github.com/offerseed/interview-experience)，版本 `c34cbd42b7070584255cb42ba597fdcf2aa43e26`。社区投稿，包含 2025–2026 的国内 AI、Agent、基础设施、搜索与推荐岗位；上游未声明许可证。
- [0voice/Campus_recruitment_interview_questions](https://github.com/0voice/Campus_recruitment_interview_questions)，版本 `8b9539fbe3b9239fd512ffb63faa705897db51b1`。上游声明 CC BY-NC-SA 4.0；保留原作者权利。

## 整理原则和限制

导入器只抽取问题标题或明确编号问题，不导入补写答案、代码、评论、联系方式或个人履历。对 PDF 做 Unicode 规范化。过滤自我介绍、薪资、反问、HR 等非技术问题。公司和岗位沿用原记录可识别的信息；未注明的轮次保留未知。论坛发布日期只标为原帖发布日期，不推定为面试发生日期。

aqjsp、offerseed、0voice 候选人记录和 bkfish 上游均未提供适用的开源许可证。相关题面只保存在用户本机和私人迁移包中，不随公开 GitHub 源码发布；“本地个人学习”说明不是权利人额外许可。原作者和平台权利仍然适用。需要公开再分发时应先取得许可或替换为自行编写的题目。

任何来源中的候选人叙述都可能遗漏、误记或经过编辑。面试经历不代表公司官方流程、实际录用门槛或标准答案。选择题时界面标注来源；AI 改编题仍显示参考来源而不宣称原题。

## 复现

每个导入器均为本地离线脚本。固定版本克隆相应仓库后，串行执行 `candidate_recollections.py`、`offerseed_interview_experiences.py`、`forum_interview_experiences.py` 和 `merge_interview_experiences.py`，命令与示例见 `docs/mock-interview.md`。牛客人工核验 manifest 是本机私有构建材料，不上传。PDF 导入依赖 `pypdf`。
