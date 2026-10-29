# 多数据库数据源与 ODS 同步

“数据源 → 新增”可选择 MySQL、PostgreSQL、SQL Server、Oracle。类型由 JDBC 地址识别，选择的类型必须与地址一致；旧 MySQL 登记无需迁移。平台元数据库仍使用 MySQL，ODS 目标仍为 Doris。

| 类型 | JDBC 示例 | 读取范围 | DataX reader |
| --- | --- | --- | --- |
| MySQL | `jdbc:mysql://host:3306/database` | 地址中的 database | mysqlreader |
| PostgreSQL | `jdbc:postgresql://host:5432/database?currentSchema=public` | 默认 public；currentSchema 可指定单个模式 | postgresqlreader |
| SQL Server | `jdbc:sqlserver://host:1433;databaseName=database;encrypt=true` | dbo 模式 | sqlserverreader |
| Oracle | `jdbc:oracle:thin:@//host:1521/service` | 登录用户模式 | oraclereader |

Oracle 当前接收服务名形式的 Thin URL，不接收 SID、TNS 描述符或 URL 内嵌凭据。用户名和密码分别填写；URL 只允许各类型的有限连接选项，拒绝驱动工厂、反序列化、本地文件、认证钩子及 URL 密码等参数。密码仍加密存储且不回显。

源表及列使用实际大小写、单个标识符（字母、数字、下划线，最长64字符）；Oracle 的常规未加引号表一般须填写大写名称。模式来自数据源配置，同步表名不再拼接额外模式或库名。已被任务引用的数据源不能改地址；非 MySQL 源也不能更换用户名，以免改变模式及历史水位口径。

连接测试按数据库使用查询（Oracle 为 `SELECT 1 FROM DUAL`），元数据采集读取对应数据库/模式的表、视图、列、主键与备注。新增数据库的数据质量扫描尚未适配，保存质量任务时会明确拒绝；原有 MySQL/Doris 质量功能继续可用。

## 同步边界

所有类型使用批量时间水位同步：源表水位须为 NOT NULL、不带时区的时间戳，并有以水位列开头的完整索引。MySQL 使用 DATETIME/TIMESTAMP，PostgreSQL 使用 timestamp without time zone，SQL Server 使用 datetime/datetime2（timestamp/rowversion 是二进制版本号，不能用作时间水位），Oracle 使用 TIMESTAMP。带时区列不属于当前支持范围。

领取执行权时平台通过 JDBC 验证水位、索引，读取源库时间和有序列清单；worker 按数据库选择 reader、标识符引用方式和时间字面量，读取固定的 `[最近成功水位 - 5分钟, 源库上界)`。上下界保留微秒；时间戳列在源端格式化为文本后由 Doris 转换，避免 DataX 默认日期转换丢失小数秒，目标列也需声明匹配精度（如 DATETIME(6)）。平台、数据库和 worker 应统一时间口径。首批从1970年开始；目标必须是已登记、已建表并核对的 ODS UNIQUE 模型，字段按名称对应且类型兼容，不要求物理字段顺序与来源相同。时间水位不捕获硬删除或超出回放窗口的历史回写，不提供 CDC 语义。

只有 DataX 成功写入 ODS 才推进水位。清洗、关联和汇总必须配置为独立 SQL 加工任务；其失败不会回退已成功同步的水位。修改执行频率不会重置水位，也不会改变回放窗口；同一管道保持互斥。高频任务应结合源库压力和一次同步耗时选择周期，定时建议只在末端加工任务启用。

## 自动 ODS 接入

新增同步管道先在第一步顶部选择 ODS 接入方式。默认“自动读取结构并创建 ODS”显示四步：“来源与目标 → 字段与键列 → Doris 物理设置 → 调度与确认”；“接入已有 ODS 模型”仅显示“来源与目标 → 调度与确认”两步，并编号为1、2。来源表下拉框随数据源联动加载；加载失败时可手动填写并在读取结构时核对。自动模式将来源字段映射为小写目标字段，来源主键建议为 Doris 键；没有主键时，必须显式选择非空、稳定且唯一的业务键并重新预览。确认映射和 DDL 后才一次性创建物理表、登记模型和管道。

