# 参与 LiteData 开发

项目入口见 [README](README.md)，模块说明见 [文档索引](docs/README.md)，代理工作方式见 [AGENTS](AGENTS.md)。

## 环境与验证

使用 JDK 25、仓库 Maven Wrapper、Docker（Linux 容器）和 Docker Compose v2。
Testcontainers 自动启动测试数据库，无需配置 `.env` 或启动完整引擎。

```powershell
.\mvnw.cmd verify
docker compose --env-file .env.example config --quiet
```

Linux/macOS 使用 `./mvnw verify`。其他已有检查按变更范围选择：

```sh
py -3 -m unittest discover -s deploy/tests
py -3 -m unittest discover -s examples/chemical-park
py -3 -m unittest discover -s examples/chemical-park-dim
node --test src/test/js/metric-lineage-graph.test.cjs
```

Linux/macOS 将 `py -3` 换成 `python3`。涉及 worker、引擎或部署行为时，在实际环境运行相应冒烟脚本；入口及验收边界见 [引擎运行说明](docs/engine-runbook.md#验证与验收)。不要将 mock 测试、配置解析或 DS 接受任务当作全链路通过。

## 修改约定

- 使用 `com.litedata` 包，持久化用 MyBatis，页面用 Thymeleaf；主题及 Bootstrap 版本规则见 [UI 说明](docs/ui-guide.md)。
- Flyway 只新增递增迁移，不修改已执行的迁移。
- 管道保持固定水位边界、运行互斥与幂等：DataX 成功写入 ODS 后推进水位，独立 SQL 加工失败不回退水位。完整语义见 [引擎运行说明](docs/engine-runbook.md#执行与水位)。
- 改动聚焦一个目的，保留无关工作。文档维护当前行为与可复现步骤；生成物放 `target/` 或 `output/`，不追加测试流水账或临时资源编号。

## 资源管理页面约定

- 列表、新增、编辑分别使用独立页面与路由，例如 `/resources`、`/resources/new`、`/resources/{id}/edit`。列表只展示数据、筛选、分页及操作入口，不内嵌表单，也不用弹窗或抽屉替代表单页。
- 列表使用统一服务端分页：默认每页 20 条，支持页码、每页条数及总数；数据库按稳定顺序计数与分页，翻页保留筛选。禁止全量查询后内存切片；非法参数返回 400，超末页回到末页。
- 新增和编辑可以复用表单，但保持独立入口、标题和提交行为。提供返回/取消入口，保存成功默认返回列表；从详情或工作流发起编辑时，保存和取消返回对应入口，从列表编辑须保留分页和筛选。校验失败保留输入、错误及返回上下文；现有页面改造时一并遵循，并验证完整跳转流程。

## 提交与安全

PR 说明用户可见行为、数据库/部署影响、实际验证命令及结果和未验证项。问题报告附版本、复现步骤、预期与实际结果。

不提交 `.env`、密码、API Key、内部令牌、加密主密钥、真实业务数据、Docker 卷及含凭据日志；示例使用 `.env.example` 占位值。日志先脱敏，安全问题按 [SECURITY](SECURITY.md) 私密报告。
