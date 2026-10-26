# 数据建模首版

本模块维护逻辑结构，并向当前单机 Doris 4.1.3 的 `warehouse` 建表。
新增 Flyway `V2__data_modeling.sql`，应用启动时自动升级平台元数据库，保留原有管道与服务数据。

## 使用流程

1. 进入“数据建模”，新增物理表名、中文名称、主题域、数仓分层和分桶数。
2. 维护字段名、类型、主键、可空、注释和标准备注；主键须连续位于字段列表前面且不可空。
   可添加、移除和上下移动字段；最多 100 个。表名保存后固定，模型修改会增加版本。
3. 保存并预览生成的 DDL，确认目标为 `warehouse` 后勾选确认、执行建表。
4. 检查执行结果，或对已有表执行“结构比对”。核对成功后状态为“已核对”，编辑后为“模型已变更”。
5. 在“同步管道”配置数据写入。模型归档仅移除目录展示，保留物理表、模型字段与执行历史。
   已归档的表名仍占用，历史目前保留在元数据库中。

ODS 数据来自业务库的 DataX 同步；其他分层通过 Doris `INSERT INTO ... SELECT` 写入。
现有管道保留同步后执行一条加工 SQL，例如 ODS → ADS；独立 数据加工与多层依赖编排已提供，
见 [数据加工](sql-transforms.md)。按日期重算与分区替换尚未提供。UNIQUE KEY 保证相同业务主键覆盖写入，但不会自动删除
因源数据删除或维度变化而失效的旧汇总行，后续加工任务需明确重跑和覆盖语义。

## 当前边界

- 支持 ODS、DIM、DWD、DWS、ADS；DIM 提供业务键、展示名称字段、维度属性与 SCD1 当前值覆盖语义。操作、质量与历史处理边界见[公共维度建模](dimension-models.md)。

- 生成固定 `ENGINE=OLAP`、`UNIQUE KEY`、按全部主键 HASH 分桶、1–32 桶、单副本和 merge-on-write。
- 类型支持整数、BOOLEAN、DATE、DATETIME(0–6)、CHAR(1–255)、VARCHAR(1–65533)、STRING、
  DECIMAL(1–38, 0–精度)、FLOAT、DOUBLE。本期禁止 STRING 和浮点类型作为业务主键。
- 表中文名、业务说明和字段注释不接受反斜杠及控制字符：实测 Doris 4.1.3 的 COMMENT 会丢弃
  反斜杠。单引号采用 Doris 字面量转义，建表连接固定会话 sql_mode，保证解析一致。
- 只执行服务端生成的单条 CREATE TABLE，不接受用户提交任意 DDL。已有表创建会失败并留记录；
  核对已有表请用“结构比对”。没有自动 ALTER、DROP、结构回滚、分区配置或 Doris 逆向导入。
- 比对信息来自 `information_schema.COLUMNS` 和 `SHOW CREATE TABLE`，覆盖字段名/类型/顺序/
  可空/注释、UNIQUE KEY、HASH 字段与桶数；不比较表说明、业务分层/主题、标准备注、分区或其他引擎属性。
- 标准备注当前为自由文本元数据，没有独立数据标准字典及强制映射。
- ADMIN / DEVELOPER 可修改、建表、比对及归档，VIEWER 可查看。页面写操作要求 CSRF。
  物理化前检查模型版本及预览摘要；保存、建表、比对和归档写审计，建表/比对保留版本、操作人、
  完整 DDL 快照与结果。详情展示最近 30 条执行记录，数据库保留全部记录。
- Doris DDL 与平台 MySQL 不能组成分布式事务。连接超时、进程中断或元数据库提交失败时，
  物理表可能已经创建，先比对结构确认结果。普通连接/执行异常返回脱敏信息并保留失败记录。

## 已有环境升级

新部署使用更新后的 bootstrap 自动授予 writer 在 `warehouse.*` 的 `CREATE_PRIV`。
已有环境可由 Doris 管理员执行以下最小授权（默认固定账号）：

```sql
GRANT CREATE_PRIV ON warehouse.* TO 'platform_writer';
```

随后从仓库根目录重建平台，让 Compose 新增的 writer 环境变量生效：

```sh
docker compose up -d --build --no-deps platform
```

只读查询账号保持 SELECT 权限。不要把 writer 密码填入模型或服务 SQL；凭据从本地环境注入。
独立部署应将建表权限限定在目标库，当前页面固定目标 `warehouse`。

## 验证

```powershell
$env:JAVA_HOME='C:\Java\jdk-25.0.3'
.\mvnw.cmd --batch-mode --no-transfer-progress verify
docker compose --env-file .env.example config --quiet
python deploy/modeling_smoke.py
```

Maven 使用 Testcontainers MySQL 验证迁移、模型和字段持久化、页面/表单、角色与 CSRF、版本冲突、
预览防篡改、执行历史和结构差异；测试中的 Doris gateway 使用明确的 mock，不能代替实际建表。
`modeling_smoke.py` 用运行中平台和真实 Doris 验证建表及类型/注释往返、匹配、重复建表失败记录、
模型修改和结构差异；从本地 `.env` 或环境读取管理员密码，不输出凭据。可通过 `PLATFORM_URL`
指定地址。脚本会保留独立的 `ods_model_smoke_*` 表、逻辑模型及记录作为验证证据，不修改业务表。

2026-10-23 验证结果：全量 Maven verify 28 项通过；注释兼容性修正后重新执行建模测试 11 项通过；
Compose 配置解析通过。当前真实 Doris 4.1.3 冒烟通过保存、DDL 预览、建表、10 个字段的类型/注释
往返、结构一致、重复建表失败留痕、模型更新、过期预览拦截和结构差异识别。
成功样例为模型 ID 3、`warehouse.ods_model_smoke_33f49292c1`，保留 v1 成功和 v2 注释变更差异作为证据。
这些验证覆盖模型结构，不代表加工数据正确性、容量或连续运行验收。

官方语法依据：[Doris 4.x CREATE TABLE](https://doris.apache.org/docs/4.x/sql-manual/sql-statements/table-and-view/table/CREATE-TABLE/)、
[COLUMNS 元数据](https://doris.apache.org/docs/4.x/admin-manual/system-tables/information_schema/columns/)。
