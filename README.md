# LiteData

LiteData（仓库名 `litedata`）是面向单机试点的轻量数据中台，采用服务端渲染页面，
围绕 MySQL 每小时增量同步、Doris 幂等加工、DolphinScheduler 调度和受控数据 API 构建数据链路。

仓库：[tan-wen/litedata](https://github.com/tan-wen/litedata)。当前版本为 `0.1.0-SNAPSHOT`，
处于首版开发与引擎联调阶段。平台功能已有实现及自动化测试，完整引擎链路、百万行压测和连续七天运行仍待验收。
详细范围见 [实施及验收计划](docs/implementation-plan.md)，操作与验证边界见 [引擎运行说明](docs/engine-runbook.md)。

## 项目能力

| 模块 | 当前实现 |
| --- | --- |
| 工作台 | 数据源、同步管道、数据服务统计及最近运行实例 |
| 数据源 | MySQL JDBC 登记、凭据加密存储和连接测试 |
| 同步管道 | 时间水位增量、运行互斥、执行实例及成功后推进水位 |
| 加工与调度 | DataX runner、Doris INSERT SELECT、DS 工作流提交及定时调度代码；完整引擎仍待联调 |
| 数据服务 | 受控 SELECT、命名参数、发布/下线、凭证轮换、限流与调用审计 |
| 账号权限 | ADMIN / DEVELOPER / VIEWER，管理页面 Session 登录与 CSRF 防护 |

```text
MySQL 源表 → DataX 时间水位增量 → Doris ODS → INSERT SELECT → Doris ADS → 数据 API
              ↑ DolphinScheduler 调度、重试及 worker 执行回调 ↑
LiteData：页面管理、元数据、凭据、水位和运行状态；MySQL：平台及独立调度元数据库
```

技术基线：Spring Boot **4.1.1** / JDK **25 LTS** / Thymeleaf / MyBatis Starter **4.1.0** /
Bootstrap **5.3.8** / MySQL **8.4.11**。Bootstrap 随应用提供，运行时无需访问 CDN。
固定引擎版本为 Doris **4.1.3**、DolphinScheduler **3.4.0**，DataX 固定源码提交见运行说明。
Java 根包为 `com.litedata`，Maven 坐标为 `com.litedata:litedata`。

## 启动

准备 Docker（Windows 使用 Linux 容器）和 Docker Compose v2；Windows 脚本需要 PowerShell 7，
Linux/macOS 配置生成脚本需要 OpenSSL。全栈启动会在容器中构建应用，本地开发和测试另需 JDK 25。
首次启动需要下载并构建多个引擎镜像，请为 Docker 预留相应内存和磁盘空间。

```sh
git clone https://github.com/tan-wen/litedata.git
cd litedata
```

Windows（PowerShell 7）：

```powershell
.\deploy\start.ps1
```

Linux/macOS：

```sh
sh deploy/start.sh
```

脚本自动生成本地 `.env`，启动两个 MySQL、Doris 和包含 DataX 的调度节点，初始化独立调度库、
仓库表和读写账号，创建 DS 项目及令牌，最后构建并启动平台。
已有 `.env` 会保留。首次构建需要访问 Docker Hub、GitHub 和 Maven Central。

打开 <http://localhost:8080>，用户名 `admin`，初始密码在本地 `.env` 的
`PLATFORM_ADMIN_PASSWORD` 中。该密码只在账号首次创建时使用；修改环境变量不会覆盖已有账号密码。
所有开发端口绑定本机，凭据文件已被 Git 和 Docker 构建上下文忽略。
端口可在 `.env` 调整。`docker compose ps` 查看状态，`docker compose down` 停止服务并保留数据卷。
已有环境迁移时请先阅读 [目录与部署名称迁移](docs/engine-runbook.md#目录与部署名称迁移)。

## 跑通演示

1. 在“数据源”登记固定演示库并测试连接：

   - 名称：演示源
   - URL：`jdbc:mysql://demo-mysql:3306/demo_source?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`
   - 用户：`demo_reader`，密码：`demo_reader_password`

2. 在“同步管道”创建任务：

   - 来源表：`orders`，水位字段：`updated_at`
   - 目标表：`ods_orders`
   - 调度：`0 0 * * * ?`
   - 加工 SQL：

   ```sql
   INSERT INTO ads_orders
   SELECT id, customer_name, amount, updated_at FROM ods_orders
   ```

3. 点击“立即运行”，等待真实实例成功，再启用每小时调度。首批会读取从 1970-01-01 起的全部数据。
4. 在“数据服务”创建 `orders`，查询：

   ```sql
   SELECT id, customer_name, amount, updated_at
   FROM ads_orders WHERE id = :id
   ```

   发布服务并生成一次性显示的凭证；每次重新生成会吊销旧凭证。

5. 调用：

   ```sh
   curl -H 'X-API-Key: <生成的凭证>' 'http://localhost:8080/api/data/orders?id=1'
   ```

服务固定使用只读仓库账号，命名参数由 MyBatis 绑定。每服务凭证每分钟最多 60 次调用，
总查询并发上限 8，查询超时 15 秒，最大结果行数可配置为 1–10000。
返回 `data`、`count` 和 `truncated`。

## 本地开发和验证

使用 JDK 25 和仓库自带的 Maven Wrapper（Maven 3.9.11），无需安装全局 Maven。
自动化测试由 Testcontainers 启动真实 MySQL，需要 Docker；不需要先配置 `.env` 或启动完整引擎。

```powershell
.\mvnw.cmd verify
```

```sh
./mvnw verify
```

测试验证数据库迁移、页面、鉴权、凭据、SQL 策略和水位状态；不验证真实 DataX、Doris 和 DS 调度链路。

```sh
docker compose --env-file .env.example config --quiet
```

IDE 启动入口为 `com.litedata.PlatformApplication`。先启动依赖，再将本地 `.env` 配置导入 IDE 环境。
平台在主机运行时，`PLATFORM_DB_URL`、`DORIS_JDBC_URL` 和 `DOLPHINSCHEDULER_URL` 须改为对应的本机映射地址。
平台在容器内运行时使用 Compose 服务名。源库地址必须同时能被平台和执行 worker 访问；
单独使用 `localhost:3307` 只适用于主机连接测试，容器 worker 的 `localhost` 指向自身。
涉及执行回调的本机调试还需单独配置 worker 可达的 `PLATFORM_URL`；完整链路优先使用 Compose 运行平台。

完整引擎验收步骤和已知验证状态见 [引擎运行说明](docs/engine-runbook.md) 和
[实施及验收计划](docs/implementation-plan.md)。

## 仓库结构

```text
src/main/java/com/litedata/   启动入口、config、data、model、pipeline、security、service、store、web
src/main/resources/          应用配置、Flyway 迁移、Thymeleaf 模板、Bootstrap 与样式
src/test/java/com/litedata/   单元测试和真实 MySQL 集成测试
deploy/                     环境生成、引擎初始化、worker、演示数据、冒烟及容量验收脚本
docs/                       实施范围和引擎操作说明
.github/                    CI 和贡献模板
compose.yaml / Dockerfile   单机开发部署与应用构建
AGENTS.md                   项目上下文和代理协作约定
```

## 数据语义

- 源表需有非空、带前导索引的 DATETIME/TIMESTAMP 水位字段；新增和更新必须更新该字段。
- 每批按源库时间获取上界，回读最近成功水位之前 5 分钟；同步和加工都成功才推进水位。
- ODS 与加工目标表均须使用幂等主键写入。示例 Doris 表使用 UNIQUE KEY。
- 时间水位覆盖有限迟到。硬删除和超出重放窗口的历史变更需要单独对账及补数。
- 意外中断导致实例仍为 RUNNING 时，应先在 DS 确认任务及子进程停止，再按运行说明恢复。
- 百万行每小时是验收目标，需完成真实同步、加工及对账压测后才能确认。
- 限流和查询并发控制在单个应用进程内生效；当前部署按单实例设计。

## 开源

[Apache License 2.0](LICENSE)。贡献与验证方式见 [CONTRIBUTING](CONTRIBUTING.md)，
安全问题见 [SECURITY](SECURITY.md)，版本变更见 [CHANGELOG](CHANGELOG.md)，
项目上下文和代理协作入口见 [AGENTS](AGENTS.md)，第三方说明见 [NOTICE](NOTICE)。
非敏感缺陷和功能建议请提交到 [Issues](https://github.com/tan-wen/litedata/issues)。

本仓库从原 `bigdata` 工作目录拆出开源工程，早期原型、设计原稿、Excel 计划和功能图保留在原目录，
不随此仓库发布。当前实现范围以源码和 `docs/` 为准；本地 `.env`、运行凭据、日志和构建产物不入库。

