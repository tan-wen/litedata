# 数据加工与依赖调度

在“数据加工”中把 Doris 模型连接成实际可执行的分层链路。平台保存任务与节点实例，
DolphinScheduler 3.4.0 负责 DAG 依赖、调度和失败重试，worker 使用独立 Doris writer 执行 SQL。
新增 Flyway V3，不修改 V1/V2 或现有管道数据。

## 使用流程

1. 新增 ODS 可在“同步管道”自动读取来源结构、预览并创建；在“数据建模”主要定义并创建 DIM、DWD、DWS、ADS 表。主键须能支持同一业务结果重复覆盖。
2. 新增数据加工，选择目标模型，填写单条 `INSERT INTO ... SELECT`。
3. 选择直接上游 SQL 任务；需要先刷新 ODS 时，选择上游同步管道。系统会自动展开传递上游。
   可以选择多个上游；同一条链中共享节点只执行一次，保存时拒绝循环依赖。
4. 保存后检查依赖链预览，点击“运行整条依赖链”。每次都实际重跑全部上游，按本次上游成功后
   执行下游，不使用历史成功状态替代本次执行。没有声明的依赖不会被自动推断或触发。
5. 在末端任务启用定时调度，支持每 5 分钟、每小时整点、每日 02:00，均为北京时间。
   停用会下线 DS 调度，不会终止已经提交的实例。

例如先自动接入来源表（或选择已登记的 ODS UNIQUE 模型）配置同步管道，再建立：

```text
同步 orders → ods_orders
  → 清洗任务：ods_orders → dwd_orders
  → 汇总任务：dwd_orders → dws_orders
  → 应用任务：dws_orders → ads_orders
```

```sql
INSERT INTO dws_orders (customer_name, total_amount, order_count)
SELECT customer_name, SUM(amount), COUNT(*)
FROM dwd_orders
GROUP BY customer_name
```

同步管道不再新增或编辑内嵌 SQL。含旧 SQL 的管道会被拒绝运行和启用定时；在编辑页迁移时，系统要求先暂停相关定时且没有运行实例，随后创建或复用同目标、同 SQL 的独立任务，自动重接原下游依赖。原管道的运行记录和 SQL 归档保留。若旧 SQL 的目标不是已登记的非 ODS UNIQUE 模型，或目标存在冲突，系统会提示人工处理。

迁移不会自动启用调度。新加工任务沿用受支持的原周期；原管道使用其他自定义 Cron 时，新任务先采用每小时周期，请核对后再启用。旧管道的 Cron 仍保留。复用任务仅忽略 SQL 的语法空白及关键字大小写差异，不自动合并不同表达式或目标写法。

## 执行、版本与并发

- 每个任务只写入所选模型，一个目标模型只对应一个任务。目标为 DIM/DWD/DWS/ADS；ODS 由同步接入。
- SQL 仅允许 warehouse 中的单条 INSERT SELECT，拒绝 VALUES、跨库、任意脚本、DDL/DML 多语句、
  注释、副作用函数、读取自身目标表。支持 SELECT 关联、聚合和可解析 CTE；字段/SQL 兼容性最终由 Doris 校验。
- 调度发布真实 DS SHELL 节点及依赖边，脚本仅包含任务 ID、版本、末端 ID 和配置摘要。
  SQL 与连接凭据不会嵌入工作流定义。worker 从 root-only 配置取得 writer 凭据，执行前向平台领取 SQL 快照。
- 目标表在发布和领取时核对 UNIQUE KEY 等模型结构，源表在实际 SQL 执行时由 Doris 验证。
  节点超时 50 分钟，SQL 服务端超时 45 分钟，工作流超时按节点数分配，失败最多重试两次、间隔一分钟。
- 每个 SQL 任务有数据库运行互斥，同任务不能并发写入；跨工作流共享上游遇到占用时最多等待 5 分钟，
  然后为本链执行新的实例。同步节点沿用已有管道互斥，冲突由 DS 重试处理。
  建议仅在末端调度，避免多条链重复刷新共享上游；此处不保证所有共享源表的跨节点快照隔离。
- 任务、依赖和模型版本都影响工作流摘要。过期 SQL 节点的 claim 返回 409，执行节点拒绝旧快照。
  SQL 任务编辑前需停用引用它的所有末端调度；修改模型也会使已有快照失效，须重新发布。
- 同步节点仅在 DataX 成功写入已登记的 ODS UNIQUE 模型后推进源水位；后续独立 SQL 失败不会回退已完成的同步水位。重跑时同步管道按自身水位增量读取，各 SQL 层按其定义重算。
- 请求被 DS 接受仅表示已提交，实际结果以 SQL 节点回调与 DS 实例为准。平台保留 SQL、任务版本、
  发起末端、开始/结束时间和结果；详情显示本链及本任务最近 100 条 SQL 记录，同步记录在“同步管道”。
