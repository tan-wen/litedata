# 变更记录

## 0.1.0-SNAPSHOT（开发中）

尚未发布稳定版本。当前行为和限制见 [文档索引](docs/README.md)，逐次修改及历史验证记录可通过 Git 历史追溯。

- 平台：Spring Boot / MyBatis / Flyway、三种角色、独立资源页面、数据库分页、本地 Bootstrap 6 与浅深主题、统一面包屑。
- 接入：MySQL、PostgreSQL、SQL Server、Oracle 数据源及结构采集；自动创建或关联 ODS、时间水位增量同步、自定义 Cron 与凭据加密。
- 建模：ODS/DIM/DWD/DWS/ADS、公共维度 SCD1、三种 Doris 表模型、分桶与分区配置、DDL 预览建表及结构核对。
- 加工与工作流：独立 INSERT SELECT、旧管道 SQL 迁移、依赖 DAG、工作流画布与集中调度、不可变运行快照、实例及脱敏节点日志。
- 治理：元数据、表级血缘、资产目录、18 类质量规则、原子/复合/派生指标及版本化字段血缘。
- 服务与集成：受控只读 API、凭证轮换、限流与审计；用户级 API Key、MCP scope 与资源白名单。
- 交付：开发一键启动、生产 JAR + Compose 联网/离线包、备份恢复，以及基础/DIM 两套化工园区示例。

工程已从 `bigdata` 拆分为独立 `litedata`，包名为 `com.litedata`；旧 Compose 卷迁移见 [运行说明](docs/engine-runbook.md#目录与部署名称迁移)。
小数据真实引擎链路已有历史验证；容量、吞吐和连续运行仍须按 [验收步骤](docs/engine-runbook.md#验证与验收) 实测。
