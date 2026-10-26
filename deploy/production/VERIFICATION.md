# 本次部署包验证记录

日期：2026-10-26。生产规格为用户确认的 Linux x86_64、32 核 / 128GB。

- 使用本机 JDK 25.0.3、仓库 Maven Wrapper 执行 `./mvnw verify` 的 Windows 对应命令：141 项测试，0 失败、0 错误、0 跳过，构建成功。
- 现有 Python runner 单元测试：`py -3 -m unittest discover -s deploy/tests -q`，9 项通过。
- 生产 Compose 清单完成真实 Docker Compose 配置解析，MySQL 中等规模配置通过 `mysqld --validate-config`。
- 调度镜像按交付 Dockerfile 的默认官方基础镜像构建成功，未使用平台源码构建运行时。
- 独立 `litedata-release-check` 项目完成两个 MySQL、独立 Doris FE/BE、ZooKeeper、DS API/Master/Worker/Alert 与 JAR 服务的启动检查。
- 引擎初始化连续执行两次，第二次复用并校验已有项目令牌；未重复导入调度库。
- 真实演示数据链路：MySQL → DataX → Doris ODS → INSERT SELECT ADS → 平台 API，返回预期结果，API 凭证轮换后旧凭证失效。
- 验证 FE/BE/Worker 重启后的健康与数据持久化；元数据备份命令生成两库 SQL、密钥环境文件和调度令牌文件。
- 配置生成器拒绝覆盖现有凭据；AES 密钥为 32 字节 Base64；制品校验与篡改检测通过。
- ZIP 显式编码 POSIX 0644/0755 权限；交付不包含 `.env`、运行令牌、真实业务数据、测试数据库目录、平台 Java 源码。

本机 Docker 可用内存约 16GiB，同时保留用户原有环境，运行验证使用独立子网与端口、缩小资源配额，并关闭测试节点的调度负载保护。
交付清单保留 128GB 中等规模配额与默认负载保护。验证过程中已修复 FE 首次启动的健康依赖、tmpfs YAML、Linux 文件权限与 Doris 优雅停机方式。
这些检查证明小数据真实链路可运行，不代表 0.5–2TB 容量、百万行吞吐、四任务并发或连续七天稳定运行验收；现场仍需按 README 完成容量与备份恢复演练。
