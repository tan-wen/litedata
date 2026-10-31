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

## 面包屑导航

- 所有使用后台公共布局的页面由 `fragments/breadcrumb.html` 统一生成面包屑；独立登录页不显示。
- 上级项必须链接到可访问的真实页面，当前页是唯一使用 `aria-current="page"` 的末项；不要为“数据开发”“数据治理”等没有对应页面的概念添加伪父级。
- 有详情页的资源应链接到详情页；没有详情页的资源祖先链接到列表中对应行的锚点。编辑页面包屑按进入来源设置父级，返回行为见[资源管理页面约定](../CONTRIBUTING.md#资源管理页面约定)。面包屑名称较长时应允许换行，不省略页面层级。

## 版本与验证边界

Bootstrap 6 仍为 Alpha 预发行版。升级应固定版本，按[官方迁移说明](https://getbootstrap.com/docs/6.0/guides/migration/)检查破坏性变更。当前官方浏览器最低要求为 Chrome / Edge 130、Firefox 132、Safari 18；低于该范围的浏览器不作为已支持目标。

修改公共布局或主题后，运行 Maven 页面渲染回归，并在浏览器验证浅色/深色、四种配色、刷新及跨页保留、跟随系统、桌面收起/展开、移动导航、登录及错误消息、SQL 编辑器和分页。只运行后端测试不能视为浏览器交互验证通过。
