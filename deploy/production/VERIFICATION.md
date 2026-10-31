# 生产包验证说明

## 历史验证范围

2026-10-26 曾在 Linux x86_64 容器中完成缩小配额的小数据验证：

- JDK 25 / Maven、Python runner、Compose 解析及 MySQL 配置检查。
- 独立平台库/调度库、Doris FE/BE、ZooKeeper、DS 各进程和 JAR 启动；引擎初始化重复执行不重建元数据。
- MySQL → DataX → Doris ODS → SQL 加工 → API，及 API 凭证轮换。
- FE/BE/Worker 重启后的健康与数据保留，元数据及密钥备份文件生成。
- 凭据覆盖保护、制品校验/篡改检测、ZIP Linux 权限及交付内容排除规则。

该环境 Docker 可用内存约 16GiB，使用独立端口/子网和缩小配额，并关闭测试节点的调度负载保护；交付配置按 32 核 / 128GB 保留默认负载保护。
这是历史验证范围，不是后续版本的验收结果，不证明 0.5–2TB 容量、百万行吞吐、四任务并发、完整备份恢复或连续七天稳定性。

## 每次交付

1. 在仓库执行 `py -3 deploy/production/package.py`（Linux 用 `python3`）；默认执行 Maven verify，生成包的 `BUILD-INFO.json` 记录源码、工作区状态及是否跳过构建。
2. 按 [部署说明](README.md#首次部署) 校验制品并在隔离环境初始化、启动；开发者可用仓库 `deploy/production/validate_release.py --help` 查看集成检查参数，对生成包做缩小配额验证。
3. 按 [容量验收](README.md#验证监控与容量验收) 和 [备份恢复](README.md#启停备份和恢复) 在目标规格实测。记录环境、数据量、命令与结果，再开放调度。

配置解析、健康检查和自动化测试不能代替真实业务链路与容量验收。
