# 化工园区 DIM 完整示例：公共企业维度与单位产品能耗分析

这个示例回答园区运营人员的三个问题：近七天消耗了多少蒸汽、电力和水？同类产品企业的单位产品用能有何差异？看到指标后，如何追到源记录和源字段，并验证纠错是否生效？

数据全部虚构：三家企业都生产示例产品 `RESIN_A`。这里展示日级经营分析，蒸汽与电力分别保留吨、kWh 单位，不混合相加，也不换算标准煤。表中 1.8 吨蒸汽/吨产品的标记线只是演示参数。跨企业比较还需要在真实业务中核对产品、工艺及计量边界。

本目录是可独立运行的 DIM 版本；[原版完整示例](../chemical-park/README.md)保留原有六模型、三加工流程。两版使用相同业务数据和验收口径，方便对比引入公共维度前后的变化。

| 对比项 | 原版 `chemical-park` | 本例 `chemical-park-dim` |
| --- | --- | --- |
| 企业属性来源 | DWD 直接关联 ODS 企业档案 | ODS 企业档案 → DIM 企业，再关联 DWD |
| 模型 / SQL 加工 | 6 个 / 3 个 | 7 个 / 4 个 |
| 维度身份 | 使用来源企业 ID | 显式配置业务键、展示字段和 SCD1 |
| 验收运行 | 纠错、重跑，共三轮 | 增加属性更新、恢复，共五轮 |
| 数据质量 | 源端异常与 DWD | 增加 DIM 成员质量和事实引用完整性 |
| 血缘 | 指标、DWD、ODS、业务源 | 企业维度字段额外经过 DIM |
| 看板 | 能耗、趋势、明细、阶段结果 | 增加 DIM 成员、SCD1 变更证据和维度血缘入口 |
| 默认资源前缀 | `cp_` | `cpdim_` |
| 默认产物目录 | `output/chemical-park` | `output/chemical-park-dim` |

本例前缀必须匹配 `cpdim_[a-z0-9]{6,12}`。两版不共享业务表、模型、任务或服务；重新运行须选择新前缀，不会覆盖上一轮资源。

## 1. 全流程

```text
MySQL 模拟业务库
  企业档案（3行）    每日公用工程读数（24行）    每日产量（24行）
          └────────────────┬───────────────────┘
                  三条 DataX 时间水位同步
                           ↓
             三张 Doris ODS 原始表（保留异常）
                   ├─ 企业档案 → DIM 公共企业维度（SCD1）─┐
                   └─ 用能读数、产量 ──────────────────┤
                                                     ↓
    DWD 企业日明细：关联公共维度、读数、产量，隔离负值与无效记录
                           ↓
    DWS 企业七日汇总 → ADS 园区七日汇总 → 数据服务 → 示例看板
              ↘
     4个原子指标 → 4个七日派生指标 → 2个单位产品复合指标
                                      ↓
                       保存的指标 SQL → 独立数据服务
                                      ↓
                  与 DWS 的加权口径对账 + 字段血缘追溯
```

DolphinScheduler 的末端任务包含三个同步节点和四个 SQL 节点；每个 SQL 节点等待其上游成功。SQL 的目标表均使用业务主键幂等写入。

## 2. 数据与粒度

| 源表后缀 | 粒度 / 主键 | 关键字段 | 行数 |
| --- | --- | --- | ---: |
| `enterprise` | 一家企业，`enterprise_id` | 企业名称、产品编码、`updated_at` | 3 |
| `utility` | 企业 × 日期，`record_id`；另有企业日期唯一约束 | 蒸汽吨、电量kWh、水m³、有效标记、`period_end`、`updated_at` | 24 |
| `production` | 企业 × 日期，`record_id`；另有企业日期唯一约束 | 产量吨、有效标记、`updated_at` | 24 |

生成仓库日历日期之前八个完整日期的记录。`stat_date` 是统计日期，`period_end` 为次日零点；它用于派生指标的滚动窗口。`updated_at` 则在生成或修正数据时更新，并有前导索引，用于同步水位。业务发生时间与数据变更时间分开，历史业务日期的纠错也能被增量读取。

