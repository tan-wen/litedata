# 界面与主题

界面使用本地固定版本的 Bootstrap `6.0.0-alpha.1`，后台布局参考 [Helm](https://uicookies.com/downloads/helm/)：固定侧栏、可收起的图标栏、顶部工具栏及移动端原生导航抽屉。布局与业务模板使用 Thymeleaf，页面拆分和数据库分页约定继续生效。

## 外观选择

- 外观模式：浅色、深色、跟随系统；默认跟随系统，系统偏好变化时自动更新。
- 主题配色：海蓝、鸢紫、青绿、石墨；配色与外观模式可以任意组合。
- 登录页及所有管理页面共享外观控件；表格、表单、提示、分页、SQL 高亮与页面外壳一同切换。
- 浏览器本地保存选择，刷新、跨页和退出登录后保留；同源标签页同步更新。这是浏览器偏好，不是用户账号在不同设备之间的设置。
- 本地存储不可用时仍可在当前页面切换；刷新后恢复默认值。桌面侧栏展开状态也保存在浏览器中。

## 开发入口

- `templates/fragments/layout.html`：后台公共外壳、桌面和移动导航；`fragments/appearance.html`：外观控件；`login.html`：独立登录页。
- `static/js/theme.js`：在 CSS 加载前设置根元素的 `data-bs-theme`、`data-accent`，并处理持久化、系统偏好变化和侧栏交互。
- `static/css/app.css`：外壳与语义色值。使用 `light-dark()`、`--platform-*` 和 Bootstrap `--bs-*` 变量，业务页面不要硬编码白底、黑字或浅色 SQL 高亮。
- Bootstrap 6 使用 `btn-solid theme-primary`、`btn-outline theme-secondary` 等组合；响应式类为 `md:col-6`，选择框使用 `form-control`，复选框使用 `check`。不要重新引入 Bootstrap 5 的旧组件类。
- `static/vendor/bootstrap/SOURCES.md` 记录精确版本、来源和完整性校验；发行文件保留原始内容及 MIT 授权。

## 操作通知与错误

操作成功、操作校验及 4xx/5xx 错误统一使用页面右上角的 Bootstrap Toast，入口为 `static/js/notifications.js` 的 `window.platformFeedback`。通知随主题切换，窄屏保持在视口内；在原生 SQL 编辑对话框打开时也可查看和关闭。

- 使用 `platformFeedback.show(message, { level, key })` 显示通知；固定 `key` 可合并同一操作的重复提示，成功后用 `clear(key)` 清除。
- JSON 请求用 `platformFeedback.json(url, options)`，或将响应交给 `readJson(response)`。4xx 保留可操作的业务原因；登录过期、无权限和 5xx 使用统一中文提示，不显示原始 HTML、堆栈或数据库异常。
- 服务端成功/失败通过 `fragments/messages` 输出通知种子。直接访问的 HTML 错误页保留 HTTP 状态并提供返回入口；JSON API 保持原响应契约。
- 错误和警告保留到手动关闭，成功提示自动关闭。轮询仅在首次失败时通知，已加载的状态和日志仍保留。
- 字段格式提示、长期说明、迁移核对项及仅显示一次的凭证仍放在对应内容旁。

## 列表分页

列表和服务调用记录共用 `fragments/pagination.html`，使用 Bootstrap 的 `pagination`、`page-item`、`page-link` 与主题状态。总条数、总页数、每页条数和分页控件统一放在列表右下角，页码采用 `« 1 2 3 »` 相连边框样式；最多展示七个页码位置，保留首页、末页及当前页附近页码，间隔使用不可交互的 `...`。当前页标记 `aria-current="page"`，不可用的前后页使用非交互元素。

切换页码保留筛选和每页条数；选择每页条数后立即刷新列表，回到第一页并保留筛选，无需额外确认。窄屏控件换行后仍靠右排列，前后翻页使用带无障碍名称的箭头。工作流画布目录使用相同的 Bootstrap 分页结构，通过异步请求更新当前页、总数及禁用状态。

## 面包屑导航

- 所有使用后台公共布局的页面由 `fragments/breadcrumb.html` 统一生成面包屑；独立登录页不显示。
- 上级项必须链接到可访问的真实页面，当前页是唯一使用 `aria-current="page"` 的末项；不要为“数据开发”“数据治理”等没有对应页面的概念添加伪父级。
- 有详情页的资源应链接到详情页；没有详情页的资源祖先链接到列表中对应行的锚点。编辑页面包屑按进入来源设置父级，返回行为见[资源管理页面约定](../CONTRIBUTING.md#资源管理页面约定)。面包屑名称较长时应允许换行，不省略页面层级。

## 版本与验证边界

Bootstrap 6 仍为 Alpha 预发行版。升级应固定版本，按[官方迁移说明](https://getbootstrap.com/docs/6.0/guides/migration/)检查破坏性变更。当前官方浏览器最低要求为 Chrome / Edge 130、Firefox 132、Safari 18；低于该范围的浏览器不作为已支持目标。

修改公共布局或主题后，运行 Maven 页面渲染回归，并在浏览器验证浅色/深色、四种配色、刷新及跨页保留、跟随系统、桌面收起/展开、移动导航、登录及错误消息、SQL 编辑器和分页。只运行后端测试不能视为浏览器交互验证通过。
