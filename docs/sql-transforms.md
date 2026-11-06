# 数据加工与依赖调度

在“数据加工”中把 Doris 模型连接成实际可执行的分层链路。平台保存任务与节点运行记录，
DolphinScheduler 3.4.0 负责 DAG 依赖、调度和失败重试，worker 使用独立 Doris writer 执行 SQL。
SQL 任务保存 SQL 定义和版本，工作流保存 SQL 依赖图、触发模式和调度绑定；升级通过新增 Flyway 迁移完成。

## 使用流程

1. 新增 ODS 可在“数据同步”点击“新建同步任务”，自动读取来源结构、预览并创建；在“数据建模”主要定义并创建 DIM、DWD、DWS、ADS 表。主键须能支持同一业务结果重复覆盖。
2. 在“数据加工”点击“新建加工任务”，分步填写基本信息、触发方式及 Cron 表达式或放行来源，保存后打开画布。
3. 在画布选择目标模型并填写单条 `INSERT INTO ... SELECT`，编排 SQL 依赖，保存后返回数据加工列表启用定时或事件订阅。创建、连线、保存和排查的完整步骤见[工作流画布使用说明](workflow-canvas-guide.md)。

## 工作流执行与历史

工作流的触发、上游重跑和链内执行顺序统一见[工作流如何执行](workflow-canvas-guide.md#1-先了解加工任务如何执行)。

每个工作流独立保存成员与依赖，SQL 任务只能属于一个加工任务。画布保存会在事务内校验完整图、任务和模型版本、运行互斥及受影响链路的启用状态，不会局部保存依赖。私有 SQL、布局与迁移核对规则见[画布使用说明](workflow-canvas-guide.md#7-保存画布与处理私有-sql)。

同步任务不加入加工画布。可选质量门禁、单来源订阅及事件投递规则见[数据就绪事件](data-ready-triggers.md)。新发布的加工定义保存不可变 SQL 拓扑快照，用于运行图展示。

例如先自动接入来源表（或选择已登记的 ODS UNIQUE 模型）配置同步任务，再建立：

```text
同步 orders → ods_orders → 可选质量检查 → 数据就绪事件
  → 加工任务：清洗任务：ods_orders → dwd_orders
  → 汇总任务：dwd_orders → dws_orders
  → 应用任务：dws_orders → ads_orders
```

```sql
INSERT INTO dws_orders (customer_name, total_amount, order_count)
SELECT customer_name, SUM(amount), COUNT(*)
FROM dwd_orders
GROUP BY customer_name
```

同步任务仅负责 ODS 接入；加工 SQL 在所属流程内维护。开发测试按新模式重建配置，不迁移旧混合依赖图或内嵌 SQL。

## 执行、版本与并发

- 每个任务只写入所选模型，一个目标模型只对应一个任务。目标为 DIM/DWD/DWS/ADS；ODS 由同步接入。
- SQL 仅允许 warehouse 中的单条 INSERT SELECT，拒绝 VALUES、跨库、任意脚本、DDL/DML 多语句、
  注释、副作用函数、读取自身目标表。支持 SELECT 关联、聚合和可解析 CTE；字段/SQL 兼容性最终由 Doris 校验。
- 调度发布真实 DS SHELL 节点及依赖边，脚本仅包含任务 ID、版本、工作流 ID 和配置摘要。
  SQL 与连接凭据不会嵌入工作流定义。worker 从 root-only 配置取得 writer 凭据，执行前向平台领取 SQL 快照。
- 目标表在发布和领取时核对 UNIQUE KEY 等模型结构，源表在实际 SQL 执行时由 Doris 验证。
  节点超时 50 分钟，SQL 服务端超时 45 分钟，工作流超时按节点数分配，失败最多重试两次、间隔一分钟。
- 每个 SQL 任务有数据库运行互斥，同任务不能并发写入。同步独立调度，质量门禁与事件触发规则见[数据就绪事件](data-ready-triggers.md)。
- 任务、依赖和模型版本都影响工作流摘要。过期 SQL 节点的 claim 返回 409，执行节点拒绝旧快照。
  SQL 任务编辑前需停用所属工作流调度；修改模型也会使已有快照失效，须重新发布。
- 同步节点仅在 DataX 成功写入已登记的 ODS UNIQUE 模型后推进源水位；后续独立 SQL 失败不会回退已完成的同步水位。重跑时同步任务按自身水位增量读取，各 SQL 层按其定义重算。
- 请求被 DS 接受仅表示已提交，实际结果以 SQL 节点回调与 DS 实例为准。平台保留 SQL、任务版本、
  发起工作流、开始/结束时间和结果；详情显示本任务最近 100 条 SQL 记录，同步记录在“数据同步”。
- 停用后可编辑并再次启用。摘要变化时创建新 DS 工作流、下线旧调度；旧定义及实例保留作历史。
  多个相同图的重复提交由 DS SERIAL_WAIT 排队，单节点仍受平台互斥保护。
- DS API 与平台 MySQL 不构成分布式事务。外部请求成功但响应丢失或平台提交失败时，可能留下引擎定义或调度；
  重复发布若提示引擎状态冲突，管理员应先核对 DS 侧同名定义及调度，确认并清理孤立项后再重试。
- 归档需先停用调度、等待本任务结束，并从所属工作流移除后保存。保留物理数据和历史，同时释放任务名及目标模型；关联有效 SQL 任务的模型禁止归档。

UNIQUE KEY 覆盖相同主键不等于完整重算替换。源端硬删除、分组维度变化、条件过滤变化导致旧分组
失效时，普通 INSERT SELECT 不会清理旧结果。本次未提供 DELETE、INSERT OVERWRITE、分区替换、
运行日期参数或补数范围；生产口径需要先确定相应策略。

## 升级与验证

升级应用和 worker，两者必须同时更新；平台启动自动执行尚未应用的迁移，V25 增加单来源事件触发所需的配置和持久化记录，不转换旧混合依赖配置。

```sh
docker compose up -d --build --no-deps dolphinscheduler platform
```

```powershell
$env:JAVA_HOME='C:\Java\jdk-25.0.3'
.\mvnw.cmd --batch-mode --no-transfer-progress verify
python -m unittest discover -s deploy/tests -v
docker compose --env-file .env.example config --quiet
python deploy/transform_smoke.py
```

Maven 以 Testcontainers MySQL 验证元数据/事务、菱形图去重、循环依赖回滚、纯同步兼容、
版本与模型摘要、运行互斥和幂等回调、页面/权限/CSRF、发布及启停重试。
这些测试中 Doris 和调度端明确 mock；客户端另使用本地 HTTP 服务校验实际 DAG 表单。
Python 测试不依赖数据库，验证执行器失败及保密行为。

真实冒烟脚本在现有演示库创建独立来源、纯 ODS 同步任务、DWD/DWS/ADS 模型和 SQL 任务，
从工作流运行真实 DAG，对照两行演示订单的汇总结果，等待一次真实五分钟定时触发并停用调度，
再验证上游 SQL 失败时下游未触发。脚本最多等待数分钟；从本地 `.env` 或环境读取凭据，输出不含密钥。
保留独立 `*_flow_*` 表、模型、任务、服务和历史作为证据，不清除原演示或业务数据。
`PLATFORM_URL` 指定平台地址，`SMOKE_DS_URL` 可指定主机可达的 DS 地址。

历史验证曾覆盖纯 ODS 同步到多层加工和 API、定时启停、上游失败阻断下游、水位推进与失败不回退、
旧管道迁移及归档、工作流图与运行快照，以及画布编辑和窄屏页面交互。这些是过去的验证范围，
每次发布仍需重新验证，且不代替百万行容量或连续七天运行验收。

## 中断恢复

普通 SQL 拒绝会记录 FAILED 并释放任务锁，DS 可重试。网络断开、进程被强制停止或执行完成后回调失败，
结果可能未知，平台保留 RUNNING 锁，防止尚未停止的 SQL 与重试同时写入。
先在 DS 和 Doris 确认该节点及查询已经停止，并确认目标结果；再备份平台数据库，事务内锁定对应
`sql_task` 与 `sql_task_run`，将仍持有 `active_run_id` 的实例置 FAILED、填写恢复说明和结束时间，
清空对应任务的 `active_run_id`。随后从工作流重新运行。首版不提供未经引擎状态确认的强制解锁按钮。

凭据不进入任务、表单、日志和回调；内部执行 API 要求独立令牌，页面沿用 ADMIN/DEVELOPER 写权限及 CSRF。

官方依据：[DS 3.4.0 WorkflowInstanceController](https://github.com/apache/dolphinscheduler/blob/3.4.0/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/controller/WorkflowInstanceController.java)、
[SchedulerServiceImpl](https://github.com/apache/dolphinscheduler/blob/3.4.0/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/service/impl/SchedulerServiceImpl.java)。