- 停用后可编辑并再次启用。摘要变化时创建新 DS 工作流、下线旧调度；旧定义及实例保留作历史。
  多个相同图的重复提交由 DS SERIAL_WAIT 排队，单节点仍受平台互斥保护。
- DS API 与平台 MySQL 不构成分布式事务。外部请求成功但响应丢失或平台提交失败时，可能留下引擎定义或调度；
  重复发布若提示引擎状态冲突，管理员应先核对 DS 侧同名定义及调度，确认并清理孤立项后再重试。
- 归档需先停用调度、等待本任务结束，并移除下游引用。保留物理数据和历史，任务名及目标模型仍占用。
  关联有效 SQL 任务的模型禁止归档。

UNIQUE KEY 覆盖相同主键不等于完整重算替换。源端硬删除、分组维度变化、条件过滤变化导致旧分组
失效时，普通 INSERT SELECT 不会清理旧结果。本次未提供 DELETE、INSERT OVERWRITE、分区替换、
运行日期参数或补数范围；生产口径需要先确定相应策略。

## 升级与验证

升级应用和 worker，两者必须同时更新；平台启动自动执行尚未应用的迁移，V16 新增旧管道 SQL 归档表。V16 不自动清空 SQL 或启用任务；旧管道通过编辑页迁移入口处理。

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

真实冒烟脚本在现有演示库创建独立来源、纯 ODS 同步管道、DWD/DWS/ADS 模型和 SQL 任务，
从末端运行真实 DAG，对照两行演示订单的汇总结果，等待一次真实五分钟定时触发并停用调度，
再验证上游 SQL 失败时下游未触发。脚本最多等待数分钟；从本地 `.env` 或环境读取凭据，输出不含密钥。
保留独立 `*_flow_*` 表、模型、任务、服务和历史作为证据，不清除原演示或业务数据。
`PLATFORM_URL` 指定平台地址，`SMOKE_DS_URL` 可指定主机可达的 DS 地址。

2026-10-23 已在当前 Doris 4.1.3 / DS 3.4.0 环境实测：纯同步 → DWD → DWS → ADS 全链成功，
API 返回 Ada=120.50/1 单、Linus=88.00/1 单；五分钟调度实际触发一次并成功，随后停用。
故障验证将独立测试 DWD 的来源改为不存在的表，DS 重试两次后工作流为 FAILURE，平台保留三次
FAILED，DWS/ADS 未新增执行记录；已恢复正常 SQL，测试定时保持关闭。
恢复后已重新运行规范的直接依赖链，三个 SQL 节点与 DS 工作流再次 SUCCESS；
最终 Maven verify 为 44 项通过，Python 执行器测试 7 项通过，Compose 配置及差异检查通过。
保留的演示末端为任务 ID 3，表为 `dwd_flow_853e15ec`、`dws_flow_853e15ec`、`ads_flow_853e15ec`。
期间修复了冒烟表单解析器把未勾选复选框也提交的问题，故障段使用修正后的解析器单独续跑通过。
这些验证不代替百万行容量或连续七天运行验收。

2026-10-28 收敛验证：Maven 全量 189 项通过，最终迁移专项 17 项通过，Python 执行器 10 项通过。真实环境两条旧管道迁移为独立任务 18，原 SQL 分别归档、原有 7 条运行记录保留；迁移后的两个同步节点和加工节点在 DS 实例 31 成功。新建的 ODS → 独立 ADS 加工 → API 链路通过。故意使 SQL 失败后，DataX / ODS 仍为 SUCCESS，水位等于同步批次上界且未回退，运行锁释放；已恢复测试 SQL，未启用新定时。

## 中断恢复

普通 SQL 拒绝会记录 FAILED 并释放任务锁，DS 可重试。网络断开、进程被强制停止或执行完成后回调失败，
结果可能未知，平台保留 RUNNING 锁，防止尚未停止的 SQL 与重试同时写入。
先在 DS 和 Doris 确认该节点及查询已经停止，并确认目标结果；再备份平台数据库，事务内锁定对应
`sql_task` 与 `sql_task_run`，将仍持有 `active_run_id` 的实例置 FAILED、填写恢复说明和结束时间，
清空对应任务的 `active_run_id`。随后从末端重新运行。首版不提供未经引擎状态确认的强制解锁按钮。

凭据不进入任务、表单、日志和回调；内部执行 API 要求独立令牌，页面沿用 ADMIN/DEVELOPER 写权限及 CSRF。

官方依据：[DS 3.4.0 WorkflowInstanceController](https://github.com/apache/dolphinscheduler/blob/3.4.0/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/controller/WorkflowInstanceController.java)、
[SchedulerServiceImpl](https://github.com/apache/dolphinscheduler/blob/3.4.0/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/service/impl/SchedulerServiceImpl.java)。
