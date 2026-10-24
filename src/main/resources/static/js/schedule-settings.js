(() => {
  const preview = document.querySelector('[data-schedule-preview]');
  const cron = document.querySelector('[data-schedule-cron]');
  let activeRequest = 0;

  const escape = (value) => String(value).replace(/[&<>"']/g, (character) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
  const showPreview = (content) => { if (preview) preview.innerHTML = content; };
  const format = (iso) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai' }).format(new Date(iso));
  const loadPreview = async () => {
    if (!preview || !cron || !cron.value) return;
    const request = ++activeRequest;
    showPreview('<div class="schedule-preview-heading">下 3 次计划触发</div><p class="fg-2 mb-0">正在计算计划时间…</p>');
    try {
      const response = await fetch(`/scheduling/preview?cron=${encodeURIComponent(cron.value)}`, { headers: { Accept: 'application/json' } });
      if (!response.ok) throw new Error('preview unavailable');
      const data = await response.json();
      if (request !== activeRequest) return;
      if (!Array.isArray(data.nextTimes) || data.nextTimes.length === 0) throw new Error('preview invalid');
      const times = data.nextTimes.map((time) => `<li><time datetime="${escape(time)}">${escape(format(time))}</time></li>`).join('');
      showPreview(`<div class="schedule-preview-heading">下 3 次计划触发 <span class="fg-2 fw-normal">· ${escape(data.timezone || 'Asia/Shanghai')}</span></div><ul class="schedule-preview-times">${times}</ul><p class="form-text mb-0">${escape(data.description || '计划时间仅供参考；等待上游或引擎资源时会延后开始。')}</p>`);
    } catch (error) {
      if (request !== activeRequest) return;
      showPreview('<div class="schedule-preview-heading">下 3 次计划触发</div><p class="fg-2 mb-0">暂时无法计算计划时间；保存与调度配置不受影响。</p>');
    }
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

  if (cron) { cron.addEventListener('change', loadPreview); loadPreview(); }
})();
