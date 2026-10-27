(() => {
  const body = document.getElementById('model-fields');
  const add = document.getElementById('add-field');
  if (!body || !add) return;
  const layer = document.getElementById('layer');
  const modelType = document.getElementById('modelType');
  const partitionType = document.getElementById('partitionType');
  const partitionColumn = document.getElementById('partitionColumn');
  const partitionUnit = document.getElementById('partitionUnit');
  const dimensionSettings = document.getElementById('dimension-settings');
  const partitionSettings = [...document.querySelectorAll('[data-partition-setting]')];
  const guidance = document.getElementById('field-guidance');

  function parseType(value) {
    const normalized = value.trim().toUpperCase().replace(/\s*([(),])\s*/g, '$1');
    let match;
    if (/^(TINYINT|SMALLINT|INT|BIGINT|LARGEINT)(?:\(\d+\))?$/.test(normalized)) return { kind: normalized.match(/^[A-Z]+/)[0] };
    if (/^(BOOLEAN|BOOL)$/.test(normalized)) return { kind: 'BOOLEAN' };
    if (/^(DATE|DATEV2)$/.test(normalized)) return { kind: 'DATE' };
    if ((match = normalized.match(/^(DATETIME|DATETIMEV2)(?:\((\d+)\))?$/))) return { kind: 'DATETIME', datetimePrecision: Number(match[2] || 0) };
    if ((match = normalized.match(/^CHAR\((\d+)\)$/))) return { kind: 'CHAR', length: Number(match[1]) };
    if ((match = normalized.match(/^VARCHAR\((\d+)\)$/))) return { kind: 'VARCHAR', length: Number(match[1]) };
    if (normalized === 'STRING' || normalized === 'FLOAT' || normalized === 'DOUBLE') return { kind: normalized };
    if ((match = normalized.match(/^(DECIMAL|DECIMALV3)\((\d+),(\d+)\)$/))) return { kind: 'DECIMAL', precision: Number(match[2]), scale: Number(match[3]) };
    return null;
  }

  function isValidType(parsed) {
    if (!parsed) return false;
    if (parsed.kind === 'CHAR') return parsed.length >= 1 && parsed.length <= 255;
    if (parsed.kind === 'VARCHAR') return parsed.length >= 1 && parsed.length <= 65533;
    if (parsed.kind === 'DECIMAL') return parsed.precision >= 1 && parsed.precision <= 38 && parsed.scale >= 0 && parsed.scale <= parsed.precision;
    return parsed.kind !== 'DATETIME' || (parsed.datetimePrecision >= 0 && parsed.datetimePrecision <= 6);
  }

  function controls(picker) {
    return {
      select: picker.querySelector('[data-type-select]'), length: picker.querySelector('[data-type-length]'),
      precision: picker.querySelector('[data-type-precision]'), scale: picker.querySelector('[data-type-scale]'),
      datetimePrecision: picker.querySelector('[data-type-datetime-precision]'), error: picker.querySelector('[data-type-error]'),
    };
  }

  function setTypeError(picker, message) {
    const { select, error } = controls(picker);
    select.setCustomValidity(message || '');
    error.hidden = !message;
    error.textContent = message || '';
  }

  function setParameterVisibility(picker, kind) {
    const { length, precision, scale, datetimePrecision } = controls(picker);
    const visible = { length: kind === 'CHAR' || kind === 'VARCHAR', precision: kind === 'DECIMAL', scale: kind === 'DECIMAL', datetimePrecision: kind === 'DATETIME' };
    [[length, visible.length], [precision, visible.precision], [scale, visible.scale], [datetimePrecision, visible.datetimePrecision]].forEach(([input, show]) => {
      input.closest('label').hidden = !show; input.disabled = !show; input.required = show;
    });
    if (kind === 'CHAR') length.max = '255';
    if (kind === 'VARCHAR') length.max = '65533';
  }

  function selectedOptionForKind(select, kind) { return [...select.options].find(option => option.dataset.typeKind === kind); }
  function canonicalType(kind, values) {
    if (kind === 'CHAR' || kind === 'VARCHAR') return `${kind}(${values.length})`;
    if (kind === 'DECIMAL') return `DECIMAL(${values.precision},${values.scale})`;
    if (kind === 'DATETIME') return values.datetimePrecision === 0 ? 'DATETIME' : `DATETIME(${values.datetimePrecision})`;
    return kind;
  }

  function synchronizeType(picker) {
    const { select, length, precision, scale, datetimePrecision } = controls(picker);
    const option = select.options[select.selectedIndex];
    const kind = option?.dataset.typeKind || parseType(select.value)?.kind;
    if (!kind) return;
    setParameterVisibility(picker, kind);
    const values = { length: Number(length.value), precision: Number(precision.value), scale: Number(scale.value), datetimePrecision: Number(datetimePrecision.value) };
    const parameterInputs = kind === 'CHAR' || kind === 'VARCHAR' ? [length] : kind === 'DECIMAL' ? [precision, scale] : kind === 'DATETIME' ? [datetimePrecision] : [];
    if (kind === 'DECIMAL') scale.max = precision.value || '38';
    if (parameterInputs.some(input => !input.checkValidity()) || !isValidType({ kind, ...values })) {
      setTypeError(picker, '请填写有效的数据类型参数。');
      return;
    }
    option.value = canonicalType(kind, values);
    setTypeError(picker, '');
  }

  function initializeTypePicker(picker) {
    if (picker.dataset.typeReady) return;
    const { select, length, precision, scale, datetimePrecision } = controls(picker);
    const currentValue = select.value;
    const parsed = parseType(currentValue);
    const option = parsed && selectedOptionForKind(select, parsed.kind);
    if (!option) {
      setParameterVisibility(picker, '');
      setTypeError(picker, '当前数据类型不受支持，请重新选择后保存。');
      picker.dataset.typeReady = 'true';
      return;
    }
    option.value = currentValue;
    select.selectedIndex = [...select.options].indexOf(option);
    if (parsed.kind === 'CHAR' || parsed.kind === 'VARCHAR') length.value = parsed.length;
    if (parsed.kind === 'DECIMAL') { precision.value = parsed.precision; scale.value = parsed.scale; }
    if (parsed.kind === 'DATETIME') datetimePrecision.value = parsed.datetimePrecision;
    [...select.options].filter(item => !item.dataset.typeKind).forEach(item => item.remove());
    picker.dataset.typeReady = 'true';
    synchronizeType(picker);
  }

  function initializeTypePickers() { body.querySelectorAll('[data-type-picker]').forEach(initializeTypePicker); }
  function selectType(picker) {
    const { select, length, precision, scale, datetimePrecision } = controls(picker);
    const kind = select.options[select.selectedIndex]?.dataset.typeKind;
    if (!kind) return;
    if (kind === 'CHAR') length.value = '1';
    if (kind === 'VARCHAR') length.value = '128';
    if (kind === 'DECIMAL') { precision.value = '18'; scale.value = '2'; }
    if (kind === 'DATETIME') datetimePrecision.value = '0';
    synchronizeType(picker);
  }

  function refreshSuggestions() {
    const names = [...body.rows].map(row => ({
      name: row.querySelector('[data-property="name"]').value.trim(), key: row.querySelector('[data-property="keyColumn"]').checked,
      type: row.querySelector('[data-property="dataType"]').value.trim().toUpperCase(),
    })).filter(field => field.name);
    const dimension = document.getElementById('dimension-fields');
    dimension.replaceChildren(...names.filter(field => !field.key).map(field => { const option = document.createElement('option'); option.value = field.name; return option; }));
    const partitions = document.getElementById('partition-fields');
    partitions.replaceChildren(...names.filter(field => field.key && /^(DATE|DATETIME(?:V2)?)(\(\d+\))?$/.test(field.type)).map(field => { const option = document.createElement('option'); option.value = field.name; return option; }));
  }

  function updateDimension(changed) {
    const enabled = layer.value === 'DIM';
    dimensionSettings.hidden = !enabled;
    dimensionSettings.querySelectorAll('input, select').forEach(input => { input.disabled = !enabled; });
    document.getElementById('dimensionKeys').required = enabled;
    document.getElementById('tableName').placeholder = enabled ? 'dim_enterprise' : 'ods_orders';
    modelType.querySelectorAll('option:not([value="UNIQUE"])').forEach(option => { option.disabled = enabled; });
    partitionType.querySelector('option[value="AUTO_RANGE"]').disabled = enabled;
    if (enabled && changed) { modelType.value = 'UNIQUE'; partitionType.value = 'NONE'; }
  }

  function updatePhysicalSettings() {
    const aggregate = modelType.value === 'AGGREGATE';
    [...body.rows].forEach(row => {
      const key = row.querySelector('[data-property="keyColumn"]').checked;
      const aggregation = row.querySelector('[data-property="aggregation"]');
      const cell = aggregation.closest('[data-aggregation-column]');
      cell.hidden = !aggregate; aggregation.disabled = !aggregate || key; aggregation.hidden = key;
      aggregation.required = aggregate && !key; cell.querySelector('[data-aggregation-placeholder]').hidden = !key;
      if (!aggregate || key) aggregation.value = '';
    });
    document.querySelector('.modeling-fields thead [data-aggregation-column]').hidden = !aggregate;
    partitionSettings.forEach(container => { const enabled = partitionType.value === 'AUTO_RANGE'; container.hidden = !enabled; container.querySelectorAll('input, select').forEach(input => { input.disabled = !enabled; }); });
    partitionColumn.required = partitionType.value === 'AUTO_RANGE'; partitionUnit.required = partitionType.value === 'AUTO_RANGE';
    guidance.textContent = aggregate ? '键列须连续排在前面且不可空。AGGREGATE 的每个非键字段都须选择聚合函数。'
      : modelType.value === 'DUPLICATE' ? '键列须连续排在前面且不可空。DUPLICATE KEY 保留每条写入明细，键列用于排序和分桶。'
        : '键列须连续排在前面且不可空。UNIQUE KEY 同键覆盖，适合当前同步与自动重试加工。';
    refreshSuggestions();
  }

  function renumber() {
    [...body.rows].forEach((row, index) => row.querySelectorAll('[data-property]').forEach(input => { input.name = `fields[${index}].${input.dataset.property}`; input.id = `field-${index}-${input.dataset.property}`; }));
    initializeTypePickers();
    add.disabled = body.rows.length >= 100;
    updatePhysicalSettings();
  }

  layer.addEventListener('change', () => { updateDimension(true); updatePhysicalSettings(); });
  modelType.addEventListener('change', updatePhysicalSettings);
  partitionType.addEventListener('change', updatePhysicalSettings);
  body.addEventListener('input', event => {
    const picker = event.target.closest('[data-type-picker]');
    if (picker && event.target.matches('[data-type-length], [data-type-precision], [data-type-scale], [data-type-datetime-precision]')) synchronizeType(picker);
    refreshSuggestions(); updatePhysicalSettings();
  });
  body.addEventListener('change', event => {
    const picker = event.target.closest('[data-type-picker]');
    if (picker && event.target.matches('[data-type-select]')) selectType(picker);
    if (picker && event.target.matches('[data-type-length], [data-type-precision], [data-type-scale], [data-type-datetime-precision]')) synchronizeType(picker);
    updatePhysicalSettings();
  });
  add.addEventListener('click', () => {
    if (body.rows.length >= 100) return;
    body.append(document.getElementById('field-template').content.cloneNode(true));
    const typeSelect = body.lastElementChild.querySelector('[data-type-select]');
    typeSelect.selectedIndex = [...typeSelect.options].indexOf(selectedOptionForKind(typeSelect, 'VARCHAR'));
    renumber(); body.lastElementChild.querySelector('input').focus();
  });
  body.addEventListener('click', event => {
    const button = event.target.closest('button'); if (!button) return;
    const row = button.closest('tr');
    if (button.hasAttribute('data-remove')) row.remove();
    if (button.dataset.move === 'up' && row.previousElementSibling) body.insertBefore(row, row.previousElementSibling);
    if (button.dataset.move === 'down' && row.nextElementSibling) body.insertBefore(row.nextElementSibling, row);
    renumber();
  });
  updateDimension(false);
  renumber();
})();
