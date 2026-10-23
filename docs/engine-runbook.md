# 引擎运行说明

默认固定部署平台、MySQL 平台库、独立 MySQL 演示源、Doris 单 FE/BE，以及带 DataX 的
DolphinScheduler standalone。调度元数据保存在 MySQL 的独立 `dolphinscheduler` 库，
账号为 `scheduler`；平台元数据在 `platform` 库。两者使用不同凭据。

## 固定版本与来源

| 组件 | 版本/来源 |
| --- | --- |
| 平台 Java | JDK 25 LTS，Maven Wrapper 3.9.11 |
| MySQL | 官方 `mysql:8.4.11` |
| Doris | 官方 `apache/doris:all-in-one-4.1.3` |
| DolphinScheduler | 官方 `apache/dolphinscheduler-standalone-server:3.4.0`，独立运行时 |
| DataX | Alibaba DataX tag datax_v202309，commit `9a1f88751e24314b083a74f1b83ef56d69ce98bd` |
| MySQL 驱动 | Connector/J 8.4.0，Maven Central SHA-256固定校验 |

Doris 使用镜像内置的集群健康检查，数据持久化至 FE/BE 的命名卷。
DS 内存 H2 已替换成 MySQL profile，并关闭启动时的 SQL 自动初始化。
DS 3.4.0 的官方 SQL 仅在目标库没有任何表时导入；已有库只检查版本表。
完整 schema 已在真实 MySQL 8.4.11 中连续执行初始化两次验证，64 张表、版本 3.4.0 保留。

## 启动和升级

`deploy/start.ps1` / `deploy/start.sh` 完成：
1. 生成并保留随机环境密钥；
2. 启动 MySQL，初始化独立 DS 库及账号；
3. 构建/启动 Doris、DS + DataX；
4. 初始化 warehouse、ODS/ADS 主键表、只读和写账号，创建 DS 项目/token；
5. 回写本地环境并构建/启动平台。

这是开发环境的一键启动入口。首次 bootstrap 使用 DS 默认本地管理员；
它的账号配置由 `.env` 管理。公开部署需独立处理引擎管理员账号和网络边界。

初始化脚本不承担跨版本数据库升级。切换 DS 版本须先备份并使用对应官方升级脚本；
发现非空但不完整的 schema 时应停止检查，不能直接重新导入含 DROP 的全量初始化 SQL。

## 目录与部署名称迁移

开源工程现位于独立的 `litedata` 目录，Java 根包为 `com.litedata`，
Maven 坐标为 `com.litedata:litedata`，应用产物为 `target/litedata-0.1.0-SNAPSHOT.jar`。
本地 `.env` 和 `deploy/runtime/` 随工作目录迁移保留，并继续由 Git 忽略。
历史原型、设计原稿和 Excel 计划保留在原 `bigdata` 目录。

Compose 默认项目名由 `data-platform` 调整为 `litedata`。命名卷和网络也会使用新的项目前缀，
已有 `data-platform_*` 数据卷不会自动附加到新项目。已有环境必须先备份数据库和 Doris 数据，
确认原容器状态及挂载路径，再决定沿用旧卷或建立新环境；不要直接删除旧卷。
需要临时沿用旧部署身份时，可在新目录用 `docker compose -p data-platform ...` 显式操作旧项目，
并检查迁移后的 bind mount 路径。内置启动脚本使用新的默认项目名，已有部署完成卷迁移前不要直接运行。

## 执行与水位

DS 的 SHELL 任务只有 `python3 /opt/platform/bin/run_pipeline.py --pipeline-id <id>`。
worker 启动时把内部执行凭证写入 root-only 的 `/run/litedata-worker.json`，
使 DS 经 `sudo -i` 切换执行身份时仍可读取；源库凭据不会写入工作流定义。

runner claim 取得不可变上下界，409 时不执行。DataX 读取 `[lowerBound, upperBound)`，
最多 45 分钟，成功后执行单条 warehouse 内的 INSERT SELECT。
两步都成功才回调推进水位；失败或超时回调失败，回调会重试。
重复完成请求幂等。超出五分钟的迟到和硬删除不属于时间水位可完整捕获的范围。

DataX 使用独立 Java 8；DS 保留镜像运行时；平台使用 Java 25。
mysqlreader 的 5.1 旧驱动由 Connector/J 8.4 替换以支持 MySQL 8 默认认证。
完整 DataX 输出仅暂存在 0600 文件；失败输出先替换凭据再截取尾部摘要，任务结束清理临时文件。

## 故障排查和恢复

`docker compose ps` 查看健康，`docker compose logs --tail 100 dolphinscheduler` 查看引擎启动错误。
DS 控制台在 <http://localhost:12345/dolphinscheduler>，运行日志由 DS 保留。
平台实例记录是 worker 实际回调结果，不把提交请求当作成功执行。

若执行节点被强制停止，平台会保留 RUNNING 防止并发重放。先确认 DS 实例与 DataX 子进程已停止，
备份元数据库，再在事务中锁定对应 pipeline，将其 active_run_id 对应实例置 FAILED，
清空 active_run_id 并保留 watermark。随后由平台重新提交。
首版尚未提供经过 DS 状态核验的自动恢复按钮。

## 验证状态

- 平台测试包含 4 项单元测试、6 项真实 MySQL 集成测试；验证命令为 `./mvnw verify`。
- 目录和包名迁移后的检查结果记录在 [CHANGELOG](../CHANGELOG.md)。
- 迁移前曾完成平台 HTTP 登录、工作台、健康接口与 DS MySQL schema 非破坏性重复初始化验证；
  这些检查不等同于完整引擎验收。
- 完整引擎镜像构建、源到 Doris、DS 实际调度/重试、容器重启后的完整恢复及百万行压测仍待联调。
  以上项目需要独立运行并记录结果，不能依据平台单元/集成测试推断通过。

## 官方依据

- [DS 3.4.0 standalone 配置](https://github.com/apache/dolphinscheduler/blob/3.4.0/dolphinscheduler-standalone-server/src/main/resources/application.yaml)
- [DS 3.4.0 MySQL schema](https://github.com/apache/dolphinscheduler/blob/3.4.0/dolphinscheduler-dao/src/main/resources/sql/dolphinscheduler_mysql.sql)
- [Doris all-in-one 说明及持久化路径](https://doris.apache.org/community/developer-guide/all-in-one-image/)
- [DataX 固定源码](https://github.com/alibaba/DataX/tree/9a1f88751e24314b083a74f1b83ef56d69ce98bd)

