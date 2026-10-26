# LiteData 第一版生产部署包（单机中等规模）

适用：**Linux x86_64、32 核 / 128GB 内存、SSD**，Docker Engine 与 Compose v2.20+、Python 3.10+。
容量规划按约 **0.5–2TB 的 Doris 有效数据**起步；这只是规划假设，不是实测吞吐承诺。
建议数据盘至少 4TB，业务增长或高更新率场景使用 8TB，持续保留 30%–40% 空闲用于 Compaction、临时结果与增长；
备份使用另一块磁盘并复制到其他主机/对象存储。数据盘采用本地 SSD 的 ext4/XFS，不把数据库目录放在 NFS 上。

本包直接携带 `app/litedata.jar`（Spring Boot 可执行 JAR，包含静态页面与 Flyway 迁移），目标服务器无需 Java/Maven/平台源码。
`artifacts/engine-runtime.tar.gz` 包含提前构建的 DataX、JDBC 驱动和 SHELL 插件；首次安装只组装调度运行时镜像，不编译 Java。
生产目录独立于仓库根的演示 Compose，无演示库、演示数据和演示任务。不要与旧环境共享数据目录或 Docker 子网。

## 拓扑与资源

| 服务 | 数量 | CPU 上限 | 容器内存上限 | 主要参数 |
| --- | --- | --- | --- | --- |
| LiteData JAR | 1 | 4 | 4GiB | JVM 1–2GiB，连接池 20，单实例运行 |
| 平台 MySQL 8.4.11 | 1 | 2 | 6GiB | Buffer Pool 3GiB，binlog 7 天、提交刷盘 |
| 调度 MySQL 8.4.11 | 1 | 2 | 6GiB | 独立数据库进程、独立账号及密码，同上 |
| Doris 4.1.3 FE | 1 | 4 | 12GiB | JVM 8GiB，连接上限 512，默认查询 300 秒 |
| Doris 4.1.3 BE | 1 | 18 | 72GiB | 进程内存 60GiB；基础/累积 Compaction 每盘 2/4 线程 |
| DolphinScheduler 3.4.0 API | 1 | 2 | 3GiB | JVM 1–2GiB，连接池 20 |
| Master | 1 | 2 | 4GiB | JVM 1–2GiB，最多 20 个工作流实例 |
| Worker + DataX | 1 | 6 | 12GiB | JVM 1–2GiB，**最多 4 个物理任务并发**，每个 DataX 配置 2 通道 |
| Alert | 1 | 1 | 2GiB | JVM 0.5–1GiB；需另配告警接收端 |
| ZooKeeper | 1 | 1 | 2GiB | 使用 DS 发行包携带的 3.8.0，JVM 0.5–1GiB，快照自动清理 |

内存上限合计 **123GiB**；实际常驻低于上限，BE 60GiB 限制与各 JVM 堆之间给宿主机留出缓存/临时开销。
CPU 配额合计高于 32，允许阶段性共享；初始并发保持保守，通过实测监控调整。DataX 的通道数不是源端 SQL 并行数保证：
当前 runner 使用时间区间 querySql，源库扫描与索引可能先成为瓶颈。避免多条大表全量初始化同时执行。

这是 **1FE + 1BE、单副本、单 Master/Worker/Registry** 的单机部署。与平台当前单实例状态锁、单副本建表行为一致，
适用于接受维护窗口及备份恢复的生产环境。宿主机或数据盘故障会整体停机；容器重启策略不等于高可用。
若要求主机级高可用，应迁移到跨主机的 3FE/至少 3BE、三副本、多个调度节点与独立高可用元数据库，不能在同机多开容器替代。

