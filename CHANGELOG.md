# 变更记录

## 0.1.0-SNAPSHOT（开发中）

### 2026-10-23：独立开源仓库

- 项目统一命名为 LiteData，仓库为 [tan-wen/litedata](https://github.com/tan-wen/litedata)。
- 从 `bigdata` 同级拆分到 `litedata`；迁移源码、测试、部署配置和当前实施文档。
  历史设计原稿、原型和 Excel 计划保留在原目录。
- Java 源码与测试根包改为 `com.litedata`，Maven 坐标改为 `com.litedata:litedata`。
- Compose 默认项目名改为 `litedata`，已有数据卷沿用方式见 [引擎运行说明](docs/engine-runbook.md#目录与部署名称迁移)。
- 补齐 README、AGENTS.md 项目上下文与协作约定、贡献文档和第三方说明；本地凭据和运行产物保持忽略。

迁移后验证：JDK 25 下执行 `mvnw.cmd --batch-mode --no-transfer-progress verify` 通过，
共 10 项测试（4 项单元、6 项真实 MySQL 集成测试），无失败、错误或跳过；
`docker compose --env-file .env.example config --quiet` 通过，成功生成可执行 JAR。

当前范围为单机平台和固定引擎链路实现。完整源到 Doris 同步、DS 调度/重试、重启恢复、
百万行压测及连续七天观察仍待联调验收；未发布稳定版本。
