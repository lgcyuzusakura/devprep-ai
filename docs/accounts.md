# 独立账号与朋友体验

默认仍是本机单人模式。多人使用时启用账号模式：每个账号分别保存聊天、练习记录、笔记、算法提交、简历、求职材料和模拟面试。浏览器草稿也按账号区分。原有单人数据归首次初始化的管理员账号。

## 初始化

在源码目录之外保存私有 JSON 文件，只允许服务器管理员读取。下面均为占位符，部署前自行替换：

```json
{
  "accounts": [
    { "username": "owner", "password": "REPLACE_WITH_OWNER_PASSWORD" },
    { "username": "friend1", "password": "REPLACE_WITH_INITIAL_PASSWORD" }
  ]
}
```

第一项是管理员，其余是学习账号。用户名为 3–32 位小写字母、数字或下划线，以字母开头；密码为 8–128 个字符。最多 20 个账号。朋友首次登录必须修改初始密码。

设置环境变量后启动 Spring Boot：

```text
DEVPREP_AUTH_ENABLED=true
DEVPREP_AUTH_BOOTSTRAP_FILE=/run/devprep/accounts-bootstrap.json
DEVPREP_AUTH_SECURE_COOKIE=true
DEVPREP_MEMBER_MODEL=gpt-6.1-sol
```

Docker 部署将私有 JSON 只读挂载到上述路径，并在 Compose 的 app.environment 中设置这些变量。账号文件不能进入 Git、公开附件或分享给其他人。首次初始化必须有该文件；之后可以保留挂载供创建遗漏账号，或撤去挂载并清空 bootstrap 环境变量。重启不会覆盖已有账号的密码。

公网入口必须提供 HTTPS，并转发 Cookie；应用仍绑定本机端口，通过反向代理转发。现有 API 仅接受本机 Host/Origin，代理须先校验公网域名与请求 Origin，再将上游 Host 和 Origin 改写为实际本机地址；不能无条件改写外部来源。`DEVPREP_AUTH_SECURE_COOKIE=true` 下，HTTP 地址不会保持登录。本机开发和浏览器测试可以显式设为 false，公网部署保持 true。

## 日常使用

- 打开站点，输入用户名和密码。不同浏览器或不同电脑可以同时登录不同账号；同一个浏览器配置使用一个当前账号。
- “我的账号”可以修改自己的密码，修改后其他设备上的旧登录会失效。
- 管理员在“我的账号”创建朋友账号、重置朋友密码。重置保留学习记录，撤销旧登录，并要求下次登录改密。
- 朋友使用 `gpt-6.1-sol`，可通过 `DEVPREP_MEMBER_MODEL` 修改。Provider、模型网关与密钥由管理员统一配置，朋友看不到配置原文，也不能修改或导入配置。管理员保留原来的模型选择。
- 多人可以同时浏览和离线练习；模型请求仍受全局串行限制及至少 10 秒间隔控制，忙碌时请稍后重试。

## 数据和维护

账号凭据、会话和所有工作区保存在同一数据库中。管理员沿用 public schema，朋友使用独立 schema；连接获取时由服务端认证上下文选择工作区，客户端不能指定。每个工作区独立执行迁移并导入配置的题库，新增账号及首次升级可能需要等待。

密码使用随机盐和 PBKDF2 哈希；浏览器会话使用 HttpOnly、SameSite=Strict Cookie，HTTPS 部署启用 Secure，仅在数据库保存会话令牌摘要。会话有效期七天，退出、改密或管理员重置可撤销。

备份时停止应用并备份整个数据库，包含全部账号与工作区；备份仍然是私有文件。不要通过关闭账号模式解决登录问题，否则会恢复无需登录的本机单人模式。此功能适合个人及少量朋友体验，没有开放自助注册、找回密码或邮件验证。

## 验证

```powershell
cd backend-java
.\mvnw.cmd -B verify
cd ..
npm run build
# 本机已经构建 Jar 后，执行真实登录浏览器流程
$env:PLAYWRIGHT_CHANNEL = 'chrome'
npx playwright test --config playwright.accounts.config.js
```

测试使用临时账号与临时数据库，不连接真实模型。浏览器验证覆盖登录、首次改密、切换账号的草稿隔离、创建/重置账号、第二浏览器登录以及手机页面宽度。