Doris 运行时复用官方 `apache/doris:all-in-one-4.1.3` 内的固定版本二进制，但 **不运行镜像默认的 CI/all-in-one 入口**：
分别启动 FE 与 BE，清除镜像的集成测试附加配置，保留上游 JVM 模块参数，再应用本包的容量配置。
FE/BE 各自持久化、按固定 IP 通信，BE 注册由带认证的初始化脚本完成；Worker 直接访问 BE 的 Stream Load，避免错误的重定向地址。
DS 同样复用官方发行包，API/Master/Worker/Alert 是独立 JVM，并非 standalone 模式。

## 首次部署

以下命令使用有权限访问 Docker 的管理员账号执行。部署目录建议 `/opt/litedata`，仅管理员可读 `.env`、`runtime`、`backups`。
第一次需要访问镜像仓库和 Debian 软件仓库（组装 Python 运行时），下载 MySQL、Doris、Temurin 和 DS 基础镜像。
未生成 `.env` 前 `docker compose config` 会主动失败，以防空密码启动。

```bash
cd /opt/litedata
python3 bin/manage.py verify
python3 bin/configure.py
sudo sysctl -w vm.max_map_count=2000000
echo 'vm.max_map_count=2000000' | sudo tee /etc/sysctl.d/90-litedata.conf
```

启动前编辑 `.env`：

- `DATA_DIR=/data/litedata`：SSD 数据目录，建议使用绝对路径，无空格；创建父目录并确认可写、磁盘容量和挂载点。
- `COMPOSE_PROJECT_NAME=litedata-prod`：持久保留，不要在升级时随意更名。
- 默认 Docker 子网 `172.29.80.0/24`；若与企业网络/VPN冲突，同时修改 `DOCKER_SUBNET`、`DORIS_FE_IP`、`DORIS_BE_IP`。
  **有数据后不要随意改 Doris IP**，其网络身份保存在元数据内，迁移前需按 Doris 节点迁移流程处理。
- 默认平台只绑定 `127.0.0.1:8080`；DS 管理端口只绑定 `127.0.0.1:12345`，数据库与引擎端口不向主机发布。
  可使用 SSH 隧道管理 DS：`ssh -L 12345:127.0.0.1:12345 user@server`，访问 `/dolphinscheduler/ui`。
- `.env` 中各项密码自动独立随机生成；`PLATFORM_ENCRYPTION_KEY` 是 32 字节 Base64 AES 密钥，**必须与元数据库共同离机备份，升级时保留**。
  不要打印 `docker compose config` 的完整结果、把 `.env` 入库或发到群里。

```bash
python3 bin/manage.py preflight
python3 bin/manage.py install
python3 bin/manage.py status
```

`install` 验证校验和、主机规格及环境配置，创建基础服务，等待健康检查，注册 BE，创建 Doris 读写账号与空 `warehouse`，
创建 DS `platform` 项目及有效期 365 天的令牌，安全写入 `.env`，最后启动平台执行 Flyway 迁移。
初始化可重试；已有 DS SQL 不会被重新导入，已有令牌会先校验，不会每次重启生成新令牌。
MySQL 初始化会导入固定 3.4.0 schema，修复 serial workflow code 的 BIGINT 兼容问题，并在 API 启动前替换默认管理员密码。
Doris 的 root 和内置 admin 均设置密码，平台只读账号和建模/导入写账号分离。`.env` 只保存平台运行需要的 DS 令牌。

访问 HTTPS 平台后，以 `admin` 和 `.env` 中 `PLATFORM_ADMIN_PASSWORD` 登录。
该密码仅用于首次创建平台管理员；之后在账号页面变更，修改环境变量不会重置数据库已有密码。
DS 管理员为 `admin`，初始密码见 `DOLPHINSCHEDULER_ADMIN_PASSWORD`；同样只在首次建库设置。
初始化脚本需要该管理员凭据，后续更改密码也应同步保管记录。DS 令牌过期前必须轮换并重建平台容器。

## HTTPS 入口

平台默认 `Secure` Session Cookie；生产需由已有反向代理/负载均衡提供 TLS，转发到主机 `127.0.0.1:8080`。
示例（宿主机 Nginx 的 HTTPS server 中，证书与域名由运维提供）：

