(() => {
  'use strict';
  document.querySelectorAll('[data-workflow-delete-form]').forEach(form => {
    form.addEventListener('submit', async event => {
      event.preventDefault();
      const button = form.querySelector('button[type="submit"]');
      if (button.disabled || !window.confirm(`删除加工流程“${form.dataset.workflowName}”？所属 SQL 任务将归档，同步管道、物理数据和运行历史将保留。`)) return;
      window.platformFeedback.clear('workflow-delete'); button.disabled = true;
      try {
        const response = await fetch(form.action, { method: 'POST', body: new URLSearchParams(new FormData(form)), headers: { Accept: 'application/json' }, cache: 'no-store' });
        if (!response.ok || response.redirected && new URL(response.url).pathname.endsWith('/login')) await window.platformFeedback.readJson(response);
        location.assign(response.redirected ? response.url : location.href);
      } catch (failure) {
        window.platformFeedback.show(failure, { key: 'workflow-delete' }); button.disabled = false;
      }
    });
  });
})();