七个模型分别为三个 ODS、`dim_enterprise`、`dwd_utility_day`、`dws_enterprise_7d`、`ads_park_7d`。物理表名、指标编码和任务名称带本次唯一前缀，例如 `cpdim_resin261026_utility`、`cpdim_resin261026_dwd_utility_day`；API slug 使用连字符，例如 `cpdim-resin261026-park-7d`。

`dim_enterprise` 粒度为一家企业，字段及角色如下：

| 字段 | 角色 | 用途 |
| --- | --- | --- |
| `enterprise_id BIGINT` | 非空业务键 / UNIQUE KEY | 稳定识别企业；更新属性时保持同一成员 |
| `enterprise_name VARCHAR(80)` | 展示名称 | 跨主题复用的企业名称 |
| `product_code VARCHAR(32)` | 维度属性 | 产品分类与筛选；本例每家企业只有一个示例产品 |
| `updated_at DATETIME(6)` | 维度属性 | 来源记录变更时间；不代表 SCD2 生效时间 |

在模型表单中选择 DIM，业务键填写 `enterprise_id`，展示字段填写 `enterprise_name`，策略选择 SCD1。维度加工的实际 SQL 形如：

```sql
INSERT INTO cpdim_resin261026_dim_enterprise
    (enterprise_id, enterprise_name, product_code, updated_at)
SELECT enterprise_id, enterprise_name, product_code, updated_at
FROM cpdim_resin261026_ods_enterprise;
```

DWD 将原先对 `ods_enterprise` 的关联改为 `dim_enterprise`，仍按企业 ID 关联，并与用能、产量的企业日期粒度对齐。DIM 的依赖是企业档案同步；DWD 的依赖是 DIM 加工、用能同步、产量同步。指标中仍显式配置 `enterprise_id` 分组，名称和产品属性沿公共维表统一提供。

## 3. 生成完整离线材料

在仓库根目录运行，Python 3.10+，仅使用标准库：

```powershell
python examples/chemical-park-dim/scenario.py --prefix cpdim_resin01 --as-of 2026-10-26 --output output/chemical-park-dim/offline-dim-resin01
```

输出包含：

| 文件 | 用途 |
| --- | --- |
| `manifest.json` | 全部源行、模型、同步、加工、指标、质量、服务和预期结果 |
| `source.sql` | 创建三个新源表和插入模拟数据；不包含清库操作 |
| `models.json` | 可对应平台模型表单的字段定义 |
| `dimensions.json` | DIM 企业模型的业务键、展示字段、SCD1 策略及字段定义 |
| `transforms.sql` | DIM、DWD、DWS、ADS 四个带真实前缀的 INSERT SELECT |
| `metrics.json` | 原子、派生和复合指标定义及完整公式 |
| `quality.json` | 源端、DIM 成员和 DWD 引用的质量任务与规则 |
| `expected.json` | 按模拟源行独立计算的验收结果 |

金额或计量相关的 DECIMAL 值在 JSON 中使用十进制字符串，避免浮点精度损失。离线的日期固定为传入的 `--as-of`；只生成文件，不连接或修改平台。输出文件已存在时拒绝覆盖。

## 4. 在真实开发环境一键跑通

先按项目 README 启动 Compose 开发环境，并确保平台包含指标及字段血缘功能。已有开发环境只更新应用时，可运行：

```powershell
docker compose up -d --build --no-deps platform
python examples/chemical-park-dim/run_demo.py
```

也可显式指定一个未使用的前缀：

```powershell
python examples/chemical-park-dim/run_demo.py --prefix cpdim_resin261026
```