```nginx
location /internal/ { return 403; }
location /actuator/ { return 403; }
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto https;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 310s;
}
```

若反向代理在其他主机上，将 `PLATFORM_BIND` 改为服务器的内网地址，并由防火墙只允许该代理访问 8080。
不要让任意外部来源访问后端以伪造转发头。DS/Shell 任务视为有权访问数据的代码，只有可信管理员可修改调度定义。
Worker 默认租户在其容器内以 root 执行以兼容现有凭据加载机制；不挂载 Docker socket，不给 privileged，不接入不可信任务。

## 验证、监控与容量验收

`python3 bin/manage.py status` 应显示所有常驻服务 healthy；然后在界面创建源库连接、目标模型、同步任务、加工 DAG 和只读 API，
执行小批次同步 → 分层加工 → API 查询，并核对源/ODS/ADS 行数及金额汇总，确认水位只在成功后推进。首次接入不能直接放开全部定时任务。
源库地址必须同时被平台和 Worker 访问，不能使用容器内的 localhost 代表业务数据库。

按生产数据分布验证百万行增量、复杂 JOIN、四任务并发，以及连续七天运行。关注：BE 内存/Compaction 积压/磁盘空闲、
FE 堆和慢查询、Worker 队列及子 JVM、MySQL Buffer Pool 与慢 SQL、DS 失败/重试和令牌到期。
DS 的 Alert 容器可运行不代表告警已送达，需配置邮箱/Webhook 并实际触发一次失败告警。
建议磁盘使用率 70% 预警、80% 告警；BE 内存或 Compaction 持续紧张时先错峰和降并发，再考虑扩容。

配置可在 `config/` 调整，修改后 `docker compose up -d --force-recreate 对应服务`。配额、堆和 BE `mem_limit` 必须一起核算。
只有 `config/` 从不可变文件校验中排除，便于按现场资源调优；JAR、运行时二进制、部署脚本与 Compose 清单均由 SHA256SUMS 校验。
Docker 标准输出轮转 50MiB×5；Doris 文件日志已配置轮转。DS 模块/任务日志持久化到 DATA_DIR/scheduler/logs，使用发行包自带轮转，并需接入离机日志采集与现场保留策略，
不要把原始 DataX 日志作为公开排错附件（可能含源库凭据）。MySQL 慢日志/平台审计表也需定期归档，binlog 7 天不等于已完成备份。

## 启停、备份和恢复

```bash
python3 bin/manage.py stop
python3 bin/manage.py start
python3 bin/manage.py backup-metadata
```

元数据备份包含两份 MySQL 一致性逻辑备份、`.env` 和 DS 项目令牌，**不包含 Doris 数据**。
两库的备份不是跨库原子快照。建议在维护窗口先停调度、确认没有运行中任务，停止平台写入，再执行备份；
保留最近 7 天每日、4 周每周、3 个月每月备份并加密离机保存，至少每月做一次隔离恢复演练。

第一版单机完整恢复可采用维护窗口的**全栈冷备份**：

1. 在平台/DS 下线定时任务，等待所有实例和 DataX 子进程结束，记录最后成功水位。
2. 执行 `python3 bin/manage.py stop`，确认 Compose 所有服务 stopped，防止复制活跃 FE/BE/MySQL 目录。
3. 使用管理员权限将整个 `DATA_DIR`、部署目录的 `.env`、`runtime/`、`config/`、当前部署 ZIP 一起归档到独立备份盘。
   例如 `tar --numeric-owner -czpf /backup/litedata-data-YYYYMMDD.tgz -C /data litedata`（示例 DATA_DIR 为 `/data/litedata`）。
   另对部署目录的 `.env runtime config BUILD-INFO.json` 执行同样的私有归档；备份文件权限设为 600。
4. 备份成功或失败均应按维护计划执行 `python3 bin/manage.py start` 恢复服务；验证后再恢复定时任务。

