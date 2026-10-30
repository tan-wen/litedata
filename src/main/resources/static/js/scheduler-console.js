(() => {
  'use strict';
  const root = document.querySelector('[data-scheduler-console]');
  if (!root) return;
  const find = key => root.querySelector(`[data-${key}]`);
  const write = (element, value) => { if (element) element.textContent = value; };
  const terminal = new Set(['SUCCESS', 'FAILURE', 'STOP', 'PAUSE', 'KILL', 'FORCED_SUCCESS', 'NEED_FAULT_TOLERANCE']);
  const badge = (element, state, labels) => {
    if (!element) return;
    element.classList.remove('theme-success', 'theme-danger', 'theme-warning', 'theme-info', 'theme-secondary');
    element.classList.add(['SUCCESS', 'FORCED_SUCCESS'].includes(state) ? 'theme-success'
      : ['FAILURE', 'KILL', 'NEED_FAULT_TOLERANCE'].includes(state) ? 'theme-danger' : terminal.has(state) ? 'theme-info' : 'theme-warning');
    element.textContent = labels[state] || state || '等待中';
  };
  async function jsonRequest(url, signal) {
    const response = await fetch(url, { signal, headers: { Accept: 'application/json' }, cache: 'no-store' });
    if (response.redirected || [401, 403].includes(response.status)) {
      const error = new Error('登录状态已失效，请刷新页面后重新登录。'); error.stop = true; throw error;
    }
    if (!response.headers.get('content-type')?.includes('json')) throw new Error('服务暂不可用，请稍后重试。');
    const data = await response.json();
    if (!response.ok) {
      const error = new Error(data.message || data.detail || '读取失败，请稍后重试。');
      error.stop = [401, 403, 404].includes(response.status); throw error;
    }
    return data;
  }
  function createLogReader(element) {
    if (!element) return null;
    const part = key => element.querySelector(`[data-log-${key}]`);
    const output = part('output'), status = part('status'), count = part('count'), error = part('error');
    const loadButton = part('load'), resetButton = part('reset'), download = part('download');
    const auto = part('auto'), follow = part('follow'), filter = part('filter');
    let url = element.dataset.logUrl || '', selectedId = element.dataset.selectedId || '';
    let selectedName = element.dataset.selectedName || '', retryTimes = null;
    let offset = 0, lines = [], loading = false, generation = 0, controller;
    let selectedActive = true, hasSnapshot = false, atEnd = false, finalReadNeeded = false;
    let finished = false, stopped = false, retryAt = 0, failures = 0;
    function controls() { loadButton.disabled = loading || !url; resetButton.disabled = !url; download.disabled = !lines.length; }
    function render() {
      const term = filter.value.toLocaleLowerCase();
      const visible = term ? lines.filter(line => line.toLocaleLowerCase().includes(term)) : lines;
      output.textContent = visible.join('\n') || (term ? '已加载内容中没有匹配行。' : url ? '暂无日志内容。' : '等待节点派发后自动读取日志。');
      count.textContent = `已读取 ${offset} 行 · 当前窗口 ${lines.length} 行${term ? ` · 匹配 ${visible.length} 行` : ''}`;
      controls();
      if (follow.checked) output.scrollTop = output.scrollHeight;
    }
    function reset() {
      generation++; controller?.abort(); loading = false; offset = 0; lines = [];
      atEnd = false; retryAt = 0; failures = 0; stopped = false; error.hidden = true; render();
    }
    async function load() {
      if (loading || !url || document.hidden || stopped) return;
      const current = generation, finalRequest = finalReadNeeded;
      finalReadNeeded = false; loading = true; controls(); error.hidden = true;
      const requestController = new AbortController(); controller = requestController;
      const timeout = setTimeout(() => requestController.abort(), 25000);
      status.textContent = '正在读取日志…';
      try {
        const requestUrl = new URL(url, location.origin);
        requestUrl.searchParams.set('offset', String(offset)); requestUrl.searchParams.set('limit', '200');
        const data = await jsonRequest(requestUrl, requestController.signal);
        if (current !== generation) return;
        if (data.offset !== offset || !Number.isInteger(data.nextOffset) || data.nextOffset < offset || typeof data.content !== 'string') throw new Error('日志分页信息无效，请从头读取。');
        if (data.lineCount > 0) {
          lines.push(...data.content.replace(/\n$/, '').split('\n'));
          lines = lines.slice(-10000); offset = data.nextOffset;
        }
        atEnd = !data.hasMore; if (!hasSnapshot) selectedActive = data.running;
        failures = 0; retryAt = 0; render();
        status.textContent = data.hasMore ? '仍有后续日志，可继续加载。' : data.running ? '已读到当前末尾，等待新日志。' : '已读到当前日志末尾。';
      } catch (failure) {
        if (current !== generation) return;
        if (finalRequest) finalReadNeeded = true;
        failures++; retryAt = Date.now() + Math.min(30000, 5000 * 2 ** (failures - 1));
        stopped = Boolean(failure.stop);
        error.textContent = failure.name === 'AbortError' ? '日志读取超时，请重试。' : failure.message;
        error.hidden = false;
        status.textContent = stopped || !auto.checked ? '读取已暂停，已加载内容保留。' : '日志暂不可用，将自动重试；已加载内容保留。';
      } finally {
        clearTimeout(timeout);
        if (current === generation) { loading = false; controls(); }
      }
    }
    function automatic() {
      if (auto.checked && !stopped && Date.now() >= retryAt && (!atEnd || selectedActive || finalReadNeeded)) load();
    }
    function select(node, workflowFinished, labels) {
      hasSnapshot = true;
      if (workflowFinished && !finished) finalReadNeeded = true;
      finished = workflowFinished;
      if (!node) {
        url = ''; selectedActive = false;
        write(part('title'), selectedName || '等待节点派发'); badge(part('node-state'), '', labels);
        status.textContent = '等待节点派发，已有日志保留。'; render(); return;
      }
      const changed = selectedId !== String(node.id);
      const retried = retryTimes !== null && node.retryTimes > retryTimes;
      if (changed || retried) reset();
      selectedId = String(node.id); selectedName = node.name; retryTimes = node.retryTimes;
      selectedActive = !terminal.has(node.state);
      url = `${root.dataset.instancePath}/tasks/${selectedId}/log`;
      element.dataset.logUrl = url; element.dataset.selectedId = selectedId; element.dataset.selectedName = selectedName;
      write(part('title'), selectedName); badge(part('node-state'), node.state, labels); controls();
      if (changed) {
        const address = new URL(location.href); address.searchParams.set('taskId', selectedId); history.replaceState(null, '', address);
      }
      if (changed || retried) load(); else automatic();
    }
    loadButton.addEventListener('click', () => { stopped = false; load(); });
    resetButton.addEventListener('click', () => { reset(); load(); });
    filter.addEventListener('input', render);
    follow.addEventListener('change', () => { if (follow.checked) output.scrollTop = output.scrollHeight; });
    auto.addEventListener('change', () => { if (auto.checked) { stopped = false; finalReadNeeded = true; load(); } });
    download.addEventListener('click', () => {
      const objectUrl = URL.createObjectURL(new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' }));
      const link = document.createElement('a'); link.href = objectUrl; link.download = `node-log-through-line-${offset}.txt`; link.click();
      setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    });
    render(); if (url) load();
    return { get id() { return selectedId; }, get name() { return selectedName; }, select, automatic,
      suspend() { generation++; controller?.abort(); loading = false; controls(); } };
  }

  const stateBadge = find('workflow-state');
  if (!stateBadge) { write(find('scheduler-status'), '状态暂不可用，请点击“刷新状态”重试。'); return; }
  const log = createLogReader(find('log-console'));
  const nav = find('scheduler-nodes'), template = find('scheduler-node-template');
  const refresh = find('scheduler-refresh'), status = find('scheduler-status'), error = find('scheduler-error');
  let finished = false, loading = false, active = true, stopped = false, retryAt = 0, failures = 0;
  let controller, timer, generation = 0;
  function renderNodes(nodes, selectedId, labels) {
    const existing = new Map(Array.from(nav.querySelectorAll('[data-node-id]'), element => [element.dataset.nodeId, element]));
    for (const [index, node] of nodes.entries()) {
      const id = String(node.id);
      const element = existing.get(id) || template.content.firstElementChild.cloneNode(true);
      existing.delete(id); element.dataset.nodeId = id;
      element.href = `${root.dataset.instancePath}?taskId=${id}`;
      element.classList.toggle('is-selected', id === selectedId);
      if (id === selectedId) element.setAttribute('aria-current', 'true'); else element.removeAttribute('aria-current');
      const part = key => element.querySelector(`[data-node-${key}]`);
      write(part('name'), node.name); badge(part('state'), node.state, labels);
      write(part('retries'), node.retryTimes); write(part('id-text'), `#${id}`);
      write(part('start'), node.startedAt || '尚未开始'); write(part('end'), node.finishedAt || '尚未结束');
      if (nav.children[index] !== element) nav.insertBefore(element, nav.children[index] || null);
    }
    existing.forEach(element => element.remove());
    let empty = find('scheduler-empty');
    if (!empty && !nodes.length) {
      empty = document.createElement('p'); empty.dataset.schedulerEmpty = ''; empty.className = 'fg-2 p-5 mb-0';
      empty.textContent = '尚未派发节点，正在等待上游或调度资源。'; nav.after(empty);
    }
    if (empty) empty.hidden = nodes.length > 0;
  }
  function callbackBadge(element, state) {
    if (!element) return;
    element.classList.remove('theme-success', 'theme-danger', 'theme-warning', 'theme-info', 'theme-secondary');
    element.classList.add(state === 'SUCCESS' ? 'theme-success' : state === 'FAILED' ? 'theme-danger' : 'theme-warning');
    element.textContent = state === 'SUCCESS' ? '成功' : state === 'FAILED' ? '失败' : '运行中';
  }
  function renderCallbacks(page) {
    const panel = find('callback-records');
    if (!panel || !page || !Array.isArray(page.items)) return;
    const rows = panel.querySelector('[data-callback-rows]');
    const template = panel.querySelector('[data-callback-row-template]');
    if (!rows || !template) return;
    const values = page.items;
    rows.replaceChildren();
    for (const run of values) {
      const row = template.content.firstElementChild.cloneNode(true);
      const part = key => row.querySelector(`[data-callback-${key}]`);
      write(part('id'), run.id ?? '—'); write(part('task-id'), run.taskInstanceId ?? '—');
      callbackBadge(part('state'), run.status || 'RUNNING');
      write(part('start'), run.startedAt || '—'); write(part('end'), run.finishedAt || '—');
      write(part('range'), `[${run.lowerBound || '—'}, ${run.upperBound || '—'})`);
      write(part('message'), run.message || '等待执行节点回传结果');
      rows.append(row);
    }
    if (!values.length) {
      const empty = document.createElement('tr'); empty.dataset.callbackEmpty = '';
      const cell = document.createElement('td'); cell.colSpan = 7; cell.className = 'text-center fg-2 py-7';
      cell.append('尚未收到可关联回调。实例可能仍在排队、节点尚未领取，或回调尚未抵达平台。旧执行器未携带实例编号的记录请查看');
      if (panel.dataset.callbackHistoryUrl) {
        const history = document.createElement('a'); history.href = panel.dataset.callbackHistoryUrl; history.textContent = '历史未关联回调';
        cell.append(history, '。');
      }
      empty.append(cell); rows.append(empty);
    }
    const total = Number.isInteger(page.total) ? page.total : values.length;
    write(panel.querySelector('[data-callback-total]'), `共 ${total} 条 · 平台回调`);
  }
  async function refreshStatus() {
    if (loading || !active || document.hidden) return;
    const current = generation; loading = true; refresh.disabled = true;
    const requestController = new AbortController(); controller = requestController;
    const timeout = setTimeout(() => requestController.abort(), 25000);
    try {
      const data = await jsonRequest(root.dataset.statusUrl, requestController.signal);
      if (current !== generation || !active) return;
      if (!data.instance || !Array.isArray(data.nodes) || typeof data.finished !== 'boolean') throw new Error('状态数据不完整，请重试。');
      const labels = data.labels || {};
      badge(stateBadge, data.instance.state, labels);
      write(find('workflow-start'), data.instance.startedAt || '尚未开始'); write(find('workflow-end'), data.instance.finishedAt || '尚未结束');
      write(find('workflow-runs'), data.instance.runTimes); write(find('node-count'), data.nodes.length);
      if (log) {
        const named = data.nodes.filter(node => node.name === log.name);
        const selected = data.nodes.find(node => String(node.id) === log.id) || (named.length === 1 ? named[0] : null)
          || data.nodes.find(node => node.state === 'FAILURE') || data.nodes[0];
        log.select(selected, data.finished, labels);
      }
      renderNodes(data.nodes, log?.id || '', labels);
      renderCallbacks(data.callbackPage);
      finished = data.finished; stopped = false; retryAt = 0; failures = 0; error.hidden = true;
      const time = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', timeStyle: 'medium' }).format(new Date(data.updatedAt));
      status.textContent = finished ? `执行已结束 · ${time} 更新` : `每 5 秒自动更新状态 · ${time} 更新`;
    } catch (failure) {
      if (current !== generation || !active) return;
      failures++; retryAt = Date.now() + Math.min(30000, 5000 * 2 ** (failures - 1)); stopped = Boolean(failure.stop);
      error.textContent = failure.name === 'AbortError' ? '状态读取超时，已保留上次结果。' : failure.message;
      error.hidden = false;
      status.textContent = stopped || finished ? '已保留上次状态，请点击“刷新状态”重试。' : '状态暂不可用，将自动重试；上次结果已保留。';
    } finally {
      clearTimeout(timeout);
      if (current === generation) { loading = false; refresh.disabled = false; }
    }
  }
  function tick() {
    if (!active || document.hidden) return;
    if (!finished && !stopped && Date.now() >= retryAt) refreshStatus();
    log?.automatic();
  }
  function startTimer() { clearInterval(timer); timer = setInterval(tick, 5000); }
  refresh.addEventListener('click', () => { stopped = false; refreshStatus(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden && !finished) tick(); });
  window.addEventListener('pagehide', () => { active = false; generation++; controller?.abort(); loading = false; clearInterval(timer); log?.suspend(); });
  window.addEventListener('pageshow', event => { if (event.persisted) { active = true; startTimer(); refreshStatus(); log?.automatic(); } });
  startTimer(); refreshStatus();
})();