脚本从当前开发环境的 `.env` 读取登录配置，使用管理页面与 CSRF 创建资源，实际调用 DataX、Doris 和 DolphinScheduler。它从仓库读取 `CURRENT_DATE` 决定模拟数据日期，避免把浏览器时区误当成数据库会话时区。仓库日历及当时的数据库时间会写入报告。示例的时间字段均为不带时区的 DATETIME，源 JDBC 使用 `serverTimezone=LOCAL`，使 DataX 的本地日历格式化保留源字段的钟面时间。实测会同时核对源端和 ODS 的 `period_end`，以及派生指标与 DWS 的窗口结果；该配置不代表其他类型时间字段的统一处理方案。驱动日历与时间瞬间的区别见 [MySQL Connector/J 时间处理说明](https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html)。

执行顺序：

1. 新建三个独立源表并造数，登记数据源并测试连接。
2. 创建七个逻辑模型（含 DIM 企业维度，业务键 `enterprise_id`、展示字段 `enterprise_name`、SCD1 策略），预览和确认 Doris 建表，核对物理结构。
3. 创建三条同步管道，以及 DIM → DWD → DWS → ADS 四个加工任务和依赖；DIM 依赖企业档案同步，DWD 同时依赖 DIM、用能与产量同步。
4. 创建源端、DIM 与 DWD 质量任务；确认预置负蒸汽行被源端检查发现。
5. 运行末端完整依赖链，等待实际 worker 成功回调，核对各层行数。
6. 修正源端负值并更新水位字段，再运行依赖链；源端质量恢复通过，DWD 从23行恢复24行。
7. 第三次运行同一依赖链，确认各层行数保持稳定，验证重复运行幂等。
8. 检查 DIM 业务键唯一、名称非空白和 DWD 企业引用；修改模拟企业名称并第四次运行，验证 SCD1 原成员覆盖、行数不增加、事实宽表名称更新；恢复名称后第五次运行。
9. 采集源库和 Doris 元数据，创建10个指标及其血缘快照。
10. 创建并发布5个只读服务，生成仅在进程内使用的 API Key，验证指标 SQL、业务查询和无凭据调用被拒绝。
11. 将真实查询返回值、资源 ID、验收步骤写入 `report.json`，生成可交互的 `dashboard.html`。

报告中的 `dimension.members` 是恢复名称后从 Doris 查询的成员记录，`dimension.scd1` 保留变更前、维度更新后、事实重算后、恢复后的实际名称和成员数；`dimension_lineage_path` 指向企业维度字段的精简血缘。看板只展示这些已观测结果，离线生成材料不会被当作实测数据。

脚本只创建独立前缀资源，不删除已有表、任务或数据。相同前缀不可重复创建；发生失败时，报告保留已创建资源的 ID，以便查看对应平台记录。默认不会启用持续定时调度，调度表达式已生成，可在确认后于末端任务页面启用。

## 5. 异常与纠错验收

| 阶段 | 源公用工程行数 | 源蒸汽负值 | DWD行数 | DWS / ADS |
| --- | ---: | ---: | ---: | --- |
| 首次生成与运行 | 24 | 1 | 23 | 3 / 1 |
| 修正源值、更新updated_at、增量重跑 | 24 | 0 | 24 | 3 / 1 |
| 再次运行 | 24 | 0 | 24 | 3 / 1 |

异常行在第八天，位于七日分析窗口之外。因此修正它会恢复八日 DWD 完整性，但七日看板数字应保持不变。质量扫描与加工调度是独立能力；本示例通过脚本显式检查扫描结果，未把它描述为平台内置的自动质量闸门。

第三家企业最近一天停产，产量为0，但保留蒸汽10吨、电500kWh、水5m³的待机用量。日单位产品指标返回 NULL；七日单位产品指标按 `SUM(用量) / SUM(产量)` 计算，保留待机消耗，不取每日比率的平均值。

## 6. 七日结果与业务解读

| 企业（虚构） | 有效天数 | 产量（吨） | 蒸汽（吨） | 电力（kWh） | 吨产品蒸汽（吨/吨） |
| --- | ---: | ---: | ---: | ---: | ---: |
| 华东树脂厂 | 7 | 756 | 1134 | 60480 | 1.500000 |
| 滨江树脂厂 | 7 | 588 | 1176 | 64680 | 2.000000 |
| 新城树脂厂 | 7 | 387 | 474.4 | 35330 | 1.225840 |
| 园区合计 | 21个企业日 | 1731 | 2784.4 | 160490 | 1.608550 |

