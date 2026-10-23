# 变更记录

## 0.1.0-SNAPSHOT（开发中）

### 2026-10-23：独立 SQL 加工与依赖调度

- 新增 SQL 加工任务、同步/SQL 上游依赖、DAG 预览、完整依赖链运行和定时启停。
- DolphinScheduler 发布多节点 SHELL 工作流；SQL worker 领取版本快照，独立记录运行状态。
- 同步管道的旧加工 SQL 改为可选，支持纯 ODS 接入；既有同步水位语义保持兼容。
- 新增 V3 迁移、任务/模型引用保护、运行互斥、过期配置拦截和执行器保密测试。
- 操作、边界和验证脚本见 [SQL 加工](docs/sql-transforms.md)。

### 2026-10-23：数据建模首版

- 新增模型目录、主题域及分层、字段编辑与排序、标准备注和乐观版本检查。
- 生成固定 warehouse 的 Doris UNIQUE KEY DDL，支持预览确认建表、结构核对及版本化执行记录。
- 新增 V2 平台迁移、菜单、角色/CSRF 防护；建表账号与只读查询账号分离。
- 新增建模单元/集成测试与真实环境冒烟脚本，使用及升级方式见 [数据建模](docs/modeling.md)。

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
