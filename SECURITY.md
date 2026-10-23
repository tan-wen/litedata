# 安全问题
请勿在公开 Issue 中发布连接密码、API Key、内部执行令牌或可直接利用的漏洞细节。
如仓库已启用私密漏洞报告，请通过 GitHub Security 页面的 Report a vulnerability 联系维护者。
若该入口不可用，请先提交不含漏洞细节、凭据或利用代码的 Issue，请维护者提供私密沟通渠道。

平台数据库、源库、Doris 读账号和 Doris 写账号职责分离。平台仅保存数据源密码密文，
主密钥由环境注入；API Key 仅保存 SHA-256 摘要。轮换 Key 会立即吊销该服务的旧 Key。
管理页面使用 Session 和 CSRF；执行节点接口要求独立内部令牌。

开发环境 HTTP 监听默认绑定本机。对外部署须通过 HTTPS 反向代理，并启用安全 Session Cookie。
仅将受信任的开发人员设为 DEVELOPER；其有权定义仓库内的数据加工和服务查询。

