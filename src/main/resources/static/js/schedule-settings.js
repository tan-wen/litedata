(() => {
  const preview = document.querySelector('[data-schedule-preview]');
  const cron = document.querySelector('[data-schedule-cron]');
  let activeRequest = 0;
  let previewTimer;

  const escape = (value) => String(value).replace(/[&<>"']/g, (character) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
  const showPreview = (content) => { if (preview) preview.innerHTML = content; };
  const format = (iso) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai' }).format(new Date(iso));
  const previewError = (message) => `<div class="schedule-preview-heading">下 3 次计划触发</div><p class="fg-danger mb-0">${escape(message)}</p>`;
  const responseMessage = async (response) => {
    try {
      const body = await response.json();
      return body.message || body.detail || '';
    } catch (_) {
      return '';
    }
  };
  const loadPreview = async () => {
    if (!preview || !cron) return;
    const value = cron.value.trim();
    if (!value) {
      activeRequest += 1;
      showPreview(previewError('请输入 Cron 表达式后再预览。'));
      return;
    }
    const request = ++activeRequest;
    showPreview('<div class="schedule-preview-heading">下 3 次计划触发</div><p class="fg-2 mb-0">正在计算计划时间…</p>');
    try {
      const response = await fetch(`/scheduling/preview?cron=${encodeURIComponent(value)}`, { headers: { Accept: 'application/json' } });
      if (!response.ok) throw new Error((await responseMessage(response)) || '该 Cron 表达式无法预览。');
      const data = await response.json();
      if (request !== activeRequest) return;
      if (!Array.isArray(data.nextTimes) || data.nextTimes.length === 0) throw new Error('未能计算下 3 次触发时间。');
      const times = data.nextTimes.map((time) => `<li><time datetime="${escape(time)}">${escape(format(time))}</time></li>`).join('');
      showPreview(`<div class="schedule-preview-heading">下 3 次计划触发 <span class="fg-2 fw-normal">· ${escape(data.timezone || 'Asia/Shanghai')}</span></div><ul class="schedule-preview-times">${times}</ul><p class="form-text mb-0">${escape(data.description || '计划时间仅供参考；等待上游或引擎资源时会延后开始。')}</p>`);
    } catch (error) {
      if (request !== activeRequest) return;
      showPreview(previewError(error.message || '暂时无法计算计划时间。'));
    }
  };
  const schedulePreview = () => {
    clearTimeout(previewTimer);
    activeRequest += 1;
    if (!cron.value.trim()) {
      showPreview(previewError('请输入 Cron 表达式后再预览。'));
      return;
    }
    showPreview('<div class="schedule-preview-heading">下 3 次计划触发</div><p class="fg-2 mb-0">正在等待输入完成…</p>');
    previewTimer = setTimeout(loadPreview, 280);
  };

  document.querySelectorAll('[data-dependency-filter]').forEach((input) => {
    const options = document.getElementById(input.dataset.dependencyFilter);
    if (!options) return;
    input.addEventListener('input', () => {
      const term = input.value.trim().toLocaleLowerCase();
      let visible = 0;
      options.querySelectorAll('.dependency-option').forEach((option) => {
        const matches = option.textContent.toLocaleLowerCase().includes(term);
        option.hidden = !matches;
        if (matches) visible += 1;
      });
      let empty = options.querySelector('.dependency-empty');
      if (!visible && term) {
        if (!empty) { empty = document.createElement('p'); empty.className = 'dependency-empty mb-0'; empty.textContent = '没有匹配的上游任务。'; options.append(empty); }
      } else if (empty) empty.remove();
    });
  });

  if (cron) { cron.addEventListener('input', schedulePreview); cron.addEventListener('change', schedulePreview); loadPreview(); }
})();