园区七日用水为5414.6m³，吨产品电耗为 `160490 / 1731`。示例运营人员可以先发现滨江企业在演示阈值之上，查看其日趋势，再沿“蒸汽复合指标 → 七日蒸汽 / 七日产量 → 原子指标 → DWD表达式 → ODS → 源字段”确认口径与数据来源。

该判断只说明演示数据与所设参数的关系。实际节能分析还需在同产品、同工况下核实；本示例不执行生产控制或联锁操作。

## 7. 数据服务与应用

| 服务后缀 | 数据来源 / 参数 | 用途 |
| --- | --- | --- |
| `park-7d` | ADS，无业务参数 | 园区 KPI |
| `enterprise-7d` | DWS，无业务参数 | 企业对比 |
| `daily` | DWD，无业务参数 | 日趋势和明细 |
| `daily-trace` | DWD，`enterpriseId`、`statDate` | 定位某企业某天，返回安全除零比率 |
| `metric-steam` | 平台实际保存的复合指标 SQL | 指标口径直接服务化，与 DWS 对账 |

所有接口有固定 `pageNum`、`pageSize` 分页参数，使用 `X-API-Key` 认证。例如：

```sh
curl -H "X-API-Key: $CHEM_API_KEY" \
  'http://localhost:8080/api/data/cpdim-resin261026-daily-trace?enterpriseId=3&statDate=2026-10-23&pageNum=1&pageSize=20'
```

脚本不会把明文 API Key 写入报告、日志或看板。自行调用时，进入对应新建服务页面生成并保存本次凭据，再设置 `CHEM_API_KEY`；生成新凭据会吊销该服务的旧凭据。示例看板展示本次已验证查询结果的快照，可筛选企业、查看趋势和明细，并跳转真实平台资源；它不会在浏览器中保存密钥或自动轮询 API。

## 8. 检查与边界

```powershell
python -m unittest discover -s examples/chemical-park-dim -p "test_*.py"
docker compose --env-file .env.example config --quiet
```

离线生成和单元测试不等于引擎链路验收。仅当 `run_demo.py` 实际执行完成、`report.json` 状态为 `success` 时，才说明该前缀的本次 DataX → Doris → DS → API 示例经过对账。连续七天稳定性、容量、实时采集、生产告警和自动调度触发不包含在本次日级样例验收中。

## 9. 公共维度与历史语义

`dim_enterprise` 是园区共享企业维度，DWD 从它读取企业名称和产品编码。其余非主键字段是维度属性，DIM 与事实加工可以共享调度依赖。SCD1 只保留当前属性，示例重算历史明细时使用当前企业属性；不保留企业迁移、产品变更等历史版本。DIM 的业务键、展示字段及更新策略属于模型配置，指标中的 `enterprise_id` 统计分组仍需显式选择。完整边界见[公共维度建模](../../docs/dimension-models.md)。

## 10. 本次独立示例验证

2026-10-26 在本地 Doris 4.1.3 / DolphinScheduler 3.4.0 环境执行：

```powershell
python examples/chemical-park-dim/run_demo.py --prefix cpdim_resin261026b
```

结果为 `success`：七模型、三同步、四加工完成五轮运行；DIM 保持 3 个成员，DWD 纠错后为 24 行。源端异常、DIM 成员质量和 DWD 企业引用通过验证；企业名称更新后同步覆盖 DIM 与重算后的 DWD，恢复后与最初成员记录一致。10 个指标、5 个受控 API 与独立预期值对账通过，无凭据调用返回 401；企业维度字段血缘追到 DIM、ODS 和业务源。

本机产物为 `output/chemical-park-dim/cpdim_resin261026b/report.json` 与同目录 `dashboard.html`。独立 DIM 版本 15 项 Python 测试通过，原版 10 项测试通过；两套示例均纳入 CI。报告和看板只保存在本地，不包含凭据，不纳入版本控制；本次未验证持续定时触发、连续七天运行和生产容量。
