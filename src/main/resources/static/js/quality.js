(() => {
  const form = document.querySelector('[data-quality-form]');
  if (!form) {
    const metrics = document.querySelectorAll('[data-quality-metric-index]');
    const rows = document.querySelectorAll('table tbody tr');
    metrics.forEach(metric => {
      if (!metric.textContent.trim()) return;
      const row = rows[Number(metric.dataset.qualityMetricIndex)];
      const cell = row?.children[3];
      if (!cell) return;
      const value = document.createElement('div');
      value.className = 'small fg-2';
      value.textContent = `检测值：${metric.textContent}`;
      cell.append(value);
    });
    return;
  }
  const rules = form.querySelector('[data-quality-rules]');
  const template = document.getElementById('quality-rule-template');
  const add = form.querySelector('[data-quality-add-rule]');
  const empty = form.querySelector('[data-quality-empty]');
  const hints = {
    NOT_NULL: '字段不可为 NULL；可填写多个逗号分隔字段。', NOT_BLANK: '字段不可为空字符串；可填写多个逗号分隔字段。', UNIQUE: '字段组合必须唯一；可填写多个逗号分隔字段。',
    RANGE: '参数 1 为最小值，参数 2 为最大值。', LENGTH: '参数 1 为最小长度，参数 2 为最大长度。', REGEX: '参数 1 为正则表达式。', ENUM: '参数 1 为允许值，每行一个。',
    NON_NEGATIVE: '字段值必须大于或等于 0。', POSITIVE: '字段值必须大于 0。', FUTURE_TIME: '字段时间不得晚于当前时间。', FRESHNESS: '参数 1 为最大允许延迟小时数。',
    ROW_COUNT: '参数 1 为最小行数，参数 2 为最大行数。', SUM_RANGE: '参数 1 为最小聚合值，参数 2 为最大聚合值。', AVG_RANGE: '参数 1 为最小平均值，参数 2 为最大平均值。',
    COMPARE_COLUMNS: '字段填写两个列名；参数 1 填比较运算符，例如 >=。', REFERENCE: '字段值须存在于同库引用表和引用字段。', ROW_COUNT_CHANGE: '参数 1 为允许的行数变化百分比。', CUSTOM_SQL: '参数 1 为返回异常行的 SELECT，仅支持 Doris。'
  };
  const twoParameters = new Set(['RANGE', 'LENGTH', 'ROW_COUNT', 'SUM_RANGE', 'AVG_RANGE']);
  const oneParameter = new Set(['REGEX', 'ENUM', 'FRESHNESS', 'COMPARE_COLUMNS', 'ROW_COUNT_CHANGE', 'CUSTOM_SQL']);
  const setVisible = (field, visible) => { if (field) field.closest('.md\\:col-3')?.classList.toggle('d-none', !visible); };
  const refreshRule = rule => {
    const type = rule.querySelector('[data-quality-type]');
    const value = type?.value || '';
    const optionHint = type?.selectedOptions[0]?.dataset.hint;
    const hint = rule.querySelector('[data-quality-hint]');
    if (hint) hint.textContent = optionHint || hints[value] || '选择规则类型后查看参数要求。';
    setVisible(rule.querySelector('[data-quality-param="one"]'), twoParameters.has(value) || oneParameter.has(value));
    setVisible(rule.querySelector('[data-quality-param="two"]'), twoParameters.has(value));
    setVisible(rule.querySelector('[data-quality-reference="table"]'), value === 'REFERENCE');
    setVisible(rule.querySelector('[data-quality-reference="column"]'), value === 'REFERENCE');
  };
  const reindex = () => {
    const items = [...rules.querySelectorAll('[data-quality-rule]')];
    items.forEach((rule, index) => {
      const title = rule.querySelector('strong');
      if (title) title.textContent = `规则 ${index + 1}`;
      rule.querySelectorAll('[data-quality-field]').forEach(field => { field.name = `rules[${index}].${field.dataset.qualityField}`; });
      rule.querySelectorAll('input[name], select[name], textarea[name]').forEach(field => {
        const match = field.name.match(/^rules\[\d+\]\.(.+)$/);
        if (match) field.name = `rules[${index}].${match[1]}`;
      });
      rule.querySelectorAll('input, select, textarea').forEach(field => {
        field.id = `quality-rule-${index}-${field.name.split('.').at(-1)}`;
        const label = field.parentElement?.querySelector('label');
        if (label) label.htmlFor = field.id;
      });
      refreshRule(rule);
    });
    if (empty) empty.classList.toggle('d-none', items.length > 0);
    if (add) add.disabled = items.length >= 30;
  };
  add?.addEventListener('click', () => {
    if (!template || rules.querySelectorAll('[data-quality-rule]').length >= 30) return;
    rules.append(template.content.cloneNode(true));
    rules.querySelector('[data-quality-rule]:last-of-type [data-quality-field="severity"]')?.setAttribute('value', 'CRITICAL');
    const severity = rules.querySelector('[data-quality-rule]:last-of-type [data-quality-field="severity"]');
    if (severity) severity.value = 'CRITICAL';
    reindex();
    rules.querySelector('[data-quality-rule]:last-of-type input')?.focus();
  });
  rules.addEventListener('click', event => {
    const button = event.target.closest('[data-quality-remove]');
    if (!button) return;
    button.closest('[data-quality-rule]')?.remove();
    reindex();
  });
  rules.addEventListener('change', event => { if (event.target.matches('[data-quality-type]')) refreshRule(event.target.closest('[data-quality-rule]')); });
  reindex();
})();
