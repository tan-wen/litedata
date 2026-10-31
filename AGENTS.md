# LiteData 代理协作入口

单机数据中台；Java 根包及 Maven 坐标为 `com.litedata` / `com.litedata:litedata`。以源码和配置为准。

## 按需读取

- 先查 `git status` 和任务相关文件，保留他人未提交修改。
- [README](README.md) 提供启动和演示；[文档索引](docs/README.md) 按模块定位说明，只读取任务需要的文档与章节。
- 默认搜索限定相关源码目录；不要全库展开或读取 `target/`、`output/`、`deploy/runtime/`、缓存、截图、第三方压缩资源及整份操作手册。确有需要时再定位读取。
- `src/main/java/com/litedata/` 按业务分包；`store` 为 MyBatis，`web` 为页面/API，`model` 为领域对象；资源、模板和 Flyway 迁移位于 `src/main/resources/`。
- `deploy/` 为引擎、worker 和冒烟脚本，`deploy/production/` 为生产交付，`examples/` 为可运行示例。

## 必须保持

- 资源列表、新增、编辑使用独立页面与路由；列表用数据库分页并保留筛选，禁止内存分页。具体行为见 [贡献约定](CONTRIBUTING.md#资源管理页面约定)。
- 使用本地固定版 Bootstrap 6、统一分页及主题控件；界面遵循 [UI 说明](docs/ui-guide.md)。
- Flyway 只新增顺序迁移，不改写已执行的迁移。
- 同步以非空、带前导索引的时间水位读取 `[最近成功水位 - 5 分钟, 源库上界)`；固定批次边界并保持运行互斥。DataX 成功写入已登记的 ODS UNIQUE 目标后才推进水位，独立 SQL 失败不回退它；重跑依赖目标业务主键幂等。时间水位不等于 CDC，硬删除和窗口外迟到需对账补数。
- 不提交或输出 `.env`、凭据、令牌、真实业务数据和未脱敏日志；数据源密码加密、API Key 存摘要，读写账号和 worker 内部令牌保持职责分离。见 [安全说明](SECURITY.md)。

## 验证与维护

JDK 25、仓库 Maven Wrapper、Docker Compose v2；Testcontainers 集成测试需要 Docker。
按改动执行相关检查并报告实际结果，Java 改动通常运行 `./mvnw verify`（Windows 为 `.\mvnw.cmd verify`）。部署配置检查使用 `docker compose --env-file .env.example config --quiet`。
单测、配置解析、任务提交接受均不代表真实引擎或容量验收；状态与验收步骤见 [引擎运行说明](docs/engine-runbook.md#验证与验收)。

文档保留当前行为、操作命令和限制；同一规则维护一处，其他位置链接引用。过程日志、临时编号和导出件不写回长期文档，生成物放 Git 忽略的 `target/` 或 `output/`。

## 委派与审查

- 只委派边界明确、有验收和验证方式的独立任务；只读检查优先 `luna_checker`，集中实现/诊断优先 `terra_worker`。架构、安全、跨模块决策和最终结论由主代理负责。
- 最多同时运行两个子代理（用户明确扩大并行度除外），避免重叠写入；主代理检查差异与证据，并独立确认相关项目检查。
- 不使用 CodeRabbit；使用本地人工审查和项目已有检查。
