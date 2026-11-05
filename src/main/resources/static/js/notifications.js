(() => {
    'use strict';
    const scriptUrl = document.currentScript.src;
    const bootstrapReady = import(new URL('../vendor/bootstrap/bootstrap.bundle.min.js', scriptUrl).href);
    const ready = document.readyState === 'loading'
        ? new Promise(resolve => document.addEventListener('DOMContentLoaded', resolve, {once: true})) : Promise.resolve();
    const notices = new Map();
    const labels = {success: '操作成功', info: '提示', warning: '请检查后重试', danger: '操作失败'};
    const httpMessages = {
        400: ['请求未完成', '提交内容有误，请检查后重试。'],
        401: ['登录已失效', '请刷新页面并重新登录后再操作。'],
        403: ['操作未获授权', '当前账号权限不足或页面已过期，请刷新页面后重试。'],
        404: ['内容不存在', '请求的内容不存在或已被删除，请刷新页面后重试。'],
        405: ['操作不支持', '当前页面不支持此操作，请刷新页面后重试。'],
        409: ['配置已变化', '配置已被其他操作修改，请刷新页面、核对后重试。'],
        410: ['内容已失效', '请求的内容已失效，请刷新页面后重试。'],
        422: ['请检查提交内容', '提交内容未通过校验，请检查后重试。'],
        429: ['操作过于频繁', '请求较多，请稍候再试。']
    };
    let container;

    function ensureContainer() {
        if (container) return;
        container = document.createElement('div');
        container.className = 'toast-container platform-notifications';
        container.popover = 'manual';
        container.setAttribute('aria-label', '操作通知');
        container.setAttribute('aria-live', 'polite');
        container.setAttribute('aria-atomic', 'false');
        document.body.append(container);
    }

    function relocate() {
        if (!container) return;
        const host = document.querySelector('dialog:modal') || document.body;
        if (container.parentElement !== host) host.append(container);
        if (container.childElementCount && !container.matches(':popover-open')) container.showPopover();
    }

    function remove(key) {
        const notice = notices.get(key);
        if (!notice) return;
        notices.delete(key);
        notice.instance?.dispose();
        notice.element.remove();
        if (container && !container.childElementCount && container.matches(':popover-open')) container.hidePopover();
    }

    async function show(value, options = {}) {
        const status = options.status ?? value?.status;
        const message = value?.name === 'TypeError' ? '网络连接失败，请检查网络后重试。'
            : typeof value === 'string' ? value : value?.message;
        if (!message) return;
        const level = options.level || (status >= 500 ? 'danger' : 'warning');
        const title = options.title || value?.title || labels[level] || labels.warning;
        const key = options.key || `${level}:${status || ''}:${message}`;
        await ready;
        ensureContainer();
        if (notices.has(key)) {
            const current = notices.get(key);
            if (current.message === message) return;
            remove(key);
        }
        while (notices.size >= 5) remove(notices.keys().next().value);
        const element = document.createElement('div'), header = document.createElement('div');
        const heading = document.createElement('strong'), close = document.createElement('button'),
            body = document.createElement('div');
        element.className = `toast theme-${Object.hasOwn(labels, level) ? level : 'warning'}`;
        element.setAttribute('role', ['success', 'info'].includes(level) ? 'status' : 'alert');
        element.setAttribute('aria-live', ['success', 'info'].includes(level) ? 'polite' : 'assertive');
        element.setAttribute('aria-atomic', 'true');
        header.className = 'toast-header';
        heading.className = 'me-auto';
        heading.textContent = title;
        close.type = 'button';
        close.className = 'btn-close';
        close.dataset.bsDismiss = 'toast';
        close.setAttribute('aria-label', '关闭通知');
        body.className = 'toast-body';
        body.textContent = message;
        header.append(heading);
        if (status) {
            const code = document.createElement('small');
            code.className = 'notification-status';
            code.textContent = `HTTP ${status}`;
            header.append(code);
        }
        header.append(close);
        element.append(header, body);
        container.append(element);
        const notice = {element, message, instance: null};
        notices.set(key, notice);
        relocate();
        const {Toast} = await bootstrapReady;
        if (notices.get(key) !== notice) return;
        notice.instance = Toast.getOrCreateInstance(element, {
            autohide: ['success', 'info'].includes(level),
            delay: 6000
        });
        element.addEventListener('hidden.bs.toast', () => remove(key), {once: true});
        notice.instance.show();
    }

    class RequestError extends Error {
        constructor(message, status = 0, title = '') {
            super(message);
            this.name = 'RequestError';
            this.status = status;
            this.title = title;
        }
    }

    async function readJson(response, fallback = '服务返回了无法识别的响应，请稍后重试。') {
        if (response.redirected && new URL(response.url, location.origin).pathname.endsWith('/login')) {
            throw new RequestError(httpMessages[401][1], 401, httpMessages[401][0]);
        }
        let data;
        if (response.headers.get('content-type')?.includes('json')) {
            try {
                data = await response.json();
            } catch (_) { /* Use the status-specific fallback below. */
            }
        }
        if (!response.ok) {
            const status = response.status;
            const [title, defaultMessage] = status >= 500
                ? ['服务暂不可用', '服务暂时无法处理请求，请稍后重试。']
                : httpMessages[status] || ['请求未完成', '请求未能完成，请检查后重试。'];
            const detail = data?.message || data?.detail;
            // Unexpected server failures must never surface stack traces or database details.
            const message = status < 500 && ![401, 403].includes(status) && typeof detail === 'string' && detail.trim()
                ? detail : defaultMessage;
            throw new RequestError(message, status, title);
        }
        if (data === undefined) throw new RequestError(fallback);
        return data;
    }

    async function json(url, options = {}) {
        let response;
        try {
            response = await fetch(url, options);
        } catch (failure) {
            if (failure.name === 'AbortError') throw failure;
            throw new RequestError('网络连接失败，请检查网络后重试。');
        }
        return readJson(response);
    }

    window.platformFeedback = {show, clear: remove, readJson, json, RequestError};
    ready.then(() => {
        ensureContainer();
        // Let the live region enter the accessibility tree before adding server messages.
        requestAnimationFrame(() => document.querySelectorAll('[data-platform-notification]').forEach(seed => {
            show(seed.textContent.trim(), {
                level: seed.dataset.platformNotification,
                status: Number(seed.dataset.notificationStatus) || undefined
            });
            seed.remove();
        }));
        new MutationObserver(relocate).observe(document.body, {
            subtree: true,
            attributes: true,
            attributeFilter: ['open']
        });
    });
})();
