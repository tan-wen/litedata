# 参与 LiteData 开发

感谢参与 LiteData。项目是面向单机试点的轻量数据中台，代码托管在 [tan-wen/litedata](https://github.com/tan-wen/litedata)。开始前请阅读 [README](README.md)、[AGENTS.md](AGENTS.md) 和 [SECURITY.md](SECURITY.md)；其中包含当前联调状态、数据语义和凭据处理要求。

## 环境准备

需要 JDK 25、Docker（Linux 容器）和 Docker Compose v2。请使用仓库自带 Maven Wrapper，而非依赖本机 Maven；Testcontainers 测试会启动真实 MySQL，因此 Docker 必须可用。

```powershell
git clone https://github.com/tan-wen/litedata.git
cd litedata
.\mvnw.cmd verify
docker compose --env-file .env.example config --quiet
```

Linux/macOS 使用：

```sh
git clone https://github.com/tan-wen/litedata.git
cd litedata
./mvnw verify
docker compose --env-file .env.example config --quiet
```

本地完整环境用 `deploy/start.ps1`（Windows PowerShell 7）或 `deploy/start.sh` 启动。脚本生成本地 `.env`；不要将它提交。完整流程和恢复注意事项见 [引擎运行说明](docs/engine-runbook.md)。

## 修改约定

- Java 代码位于 `src/main/java/com/litedata/`，测试位于 `src/test/java/com/litedata/`；新增代码使用 `com.litedata` 包名。
- 平台持久化使用 MyBatis，页面使用 Thymeleaf，本地固定版本的 Bootstrap 6 静态资源随仓库提供；界面与主题开发遵循 [界面与主题说明](docs/ui-guide.md)。
- 数据库变更在 `src/main/resources/db/migration/` 新增顺序递增的 Flyway 迁移；不要修改已经执行的迁移。
- 修改管道时保留时间水位、运行互斥、同步和加工全部成功后才推进水位，以及 Doris 目标表幂等写入。时间水位不覆盖硬删除和超出回放窗口的迟到，相关补数须有对账方案。
- 提交应聚焦一个目的，说明用户可见行为、数据迁移影响和验证结果。不要顺带格式化或回退无关文件。

## 资源管理页面约定

- 所有资源管理功能（如数据源、数据模型、同步管道、SQL 加工任务、数据服务、账号权限等）的列表页、新增页、编辑页必须分别使用独立页面和独立路由。
- 列表页负责列表展示、搜索筛选、分页和操作入口；点击“新增”或“编辑”应跳转到对应页面。不得把新增、编辑表单与列表并排、上下拼接或内嵌在同一页面，也不得用列表页上的弹窗、抽屉替代独立的新增页、编辑页。
- 所有资源列表必须提供服务端分页，默认每页20条，支持切换页码和每页条数并显示总数；数据库按稳定顺序执行计数与分页查询，不得全量加载后在内存中切片。翻页保留搜索和筛选条件，非法分页参数返回400，页码超过末页时回到末页。
- 路由按资源组织，例如 `/resources`（列表）、`/resources/new`（新增）、`/resources/{id}/edit`（编辑）。新增页与编辑页可以复用表单模板或组件，但各自保持独立的页面入口、标题和提交行为，不与列表共用页面。
- 新增页、编辑页提供明确的返回或取消入口；保存成功后返回列表页，校验失败时保留当前表单及错误提示。
- 新增或改造资源管理页面时按此约定实施；现有将新增、编辑区域与列表混用的页面，在改造时也必须拆分，并验证列表到表单、保存、取消及返回的完整流程。

## 验证与提交

提交前至少运行与变更相关的检查，Java 改动通常应运行：

```powershell
.\mvnw.cmd verify
docker compose --env-file .env.example config --quiet
```

涉及 DataX、Doris、DolphinScheduler、worker 或部署脚本的改动，还应在实际 Compose 环境中完成对应验证；完整环境启动后可运行 `python deploy/smoke.py`。单元/集成测试、Compose 配置解析和 DS 接受任务均不等同于 MySQL→DataX→Doris→DolphinScheduler→API 完整链路验收。未完成实际测量时，请在 PR 或 Issue 中明确写出未验证项。

提交 Pull Request 前请确保描述包括：改动目的、数据库或部署影响、已执行命令及结果、尚未验证的外部依赖。问题报告请附版本、复现步骤、预期和实际结果；日志先脱敏。

## 安全与数据

不要提交 `.env`、连接密码、访问令牌、API Key、内部执行令牌、加密主密钥、真实业务数据、Docker 卷内容或包含凭据的 DataX 日志。示例仅使用 [.env.example](.env.example) 中的占位值。安全问题不要公开提交到 Issue，请遵循 [SECURITY.md](SECURITY.md) 的私密报告方式。

