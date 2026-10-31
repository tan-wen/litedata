# 文档索引

从任务对应的主题进入，无需通读全部文档。启动与订单演示见 [项目入口](../README.md)，开发规范与检查命令见 [CONTRIBUTING](../CONTRIBUTING.md)。

| 任务 | 文档 |
| --- | --- |
| 首次操作、图文步骤 | [操作手册](user-manual.md)（截图为历史界面，当前流程以模块说明为准） |
| 开发部署、引擎版本、增量语义、故障恢复及验收 | [引擎运行说明](engine-runbook.md) |
| 生产安装、打包、备份恢复 | [生产部署](../deploy/production/README.md) |
| 多数据库接入、自动 ODS | [数据源](data-sources.md) |
| 模型、DDL、分桶及分区 | [数据建模](modeling.md)、[公共维度](dimension-models.md) |
| SQL、工作流画布、依赖与执行互斥 | [数据加工](sql-transforms.md) |
| Cron、运行实例与节点日志 | [调度运维](scheduling-operations.md) |
| 元数据采集与血缘 | [元数据与血缘](metadata-lineage.md) |
| 统一资产检索与业务说明 | [资产目录](asset-directory.md) |
| 规则、阈值、时间窗口与质量运行 | [数据质量](data-quality.md) |
| 指标口径、版本与字段血缘 | [指标管理](metrics.md) |
| Agent 接入、用户 Key 与权限 | [MCP](mcp.md) |
| 页面布局、主题与前端版本 | [UI 说明](ui-guide.md) |
| 容量规划与瓶颈分析 | [Doris 容量分析](doris-capacity-and-bottlenecks.md) |
| 端到端业务示例 | [化工园区](../examples/chemical-park/README.md)、[DIM 版本](../examples/chemical-park-dim/README.md) |

长期文档保留当前规则、使用步骤与验证边界。手册以 Markdown 和截图为维护源；HTML/PDF 导出件及其他生成物放 Git 忽略的 `output/`，历史过程记录通过 Git 查询。
