# LiteData 代理协作入口

本文件是仓库内自动化代理的协作入口。开发者贡献步骤见 [CONTRIBUTING.md](CONTRIBUTING.md)。所有修改以实际源码、`pom.xml`、`compose.yaml` 和 `docs/` 为准。

## 项目上下文

LiteData 是面向单机试点的轻量数据中台。当前版本为 `0.1.0-SNAPSHOT`，处于首版开发和引擎联调阶段：平台功能与自动化测试已实现；真实 DataX、Doris、DolphinScheduler 演示同步及分层加工链路已实测跑通，百万行压测和连续七天运行仍待验收。范围和已知验证状态以 [README](README.md)、[实施及验收计划](docs/implementation-plan.md) 和 [引擎运行说明](docs/engine-runbook.md) 为准。

- Java 根包为 `com.litedata`，Maven 坐标为 `com.litedata:litedata`；新增代码和引用保持一致。
- `src/main/java/com/litedata/`：`config` 配置绑定，`data` 数据源连接，`pipeline` 调度和水位流程，`security` 鉴权及凭据，`service` 受控查询，`store` MyBatis 持久化，`web` 页面与 API；`model` 为领域对象。
- `modeling` 维护逻辑模型、Doris 建表和结构核对；`transform` 维护独立 SQL 加工、同步/SQL 依赖 DAG 与 DS 调度，使用独立运行记录，不回退或推进同步管道水位。操作边界见 `docs/sql-transforms.md`。
- `src/main/resources/db/migration/` 是 Flyway 迁移；模板、静态资源和应用配置同在 `src/main/resources/`。`deploy/` 提供 Compose 引擎初始化、worker 与冒烟脚本；`docs/` 保存实施边界和引擎操作说明。

### 运行和验证

使用 JDK 25（Maven Enforcer 要求 `[25,26)`）、仓库 Maven Wrapper 和 Docker Compose v2。自动化测试由 Testcontainers 启动真实 MySQL，因而需要 Docker：

```powershell
.\mvnw.cmd verify
docker compose --env-file .env.example config --quiet
```

Linux/macOS 对应使用 `./mvnw verify`。完整部署通过 `deploy/start.ps1` 或 `deploy/start.sh` 启动；只有实际运行后才可执行 `deploy/smoke.py`。没有实测真实引擎链路时，不能把单测、Compose 配置解析或任务提交接受表述为全链路验收通过。

### 数据链路和安全边界

数据流程是：MySQL 源表 → DataX 时间水位增量 → Doris ODS（UNIQUE KEY）→ `INSERT SELECT` 加工 → Doris ADS → 只读数据 API。DolphinScheduler 调度和重试同步/加工任务，worker 向平台回调结果。平台 MySQL 与 DolphinScheduler MySQL 元数据库相互独立。

- 源表水位必须是非空、有前导索引的 `DATETIME`/`TIMESTAMP`；每批取源库上界，读取 `[最近成功水位 - 5 分钟, 上界)`，首批从 1970-01-01 开始。
- runner 对管道实行运行互斥并固定上下界；同步和加工均成功才推进水位。重跑依赖 Doris 目标表及加工目标的业务主键幂等写入。
- 时间水位不能完整捕获硬删除、超出重放窗口的迟到和历史水位回写；这些情形需要对账和显式补数。不要把它描述为 CDC 语义。
- 平台密钥和内部令牌从环境变量或本地 `.env` 注入：不得提交 `.env`、令牌、密钥、真实业务数据、含密码日志或 `deploy/runtime/` 内容。页面登记的数据源密码以密文存储，主密钥由环境提供；API Key 仅保存摘要，Doris 读写账号和内部 worker 令牌必须职责分离。具体披露方式见 [SECURITY.md](SECURITY.md)。

## 工作方式

- 先检查工作区和相关文件，保留其他人的未提交修改；不要用重置或回退覆盖无关工作。
- 保持改动小且聚焦。涉及包名时使用 `com.litedata`。
- 所有资源的列表页、新增页、编辑页必须使用独立页面和独立路由；列表只保留操作入口，不内嵌或并排展示新增、编辑表单，也不以弹窗、抽屉替代独立页面。新增及改造页面遵循 [资源管理页面约定](CONTRIBUTING.md#资源管理页面约定)。
- 所有资源列表必须支持数据库分页，并在翻页时保留筛选条件；使用统一分页组件，禁止全量加载后在内存分页。
- 前端使用本地固定版本的 Bootstrap 6；所有页面及 SQL 高亮遵循 [界面与主题说明](docs/ui-guide.md)，使用统一外观控件和语义色变量，支持浅色、深色、跟随系统及主题配色。
- Java 构建使用 JDK 25、仓库 Maven Wrapper 与 Docker。优先运行与改动相称的检查；报告实际执行的命令和结果。
- Flyway 只接受新增的顺序迁移，不改写已执行迁移。变更管道时维护时间水位、运行互斥、成功后推进和目标幂等写入这些约束。
- 禁止提交或输出 `.env`、密码、API Key、内部令牌、加密主密钥、真实数据及未脱敏日志。不要将未实测的 DataX→Doris→DolphinScheduler→API 全链路描述为验收通过。

## 成本感知委派

- 只委派边界明确、可独立完成，并具有明确验收标准和可行验证方式的任务。
- 窄范围只读搜索、清单、摘要、简单静态检查和确定性测试/日志分诊优先使用 `luna_checker`。
- 需要判断但范围集中的实现、诊断或审查优先使用 `terra_worker`。
- 不把架构、安全关键决策、跨模块改动或最终结论外包；主代理必须检查子代理的证据和差异，并独立确认相关项目检查。
- 除非用户明确要求扩大并行度，同时运行的子代理最多两个；写密集型任务不要重叠委派。

## 审查

不使用 CodeRabbit。使用本地人工审查以及仓库已有的 Maven、Docker Compose 和按需完整链路检查。