预览不是写操作。创建时服务端会重新读取来源并核对结构指纹；字段、类型、键列、分区或分桶设置变化后必须重新预览。平台不会自动 ALTER 或 DROP 目标表，也不会把时间水位升级为 CDC。默认是全部业务键 HASH、Doris AUTO 分桶、1 副本和不分区；第三步可独立调整分桶键、桶数、副本数，以及自动范围/列表、手动范围/列表和动态分区 PROPERTIES。UNIQUE KEY 同步只支持 HASH，分桶、分区列必须属于非空业务键；AUTO LIST 须使用固定桶数。不要仅为了按时间分区把可变水位列加入业务键，这会改变覆盖写入的唯一性口径。

“接入已有 ODS 模型”仍可选择已登记、建表并核对的 ODS UNIQUE 目标，适合已有分区或物理策略的表。编辑已有管道时同步边界固定，保持这一模式。

自动接入的管道会保存源字段、类型、可空和主键信息的结构基线，并在提交运行、启用调度及领取执行权时重新核对源结构和 ODS 模型/物理表；变化时阻断执行，不推进水位。历史管道与“接入已有模型”模式保留既有行为，不会自动补录基线。若需接收源结构变更，先核对影响，再预览接入新的目标表和管道，避免复用旧水位。

类型转换采用保守规则：字符容量按 UTF-8 最多四字节预留，普通日期时间精度最多六位；二进制、带时区时间、不能可靠表示的高精度数值等会明确拒绝。当前自动建模最多100列、表清单最多1000张；仅读取结构，不扫描业务行。Doris 建表与平台数据库不属于同一事务，异常后不会自动删表；重试预览会核对可能已创建的物理表，完全一致才允许登记接入。

## 部署与验证

升级需同时重建平台和 worker，新增 JDBC 驱动及 reader 才会生效：

```powershell
docker compose build platform dolphinscheduler
```

建议先暂停调度，等待实例完成后再重建运行容器，避免中断已领取执行权的任务。首次启动仍使用 `deploy/start.ps1` / `deploy/start.sh`。

`mvnw verify` 覆盖 JDBC 地址与驱动、真实 PostgreSQL 模式/水位/索引校验和原 MySQL 集成回归；`py -3 -m unittest discover -s deploy/tests -v` 检查四类 reader、SQL引用、固定微秒边界及输入拒绝。worker 镜像构建检查四类 reader 产物和驱动依赖。自动化通过不能替代 PostgreSQL、SQL Server、Oracle 各自的 DataX→Doris 实库链路验收。

`python deploy/ods_sync_smoke.py` 验证自动 ODS 接入全链路，会生成唯一名称的合成来源表、模型、管道和只读服务；不会启用定时，测试资源保留供查看。2026-10-29 实测通过：从预览直接创建 ODS 并执行 DolphinScheduler→DataX→Doris→API，核对大小写字段映射、键列前置后的字段对应、中文和 emoji、金额小数及 DATETIME(6) 微秒；源表加列后阻止再次运行，随后恢复测试表结构。全量 Maven 回归203项通过，最终相关59项及页面回退10项复测通过，Python runner 11项通过；新增自动建模链路尚未完成 PostgreSQL、SQL Server、Oracle 实库验收。

同日四步向导迭代验证：相关 Maven 回归72项及最终 ODS 专项13项复测通过；浏览器核对来源表联动、步骤切换、配置回显、AUTO LIST 固定分桶约束和修改设置后的重新确认。真实冒烟使用来源复合主键中的稳定业务日期做 AUTO RANGE 分区、订单键做 HASH 分桶并指定3个桶，自动建表、同步、API值核对和源结构漂移拦截均通过；连续两次同步后 API 仍只返回同一条完整业务记录，覆盖写入与已有自动分区的结构核对也通过实测。当前测试脚本按浏览器方式提交重复键名，以完整保留复合主键。

2026-10-25 验证：全量 `mvnw clean verify` 123项通过；时间列精度调整后相关专项35项通过；替换工作流名称回归10项通过；Python runner测试9项通过；平台及worker镜像构建、Compose配置校验通过。真实 MySQL→DataX→Doris→API 冒烟成功；独立 PostgreSQL→DataX→Doris 传输核对数值和微秒精度成功，临时源容器与目标表已清理。真实 DS 每分钟定时触发成功，暂停后改为每20分钟并重新发布，数据库核对引擎 Cron 与配置一致、保留水位，测试调度已暂停。SQL Server、Oracle 未进行对应实库全链路验收；浏览器浅深主题与交互未单独复测。
