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

领取执行权时平台通过 JDBC 验证水位、索引，读取源库时间和有序列清单；worker 按数据库选择 reader、标识符引用方式和时间字面量，读取固定的 `[最近成功水位 - 5分钟, 源库上界)`。上下界保留微秒；时间戳列在源端格式化为文本后由 Doris 转换，避免 DataX 默认日期转换丢失小数秒，目标列也需声明匹配精度（如 DATETIME(6)）。平台、数据库和 worker 应统一时间口径。首批从1970年开始；目标表需预先建立 Doris UNIQUE KEY 表并保证列顺序及类型兼容。时间水位不捕获硬删除或超出回放窗口的历史回写，不提供 CDC 语义。

仅同步或同步后加工全部成功才推进水位。修改执行频率不会重置水位，也不会改变回放窗口；同一管道保持互斥。高频任务应结合源库压力和一次同步耗时选择周期。

## 部署与验证

升级需同时重建平台和 worker，新增 JDBC 驱动及 reader 才会生效：

```powershell
docker compose build platform dolphinscheduler
```

建议先暂停调度，等待实例完成后再重建运行容器，避免中断已领取执行权的任务。首次启动仍使用 `deploy/start.ps1` / `deploy/start.sh`。

`mvnw verify` 覆盖 JDBC 地址与驱动、真实 PostgreSQL 模式/水位/索引校验和原 MySQL 集成回归；`py -3 -m unittest discover -s deploy/tests -v` 检查四类 reader、SQL引用、固定微秒边界及输入拒绝。worker 镜像构建检查四类 reader 产物和驱动依赖。自动化通过不能替代 PostgreSQL、SQL Server、Oracle 各自的 DataX→Doris 实库链路验收。

2026-10-25 验证：全量 `mvnw clean verify` 123项通过；时间列精度调整后相关专项35项通过；替换工作流名称回归10项通过；Python runner测试9项通过；平台及worker镜像构建、Compose配置校验通过。真实 MySQL→DataX→Doris→API 冒烟成功；独立 PostgreSQL→DataX→Doris 传输核对数值和微秒精度成功，临时源容器与目标表已清理。真实 DS 每分钟定时触发成功，暂停后改为每20分钟并重新发布，数据库核对引擎 Cron 与配置一致、保留水位，测试调度已暂停。SQL Server、Oracle 未进行对应实库全链路验收；浏览器浅深主题与交互未单独复测。
