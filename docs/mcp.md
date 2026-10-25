# 用户 API Key 与 MCP

## 方案依据

- [MCP HTTP 授权规范（2025-11-25）](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)推荐 OAuth 2.1，凭证在传输层验证，每个 HTTP 请求携带 Authorization；不把会话 ID 当作身份。
- [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)允许 JSON 响应及无状态服务。此版本采用单端点 POST、JSON 响应，不提供 SSE 或服务端会话。
- [Supabase PAT](https://supabase.com/docs/guides/platform/personal-access-tokens)按工具权限和资源缩小用户访问范围；[GitHub PAT](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)同样要求 token 权限不超过所有者权限，并推荐有效期。

## 本次实现决策

1. 新增专用于 MCP 的用户 API Key，与现有数据服务 Key、网页 Session、内部 worker token 分离。支持多个命名 Key、1–365 天有效期（默认 90 天）、独立吊销。32 字节随机值以 `ld_mcp_` 开头，明文只在创建响应显示一次；数据库仅存 SHA-256 和不可用于认证的短前缀。
2. 所有 HTTP 请求从 Bearer Key 查回所有者，验证用户启用状态、内置角色、Key 有效期及吊销状态。不接受 query 参数或工具参数中的身份与 Key，不信任 Agent 传来的 username/role。权限变更在下一次请求生效。
3. 权限为「当前用户角色 ∩ Key scopes ∩ 资源白名单」。所有内置角色可使用 `services:read`、`services:query`；只有 ADMIN/DEVELOPER 可使用 `pipelines:run`。服务与管道必须逐个授权，不提供默认全资源或未来资源自动授权。VIEWER 无法签发执行管道的 Key。
4. `/mcp` 实现 initialize、notifications/initialized、ping、tools/list、tools/call。工具为 `get_current_user`、`list_data_services`、`query_data_service`、`run_pipeline`。工具发现按权限过滤；每次调用重新验证权限与资源，隐藏工具不代替后端校验。
5. 数据查询只允许已发布服务，复用现有 QueryPolicy、绑定参数、固定分页、每分钟 60 次及并发上限 8，不开放任意 SQL、凭据或平台管理操作。管道执行复用现有调度提交逻辑；接受提交不表示运行完成。
6. 新增“我的 API Key”分页列表及独立创建、结果页面，使用 Session + CSRF，仅管理本人的 Key。创建结果禁止缓存，不将明文放入 URL、Flash Session 或日志。轮换通过创建替代 Key、更新客户端配置、吊销旧 Key 完成。
7. 审计记录用户、Key ID、工具、资源和结果，不记录 Key、SQL、工具参数或数据行。认证失败返回 401，权限不足返回 403；工具参数/业务错误使用 MCP 错误响应，未知错误仅返回通用信息。

此版本是支持自定义 HTTP Authorization 的 Agent 使用的 PAT 接入模式，不实现 OAuth discovery、浏览器授权、动态客户端注册，也不宣称完整实现 MCP OAuth 授权规范。仅支持 OAuth 自动登录的客户端需要后续 OAuth 2.1 集成。远程部署必须使用 HTTPS；反向代理须保留 Authorization，并限制请求体大小和入口速率。

## 使用流程

1. 登录 LiteData，在“我的 API Key”进入创建页。为不同 Agent 单独创建 Key，选择有效期、工具权限、已发布服务标识及（需要执行时）管道 ID。无通配符；新资源不会自动加入。
2. 保存一次显示的 Key 到客户端密钥管理或受保护的本地配置中。离开结果页后不能恢复明文。列表仅显示前缀、授权范围、有效期和最近使用时间（UTC）。
3. 配置支持 Streamable HTTP 的客户端，例如下面的通用 MCP 配置格式。不同客户端的环境变量替换方式不同，请由客户端安全注入 `LITEDATA_MCP_API_KEY`；不要把占位符当作可直接认证的 Key。

```json
{
  "mcpServers": {
    "litedata": {
      "type": "http",
      "url": "https://<LiteData 主机>/mcp",
      "headers": { "Authorization": "Bearer <从客户端密钥存储注入>" }
    }
  }
}
```

4. 客户端先发送 `initialize`、`notifications/initialized`，再请求 `tools/list`。支持协议版本 `2025-11-25`、`2025-06-18`、`2025-03-26`；后续请求发送协商的 `MCP-Protocol-Version`。服务无会话，不返回 `Mcp-Session-Id`；每次 POST 都必须携带 Bearer。GET/SSE、旧 HTTP+SSE 和 STDIO 不在本版本支持范围。
5. 先调用 `get_current_user` 检查身份，再调用 `list_data_services` 发现已授权服务及其业务参数。`query_data_service` 只执行已发布的固定查询；业务参数以及 `pageNum` / `pageSize` 以字符串放入 `params`，例如：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "query_data_service",
    "arguments": {
      "slug": "orders",
      "params": { "id": "1", "pageNum": "1", "pageSize": "20" }
    }
  }
}
```

| 工具 | Key scope | 角色与资源约束 |
| --- | --- | --- |
| `get_current_user` | 任意有效用户 Key | 仅返回本人身份及当前有效 scopes，不返回密码或 Key |
| `list_data_services` | `services:read` | 所有内置角色；只返回白名单内已发布服务，数据库分页，每页最多100 |
| `query_data_service` | `services:query` | 所有内置角色；服务必须在白名单中且已发布，查询分页最多1000 |
| `run_pipeline` | `pipelines:run` | 当前角色为 ADMIN/DEVELOPER，且 `pipelineId` 在 Key 白名单内 |

`tools/list` 仅返回当前角色和 scopes 允许的工具。即使 Agent 缓存了旧工具，直接调用仍会重新鉴权。管道执行会产生写入，只在明确需要自动运行时授予此 scope，并在 Agent 侧保留执行确认。

## 运行与审计

- 首次启动自动执行 Flyway `V10__user_api_keys.sql`，只新增用户 Key 及资源授权表；现有 `/api/data/{slug}` 的服务级 Key 行为保留。
- `/mcp` 请求体最多64 KiB，不接收 JSON-RPC batch。无效/缺失凭证返回401，越权返回403，非法 JSON 返回400，通知返回202；工具业务失败返回 `result.isError=true`，协议和参数失败返回 JSON-RPC error。
- `audit_event` 的 `MCP_AUTH` 记录认证拒绝，`MCP_TOOL_CALL` 记录用户、Key ID、工具、资源和 SUCCESS/DENIED/FAILED；`SERVICE_QUERY` 与 `service_call` 保留查询耗时、分页、行数和状态。均不存工具参数、Key 明文或结果数据。
- 反向代理必须限制入口请求率，且禁止在访问日志采集 Authorization。应用查询限流为每 Key 每分钟60次，跨该 Key 的所有服务共享；查询并发上限8沿用数据 API。此内存限流面向单机，不是多实例配额。
- 含 Origin 的请求仅允许与服务请求同源；未带 Origin 的原生 Agent 可使用。HTTPS 终止在可信代理时，须由部署方正确配置转发头并限制可设置转发头的上游，避免应用误判 Origin。
- 吊销、账号停用、降级在后续请求生效；已开始的数据库查询或已提交的调度实例不会被追溯取消。权限需要调整时重新签发 Key。Key 泄露时先吊销再轮换。

后续如需第三方交互式授权，再增加 OAuth 2.1 / PKCE、Protected Resource Metadata、短期访问 token 与刷新轮换；不在本次 PAT 功能中伪装提供 OAuth 发现端点。

## 开发验证

使用仓库要求的 JDK 25 和 Docker，运行 `./mvnw verify`（Windows 使用 `.\mvnw.cmd verify`）。聚焦 MCP、用户 Key 与原有 REST 兼容性可运行：

```powershell
.\mvnw.cmd verify '-Dtest=McpIntegrationTest,McpProtocolTest,PlatformIntegrationTest'
docker compose --env-file .env.example config --quiet
```

MCP 集成测试使用真实 MySQL 验证迁移、页面、凭证隔离、scope/资源越权、用户/角色/Key 状态、Origin、分页和审计；管道提交使用调度客户端 mock。协议测试覆盖错误 JSON、体积限制、batch、非法 ID、通知不能执行工具以及版本协商。它们不替代真实 DataX→Doris→DolphinScheduler 全链路验收。