恢复时先在隔离环境解压同版本部署包，恢复原 `.env`、runtime/config，停止全部服务后把冷备数据恢复到原 DATA_DIR 并保留数字属主；
恢复原 Docker 子网与 FE/BE IP，再执行 `start`，不要对非空生产目录覆盖解压，也不要重新运行凭据生成器。
MySQL 逻辑恢复可在停平台和全部 DS 进程后，分别用 `docker compose exec -T platform-db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot platform' < platform.sql`
及 scheduler-db/dolphinscheduler 同样命令导入；它不能代替 Doris 的数据恢复。完整冷备恢复后验证源连接解密、任务/水位、Doris 数据与 API 查询。
恢复到旧水位的任务可能重放数据，依赖 UNIQUE KEY 幂等；硬删除和窗口外迟到数据仍需对账补数。
更低 RPO 的在线 Doris 备份应按官方 BACKUP/RESTORE 接入远程仓库，本包未替用户配置外部对象存储凭据。

不要使用 `docker compose down -v`、清空 DATA_DIR 或在升级时初始化新密钥；普通重启使用 `start/stop`。

## JAR 升级与回退

维护窗口先停定时任务、等在途任务结束，完成两库备份与完整数据备份后，停止平台。
保留当前部署包，将新版本 JAR 及对应 SHA256SUMS 作为一组发布，保持 `.env`、runtime 和 DATA_DIR，
仅重建平台容器（`docker compose up -d --no-deps --force-recreate platform`），检查健康、Flyway 和登录/API 后恢复调度。
不要用新包目录的空数据路径误启动一套环境。数据库迁移只向前执行；旧 JAR 不保证兼容新 schema，
回退需要验证过的 schema 兼容性，或在完整恢复方案下恢复匹配的元数据库/数据/密钥；禁止盲目降级和改 Flyway 历史。

## 离线交付和重新打包

默认 ZIP 为联网安装包。打包机（不是生产服务器）在仓库运行：

```powershell
$env:JAVA_HOME='C:\Java\jdk-25.0.3'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
python deploy/production/package.py
# 完全离线版：联网打包机准备所有镜像，包体会明显增大
python deploy/production/package.py --with-images
```

离线版先 `docker load -i images/runtime-images.tar`，然后生成配置并检查，使用 `python3 bin/manage.py install-offline` 跳过现场镜像构建。
镜像固定版本标签；`BUILD-INFO.json` 记录源码版本、工作区状态、构建时间和 DataX donor 的镜像 ID。
平台当前制品版本仍为 `0.1.0-SNAPSHOT`，本包不擅自修改项目版本；生产发布流程应给制品做独立编号和留档。

## 配置参考与验证边界

容量参数是按上述单机预算给出的初始配置，参数名对照固定版本源码：
[Doris 4.1.3 FE 配置](https://github.com/apache/doris/blob/4.1.3/fe/fe-common/src/main/java/org/apache/doris/common/Config.java)、
[BE 配置](https://github.com/apache/doris/blob/4.1.3/be/src/common/config.cpp)、
[DS 3.4.0 Worker 配置](https://github.com/apache/dolphinscheduler/blob/3.4.0/dolphinscheduler-worker/src/main/resources/application.yaml)、
[Master 配置](https://github.com/apache/dolphinscheduler/blob/3.4.0/dolphinscheduler-master/src/main/resources/application.yaml)、
[官方分进程 Compose](https://github.com/apache/dolphinscheduler/blob/3.4.0/deploy/docker/docker-compose.yml)。
本次实际执行的构建与链路检查见 [验证记录](VERIFICATION.md)。开发者可用仓库的 `deploy/production/validate_release.py` 对独立的生成包做缩小配额的集成检查。
校验和/Compose 解析/健康检查与项目自动化测试均不能代表真实业务全链路和中等数据容量验收，现场按上文步骤验证后再开放生产调度。
