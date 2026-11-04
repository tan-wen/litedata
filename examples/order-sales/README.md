# 订单销售分析示例

以 6 笔模拟订单演示：MySQL 接入 → ODS 贴源 → DWD 清洗 → ADS 客户日汇总 → 质量校验 → 指标与数据服务。
平台配置通过管理页面维护；输入样本见 [seed.sql](seed.sql)。已有化工园区和基础订单示例可独立使用。

## 输入样本

在本地开发 Compose 环境执行：

```powershell
Get-Content -Raw .\examples\order-sales\seed.sql | docker compose exec -T demo-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4'
```

样本表为 `demo_source.sales_demo_orders`，包含非空的 `id`、`customer_name`、`amount`、`updated_at`，水位字段有前导索引。
3 个客户各有 2 笔订单，销售额分别为 200、390、400 元，总额 990 元。时间使用 UTC；重新执行样本 SQL 会刷新源端更新时间。

## 在页面中配置

1. **数据源**：`订单销售示例源`，MySQL 地址与开发环境的 `demo-mysql/demo_source` 一致；使用开发演示只读账号，保存后测试连接。
2. **同步管道**：`订单销售贴源同步`，来源表 `sales_demo_orders`，水位 `updated_at`，自动创建 `ods_sales_orders`。保留来源主键 `id`，使用 UNIQUE、固定 1 桶、1 副本、不分区。
3. **数据建模**：创建以下 UNIQUE 模型，固定 1 桶、1 副本、不分区，预览 DDL 后执行建表。

| 模型 | 字段与类型 | 键列 |
| --- | --- | --- |
| `dwd_sales_order_detail` · 订单销售明细 | `id BIGINT`、`customer_name VARCHAR(512)`、`amount DECIMAL(18,2)`、`updated_at DATETIME(6)` | `id` |
| `ads_sales_customer_daily` · 客户每日销售汇总 | `stat_date DATE`、`customer_name VARCHAR(512)`、`total_amount DECIMAL(18,2)`、`order_count BIGINT` | `stat_date, customer_name` |

字段均不可空。主题域为“销售分析”，ADS 按源端 UTC 日期分组。

4. **数据加工**：创建 `订单销售分析流程`，在画布内新增以下两个 SQL 节点，并连线：

```text
订单销售贴源同步 → 清洗订单销售明细 → 汇总客户每日销售
```

明细清洗：

```sql
INSERT INTO dwd_sales_order_detail (id, customer_name, amount, updated_at)
SELECT id, TRIM(customer_name), amount, updated_at
FROM ods_sales_orders
```

客户日汇总：

```sql
INSERT INTO ads_sales_customer_daily (stat_date, customer_name, total_amount, order_count)
SELECT DATE(updated_at), customer_name, SUM(amount), COUNT(*)
FROM dwd_sales_order_detail
GROUP BY DATE(updated_at), customer_name
```

保存画布后运行整链，在运行实例中确认三个节点成功。Cron 为 `0 0 2 * * ?`（北京时间），示例默认停用定时；可手动运行。

5. **数据质量**：`客户日销售汇总校验`，Doris 表 `ads_sales_customer_daily`，配置客户名称非空、销售额非负、样本汇总行数等于 3，手动运行并查看规则结果。固定行数用于本样本验收；业务应用应按实际数据量调整阈值或检测窗口。
6. **指标管理**：创建以下指标，负责人“销售数据组”。

| 编码 | 类型与口径 | 样本结果 |
| --- | --- | --- |
| `sales_amount` | 原子：明细模型 `SUM(amount)`，时间字段 `updated_at` | 990 元 |
| `sales_order_count` | 原子：明细模型 `COUNT(*)`，时间字段 `updated_at` | 6 笔 |
| `sales_amount_7d` | 派生：`sales_amount`，回看 7 天 | 990 元 |
| `sales_avg_order_value` | 复合：`[sales_amount] / [sales_order_count]` | 165 元/笔 |

在指标血缘中可追溯源端字段、ODS、DWD 和指标公式的版本化关系。

7. **数据服务**：`客户每日销售查询`，服务标识 `sales-customer-daily`，保存以下查询后发布；管理员可在页面生成调用密钥。

```sql
SELECT stat_date, customer_name, total_amount, order_count
FROM ads_sales_customer_daily
WHERE stat_date = :statDate
ORDER BY customer_name
```

```sh
curl -H 'X-API-Key: <页面生成的密钥>' \
  'http://localhost:8080/api/data/sales-customer-daily?statDate=<源端UTC日期>&pageNum=1&pageSize=10'
```

该日期的样本返回 3 行，每行订单数为 2。可在“调用记录”查看状态、耗时和行数。
最后在“元数据与血缘”采集 Doris，在“资产目录”检索 `ads_sales_customer_daily`，查看业务说明、负责人、标签和关联质量任务。

## 边界

- 同一日期重跑按 UNIQUE 键覆盖，样本仍为 6 条明细、3 条汇总、总额 990 元。
- 水位不是 CDC。源端硬删除和分组日期变化不会自动清除已存在的汇总行，需对账处理；跨日期重新播种样本时应按查询日期验收。
- 已物理建表的模型不允许通过“删除”移除；存在有效依赖的对象也不能删除。仅未建表且无依赖的草稿可逻辑删除，详见[数据建模](../../docs/modeling.md)。
