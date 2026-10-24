(() => {
  'use strict';
  const root = document.querySelector('[data-log-console]');
  if (!root) return;
  const find = key => root.querySelector(`[data-log-${key}]`);
  const output = find('output'), status = find('status'), count = find('count'), error = find('error');
  const loadButton = find('load'), reset = find('reset'), download = find('download');
  const auto = find('auto'), follow = find('follow'), filter = find('filter');
  let offset = 0, lines = [], loading = false, generation = 0, controller;
  function render() {
    const term = filter.value.toLocaleLowerCase();
    const visible = term ? lines.filter(line => line.toLocaleLowerCase().includes(term)) : lines;
    output.textContent = visible.join('\n') || (term ? '已加载内容中没有匹配行。' : '暂无日志内容。');
    count.textContent = `已读取 ${offset} 行 · 当前窗口 ${lines.length} 行${term ? ` · 匹配 ${visible.length} 行` : ''}`;
    download.disabled = lines.length === 0;
    if (follow.checked) output.scrollTop = output.scrollHeight;
  }
  async function load() {
    if (loading || document.hidden) return;
    const current = generation;
    loading = true; loadButton.disabled = true; error.hidden = true;
    controller = new AbortController();
    const requestController = controller;
    const timeout = setTimeout(() => requestController.abort(), 15000);
    status.textContent = '正在读取日志…';
    try {
      const url = new URL(root.dataset.logUrl, location.origin);
      url.searchParams.set('offset', String(offset)); url.searchParams.set('limit', '200');
      const response = await fetch(url, { signal: requestController.signal, headers: { Accept: 'application/json' }, cache: 'no-store' });
      if (current !== generation) return;
      if (response.redirected || !response.headers.get('content-type')?.includes('application/json')) throw new Error('登录状态已失效，请刷新页面后重新登录。');
      const data = await response.json();
      if (current !== generation) return;
      if (!response.ok) throw new Error(data.message || data.detail || '日志读取失败，请稍后重试。');
      if (data.offset !== offset || !Number.isInteger(data.nextOffset) || data.nextOffset < offset || typeof data.content !== 'string') throw new Error('日志分页信息无效，请从头读取。');
      if (data.lineCount > 0) {
        const chunk = data.content.replace(/\n$/, '').split('\n');
        lines.push(...chunk); lines = lines.slice(-10000); offset = data.nextOffset;
      }
      render();
      status.textContent = data.hasMore ? '仍有后续日志，可继续加载。' : data.running ? '已读到当前末尾，等待新日志。' : '已读到当前日志末尾。';
      if (!data.running && !data.hasMore) auto.checked = false;
    } catch (failure) {
      if (current !== generation) return;
      error.textContent = failure.name === 'AbortError' ? '日志读取超时，请重试。' : failure.message;
      error.hidden = false; auto.checked = false; status.textContent = '读取已暂停，已加载内容保留。';
    } finally {
      clearTimeout(timeout);
      if (current === generation) { loading = false; loadButton.disabled = false; }
    }
  }
  loadButton.addEventListener('click', load);
  reset.addEventListener('click', () => { generation++; controller?.abort(); loading = false; offset = 0; lines = []; render(); load(); });
  filter.addEventListener('input', render);
  follow.addEventListener('change', () => { if (follow.checked) output.scrollTop = output.scrollHeight; });
  auto.addEventListener('change', () => { if (auto.checked) load(); });
  const timer = setInterval(() => { if (auto.checked) load(); }, 5000);
  window.addEventListener('pagehide', () => { clearInterval(timer); controller?.abort(); });
  download.addEventListener('click', () => {
    const url = URL.createObjectURL(new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a'); link.href = url; link.download = `node-log-through-line-${offset}.txt`; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  load();
})();
