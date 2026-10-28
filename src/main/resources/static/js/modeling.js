(() => {
  const form = document.getElementById('model-form');
  const body = document.getElementById('model-fields');
  const add = document.getElementById('add-field');
  if (!form || !body || !add) return;

  const layer = document.getElementById('layer');
  const modelType = document.getElementById('modelType');
  const partitionType = document.getElementById('partitionType');
  const partitionColumn = document.getElementById('partitionColumn');
  const partitionUnit = document.getElementById('partitionUnit');
  const distributionType = document.getElementById('distributionType');
  const distributionColumns = document.getElementById('distributionColumns');
  let defaultDistributionKeys = distributionColumns.dataset.defaultKeys === 'true';
  const distributionKeys = document.querySelector('[data-distribution-keys]');
  const distributionKeyList = document.querySelector('[data-distribution-key-list]');
  const distributionTypeHelp = document.querySelector('[data-distribution-type-help]');
  const distributionKeyHelp = document.querySelector('[data-distribution-key-help]');
  const autoBuckets = document.querySelector('[data-auto-buckets]');
  const bucketCount = document.querySelector('[data-bucket-count]');
  const bucketHelp = document.querySelector('[data-bucket-help]');
  const dimensionSettings = document.getElementById('dimension-settings');
  const partitionColumnSetting = document.querySelector('[data-partition-column-setting]');
  const partitionUnitSetting = document.querySelector('[data-partition-unit-setting]');
  const rangeStyle = document.querySelector('[data-partition-range-style]');
  const manualRangeSettings = document.querySelector('[data-manual-range-settings]');
  const manualListSettings = document.querySelector('[data-manual-list-settings]');
  const manualRangeList = document.querySelector('[data-manual-range-list]');
  const manualListList = document.querySelector('[data-manual-list-list]');
  const dynamicPartitionSettings = document.querySelector('[data-dynamic-partition-settings]');
  const guidance = document.getElementById('field-guidance');
  const aggregationRules = document.querySelector('[data-aggregation-rules]');
  const aggregationEmpty = document.querySelector('[data-aggregation-empty]');
  const wizardPanels = [...form.querySelectorAll('[data-wizard-step]')];
  const wizardSteps = [...form.querySelectorAll('[data-wizard-target]')];
  const wizardNotice = form.querySelector('[data-wizard-notice]');
  let activeStep = 1;

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
  function controls(picker) { return { select: picker.querySelector('[data-type-select]'), length: picker.querySelector('[data-type-length]'), precision: picker.querySelector('[data-type-precision]'), scale: picker.querySelector('[data-type-scale]'), datetimePrecision: picker.querySelector('[data-type-datetime-precision]'), error: picker.querySelector('[data-type-error]') }; }
  function setTypeError(picker, message) { const { select, error } = controls(picker); select.setCustomValidity(message || ''); error.hidden = !message; error.textContent = message || ''; }
  function setParameterVisibility(picker, kind) {
    const { length, precision, scale, datetimePrecision } = controls(picker);
    const visible = { length: kind === 'CHAR' || kind === 'VARCHAR', precision: kind === 'DECIMAL', scale: kind === 'DECIMAL', datetimePrecision: kind === 'DATETIME' };
    [[length, visible.length], [precision, visible.precision], [scale, visible.scale], [datetimePrecision, visible.datetimePrecision]].forEach(([input, show]) => { input.closest('label').hidden = !show; input.disabled = !show; input.required = show; });
    if (kind === 'CHAR') length.max = '255';
    if (kind === 'VARCHAR') length.max = '65533';
  }
  function selectedOptionForKind(select, kind) { return [...select.options].find(option => option.dataset.typeKind === kind); }
  function canonicalType(kind, values) { if (kind === 'CHAR' || kind === 'VARCHAR') return `${kind}(${values.length})`; if (kind === 'DECIMAL') return `DECIMAL(${values.precision},${values.scale})`; if (kind === 'DATETIME') return values.datetimePrecision === 0 ? 'DATETIME' : `DATETIME(${values.datetimePrecision})`; return kind; }
  function synchronizeType(picker) {
    const { select, length, precision, scale, datetimePrecision } = controls(picker);
    const option = select.options[select.selectedIndex]; const kind = option?.dataset.typeKind || parseType(select.value)?.kind;
    if (!kind) return;
    setParameterVisibility(picker, kind);
    const values = { length: Number(length.value), precision: Number(precision.value), scale: Number(scale.value), datetimePrecision: Number(datetimePrecision.value) };
    const parameterInputs = kind === 'CHAR' || kind === 'VARCHAR' ? [length] : kind === 'DECIMAL' ? [precision, scale] : kind === 'DATETIME' ? [datetimePrecision] : [];
    if (kind === 'DECIMAL') scale.max = precision.value || '38';
    if (parameterInputs.some(input => !input.checkValidity()) || !isValidType({ kind, ...values })) return setTypeError(picker, '请填写有效的数据类型参数。');
    option.value = canonicalType(kind, values); setTypeError(picker, '');
  }
  function initializeTypePicker(picker) {
    if (picker.dataset.typeReady) return;
    const { select, length, precision, scale, datetimePrecision } = controls(picker); const currentValue = select.value; const parsed = parseType(currentValue); const option = parsed && selectedOptionForKind(select, parsed.kind);
    if (!option) { setParameterVisibility(picker, ''); setTypeError(picker, '当前数据类型不受支持，请重新选择后保存。'); picker.dataset.typeReady = 'true'; return; }
    option.value = currentValue; select.selectedIndex = [...select.options].indexOf(option);
    if (parsed.kind === 'CHAR' || parsed.kind === 'VARCHAR') length.value = parsed.length;
    if (parsed.kind === 'DECIMAL') { precision.value = parsed.precision; scale.value = parsed.scale; }
    if (parsed.kind === 'DATETIME') datetimePrecision.value = parsed.datetimePrecision;
    [...select.options].filter(item => !item.dataset.typeKind).forEach(item => item.remove()); picker.dataset.typeReady = 'true'; synchronizeType(picker);
  }
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
  function fields() { return [...body.rows].map((row, index) => ({ index, row, name: row.querySelector('[data-property="name"]').value.trim(), dataType: row.querySelector('[data-property="dataType"]').value.trim(), key: row.querySelector('[data-property="keyColumn"]').checked, nullable: row.querySelector('[data-property="nullable"]').checked, aggregation: row.querySelector('[data-property="aggregation"]') })); }
  function updateFieldConstraints() {
    [...body.rows].forEach(row => {
      const key = row.querySelector('[data-property="keyColumn"]').checked;
      const nullable = row.querySelector('[data-property="nullable"]');
      if (key) nullable.checked = false;
      nullable.disabled = key;
      const picker = row.querySelector('[data-type-picker]');
      const select = picker.querySelector('[data-type-select]');
      [...select.options].forEach(option => { option.disabled = key && ['FLOAT', 'DOUBLE', 'STRING'].includes(option.dataset.typeKind); });
      synchronizeType(picker);
      if (key && ['FLOAT', 'DOUBLE', 'STRING'].includes(parseType(select.value)?.kind)) setTypeError(picker, '键列不支持 FLOAT、DOUBLE 或 STRING，请选择其他类型。');
    });
  }
  function partitionCandidateTypes() {
    if (partitionType.value === 'AUTO_RANGE' || partitionType.value === 'DYNAMIC') return ['DATE', 'DATETIME'];
    if (partitionType.value === 'MANUAL_RANGE') return ['DATE', 'DATETIME', 'TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'LARGEINT'];
    if (partitionType.value === 'AUTO_LIST' || partitionType.value === 'MANUAL_LIST') return ['BOOLEAN', 'TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'LARGEINT', 'DATE', 'DATETIME', 'CHAR', 'VARCHAR'];
    return [];
  }
  function partitionTypeLabel() {
    return { AUTO_RANGE: '自动范围分区', AUTO_LIST: '自动列表分区', MANUAL_RANGE: '手动范围分区', MANUAL_LIST: '手动列表分区', DYNAMIC: '动态分区' }[partitionType.value] || '分区';
  }
  function refreshSuggestions() {
    const all = fields().filter(field => field.name); const dimension = document.getElementById('dimension-fields');
    if (layer.value === 'DIM') document.getElementById('dimensionKeys').value = all.filter(field => field.key).map(field => field.name).join(',');
    dimension.replaceChildren(...all.filter(field => !field.key).map(field => { const option = document.createElement('option'); option.value = field.name; return option; }));
    const nonNullKeys = all.filter(field => field.key && !field.nullable);
    const dateCandidates = nonNullKeys.filter(field => ['DATE', 'DATETIME'].includes(parseType(field.dataType)?.kind));
    const manualRangeCandidates = nonNullKeys.filter(field => ['DATE', 'DATETIME', 'TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'LARGEINT'].includes(parseType(field.dataType)?.kind));
    const listCandidates = nonNullKeys.filter(field => ['BOOLEAN', 'TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'LARGEINT', 'DATE', 'DATETIME', 'CHAR', 'VARCHAR'].includes(parseType(field.dataType)?.kind));
    const candidates = all.filter(field => field.key && !field.nullable && partitionCandidateTypes().includes(parseType(field.dataType)?.kind));
    const selected = partitionColumn.dataset.initialValue ?? partitionColumn.value;
    delete partitionColumn.dataset.initialValue;
    partitionColumn.replaceChildren(); const blank = document.createElement('option'); blank.value = ''; blank.textContent = '请选择分区键列'; partitionColumn.append(blank);
    candidates.forEach(field => { const option = document.createElement('option'); option.value = field.name; option.textContent = `${field.name} · ${field.dataType}`; partitionColumn.append(option); });
    const valid = candidates.some(field => field.name === selected); partitionColumn.value = valid ? selected : '';
    const requiresColumn = partitionType.value !== 'NONE';
    partitionColumn.setCustomValidity(requiresColumn && !valid ? `请选择 ${partitionTypeLabel()}可用的非空键列。` : '');
    ['AUTO_RANGE', 'AUTO_LIST', 'MANUAL_RANGE', 'MANUAL_LIST', 'DYNAMIC'].forEach(value => {
      const option = partitionType.querySelector(`option[value="${value}"]`);
      if (option) option.disabled = layer.value === 'DIM' || !all.some(field => field.key && !field.nullable && ({ AUTO_RANGE: ['DATE', 'DATETIME'], AUTO_LIST: ['BOOLEAN', 'TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'LARGEINT', 'DATE', 'DATETIME', 'CHAR', 'VARCHAR'], MANUAL_RANGE: ['DATE', 'DATETIME', 'TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'LARGEINT'], MANUAL_LIST: ['BOOLEAN', 'TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'LARGEINT', 'DATE', 'DATETIME', 'CHAR', 'VARCHAR'], DYNAMIC: ['DATE', 'DATETIME'] }[value]).includes(parseType(field.dataType)?.kind));
    });
    document.querySelector('[data-partition-help]').textContent = candidates.length ? `仅显示 ${partitionTypeLabel()}可用的非空键列。` : '没有当前分区方式可用的键列：请返回第二步调整字段类型、键列和非空设置。';
    const dateFields = all.filter(field => ['DATE', 'DATETIME'].includes(parseType(field.dataType)?.kind));
    const missingKeys = dateFields.filter(field => !field.key);
    const names = list => list.slice(0, 3).map(field => `${field.name}（${field.dataType}）`).join('、') + (list.length > 3 ? ` 等 ${list.length} 个字段` : '');
    let availability;
    if (layer.value === 'DIM') availability = 'DIM/SCD1 不分区，按业务键保留唯一当前值。';
    else if (partitionType.value === 'NONE' && dateCandidates.length) availability = `可从 ${dateCandidates.length} 个日期键列选择自动范围、动态或手动范围分区；共 ${listCandidates.length} 个键列可用于列表分区。`;
    else if (partitionType.value === 'NONE' && manualRangeCandidates.length) availability = `有 ${manualRangeCandidates.length} 个整数键列可用于手动范围分区，且 ${listCandidates.length} 个键列可用于自动或手动列表分区。`;
    else if (partitionType.value === 'NONE' && listCandidates.length) availability = `有 ${listCandidates.length} 个键列可用于自动或手动列表分区。`;
    else if (candidates.length) availability = `可从 ${candidates.length} 个键列中选择${partitionTypeLabel()}字段。`;
    else if (missingKeys.length) availability = `已添加日期字段 ${names(missingKeys)}，但尚未勾选“键列”，因此无法启用日期分区。确认字段属于业务键后，请在第二步勾选“键列”，并移到所有非键字段前面。`;
    else if (dateFields.length) availability = `日期字段 ${names(dateFields)} 必须是不可空的键列，才能启用日期分区。`;
    else availability = '尚无可用于分区的非空键列。自动范围/动态需要 DATE/DATETIME；手动范围另支持整数；自动或手动列表另支持 BOOLEAN、整数、CHAR/VARCHAR。';
    if (layer.value !== 'DIM' && modelType.value === 'UNIQUE' && missingKeys.length && !candidates.length)
      availability += ' UNIQUE KEY 加入时间键会改变记录唯一性，请使用稳定的业务日期，避免仅为分区加入可变的更新时间。';
    document.querySelector('[data-partition-availability]').textContent = availability;
    document.querySelector('[data-partition-fields-action]').hidden = layer.value === 'DIM' || candidates.length > 0 || partitionType.value === 'NONE';
  }
  function updateDimension(changed) {
    const enabled = layer.value === 'DIM'; dimensionSettings.hidden = !enabled;
    dimensionSettings.querySelectorAll('input, select').forEach(input => { input.disabled = !enabled; }); document.getElementById('dimensionKeys').required = enabled;
    document.getElementById('tableName').placeholder = enabled ? 'dim_enterprise' : 'ods_orders'; modelType.querySelectorAll('option:not([value="UNIQUE"])').forEach(option => { option.disabled = enabled; });
    if (enabled && changed) { modelType.value = 'UNIQUE'; partitionType.value = 'NONE'; }
  }
  function aggregationOptions(field) {
    const numeric = ['TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'LARGEINT', 'FLOAT', 'DOUBLE', 'DECIMAL'].includes(parseType(field.dataType)?.kind);
    return [['', '请选择聚合函数', true], ['SUM', 'SUM', numeric], ['MIN', 'MIN', true], ['MAX', 'MAX', true], ['REPLACE', 'REPLACE', true], ['REPLACE_IF_NOT_NULL', 'REPLACE_IF_NOT_NULL', field.nullable]];
  }
  function updateAggregationRules() {
    document.querySelector('[data-aggregate-settings]').hidden = modelType.value !== 'AGGREGATE';
    const all = fields();
    if (modelType.value !== 'AGGREGATE') all.forEach(field => { field.aggregation.value = ''; });
    const nonKeys = all.filter(field => !field.key); const aggregateOption = modelType.querySelector('option[value="AGGREGATE"]'); aggregateOption.disabled = layer.value === 'DIM' || nonKeys.length === 0;
    aggregationRules.replaceChildren(); aggregationEmpty.hidden = nonKeys.length > 0;
    nonKeys.forEach(field => {
      const row = document.createElement('tr'); const name = document.createElement('td'); name.textContent = field.name || `第 ${field.index + 1} 个字段`; const type = document.createElement('td'); type.textContent = field.dataType; const rule = document.createElement('td'); const select = document.createElement('select');
      select.className = 'form-control form-control-sm'; select.dataset.aggregateIndex = String(field.index); select.setAttribute('aria-label', `${field.name || '字段'}的聚合函数`);
      aggregationOptions(field).forEach(([value, text, allowed]) => { const option = document.createElement('option'); option.value = value; option.textContent = text; option.disabled = !allowed; select.append(option); });
      if ([...select.options].some(option => !option.disabled && option.value === field.aggregation.value)) select.value = field.aggregation.value; else field.aggregation.value = '';
      select.required = modelType.value === 'AGGREGATE'; select.disabled = modelType.value !== 'AGGREGATE'; rule.append(select); row.append(name, type, rule); aggregationRules.append(row);
    });
  }
  function updateLayerHint() {
    const hints = { ODS: 'ODS：回放或幂等落表建议 UNIQUE；仅独立追加的原始事件可使用 DUPLICATE。', DIM: 'DIM：使用 SCD1 当前值模型，固定 UNIQUE KEY 且不分区。', DWD: 'DWD：明细加工建议 UNIQUE；只追加且需要保留事件可使用 DUPLICATE。', DWS: 'DWS：重算快照建议 UNIQUE；增量汇总可用 AGGREGATE。注意 SUM 重放会重复累加。', ADS: 'ADS：重算快照建议 UNIQUE；增量汇总可用 AGGREGATE。注意 SUM 重放会重复累加。' };
    const shapeHint = layer.value !== 'DIM' && !fields().some(field => !field.key) ? ' 当前没有非键字段，暂不可选择 AGGREGATE；可返回第二步添加指标字段。' : '';
    const writeHint = modelType.value !== 'UNIQUE' ? ' 当前自动同步和重试加工要求 UNIQUE；此模型需独立管理写入与重算。' : '';
    document.querySelector('[data-layer-model-hint]').textContent = (hints[layer.value] || '请根据写入语义选择 Doris 数据模型。') + shapeHint + writeHint;
  }
  function distributionSelection() {
    return distributionColumns.value.split(',').map(value => value.trim()).filter(Boolean);
  }
  function updateDistributionSettings() {
    const all = fields().filter(field => field.name);
    const duplicate = modelType.value === 'DUPLICATE';
    const randomOption = distributionType.querySelector('option[value="RANDOM"]');
    randomOption.disabled = !duplicate;
    if (!duplicate && distributionType.value === 'RANDOM') distributionType.value = 'HASH';
    const random = distributionType.value === 'RANDOM';
    const candidates = (duplicate ? all : all.filter(field => field.key)).filter(field => !['FLOAT', 'DOUBLE'].includes(parseType(field.dataType)?.kind));
    const selected = new Set(distributionSelection().map(value => value.toLowerCase()));
    const valid = [...selected].map(name => candidates.find(field => field.name.toLowerCase() === name)).filter(Boolean);
    const choices = defaultDistributionKeys ? candidates.filter(field => field.key) : valid;
    distributionKeyList.replaceChildren();
    candidates.forEach(field => {
      const label = document.createElement('label'); label.className = 'd-flex align-items-center gap-2 mb-2';
      const input = document.createElement('input'); input.className = 'check m-0'; input.type = 'checkbox'; input.value = field.name; input.checked = choices.some(item => item.name.toLowerCase() === field.name.toLowerCase());
      input.dataset.distributionKey = 'true'; input.setAttribute('aria-label', `${field.name} 分桶键`);
      const text = document.createElement('span'); text.textContent = `${field.name} · ${field.dataType}${field.key ? '（键列）' : ''}`;
      label.append(input, text); distributionKeyList.append(label);
    });
    distributionKeys.hidden = random;
    distributionKeyList.querySelectorAll('input').forEach(input => { input.disabled = random; });
    distributionColumns.value = random ? '' : choices.map(field => field.name).join(',');
    distributionColumns.setCustomValidity(!random && choices.length === 0 ? 'HASH 分桶至少需要一个分桶键。' : '');
    distributionTypeHelp.textContent = random ? 'RANDOM 仅支持 DUPLICATE KEY，数据随机均匀分布，不能按分桶键裁剪。' : 'HASH 按分桶键计算，查询等值过滤可利用分桶裁剪。';
    distributionKeyHelp.textContent = random ? '' : candidates.length ? (duplicate ? 'DUPLICATE KEY 可选择键列或非键列（不支持 FLOAT / DOUBLE）；至少选择一个。' : 'UNIQUE / AGGREGATE KEY 只能选择键列；至少选择一个。') : '请返回第二步维护可用的键列。';
    const automaticBucketsUnsupported = partitionType.value === 'AUTO_LIST';
    if (automaticBucketsUnsupported) autoBuckets.checked = false;
    autoBuckets.disabled = automaticBucketsUnsupported;
    bucketCount.disabled = autoBuckets.checked;
    bucketCount.required = !autoBuckets.checked;
    bucketHelp.textContent = automaticBucketsUnsupported ? '自动列表分区不支持 AUTO 分桶，请指定固定分桶数。' : autoBuckets.checked ? 'Doris 会在建表或创建新分区时推算桶数，不会重分桶已有数据。' : '手动指定 1 到 2147483647 的整数；建表后既有分区不可修改。';
  }
  function partitionRows() { return partitionType.value === 'MANUAL_RANGE' ? [...manualRangeList.rows] : partitionType.value === 'MANUAL_LIST' ? [...manualListList.rows] : []; }
  function renumberPartitionRows() {
    const prefix = partitionType.value === 'MANUAL_RANGE' ? 'manual-range' : 'manual-list';
    partitionRows().forEach((row, index) => row.querySelectorAll('[data-partition-property]').forEach(input => { input.name = `partitions[${index}].${input.dataset.partitionProperty}`; input.id = `${prefix}-${index}-${input.dataset.partitionProperty}`; }));
    const count = partitionRows().length;
    form.querySelectorAll('[data-add-partition]').forEach(button => { button.disabled = count >= 100; });
  }
  function addPartitionRow(kind) {
    const list = kind === 'range' ? manualRangeList : manualListList;
    if (list.rows.length >= 100) return;
    const row = document.createElement('tr'); row.dataset[`manual${kind === 'range' ? 'Range' : 'List'}Row`] = '';
    const name = document.createElement('input'); name.className = 'form-control form-control-sm'; name.dataset.partitionProperty = 'name'; name.maxLength = 64; name.setAttribute('aria-label', '分区名');
    const nameCell = document.createElement('td'); nameCell.append(name); row.append(nameCell);
    if (kind === 'range') {
      const lowerCell = document.createElement('td'); lowerCell.dataset.rangeLowerCell = ''; const lower = document.createElement('input'); lower.className = 'form-control form-control-sm'; lower.dataset.partitionProperty = 'lowerBound'; lower.maxLength = 128; lower.setAttribute('aria-label', '下界'); lowerCell.append(lower); row.append(lowerCell);
      const upperCell = document.createElement('td'); const upper = document.createElement('input'); upper.className = 'form-control form-control-sm'; upper.dataset.partitionProperty = 'upperBound'; upper.maxLength = 128; upper.setAttribute('aria-label', '上界'); upperCell.append(upper); row.append(upperCell);
    } else {
      const valuesCell = document.createElement('td'); const values = document.createElement('textarea'); values.className = 'form-control form-control-sm'; values.dataset.partitionProperty = 'valuesText'; values.rows = 3; values.maxLength = 4000; values.setAttribute('aria-label', '分区值，每行一个'); valuesCell.append(values); row.append(valuesCell);
    }
    const action = document.createElement('td'); const remove = document.createElement('button'); remove.className = 'btn-outline theme-danger btn-sm'; remove.type = 'button'; remove.dataset.removePartition = ''; remove.setAttribute('aria-label', '移除分区'); remove.textContent = '移除'; action.append(remove); row.append(action); list.append(row); updateManualRangeStyle(); renumberPartitionRows(); name.focus();
  }
  function updateManualRangeStyle() {
    const lessThan = rangeStyle.value === 'LESS_THAN';
    manualRangeSettings.querySelector('[data-range-lower-heading]').hidden = lessThan;
    manualRangeList.querySelectorAll('[data-range-lower-cell]').forEach(cell => { cell.hidden = lessThan; cell.querySelector('input').disabled = lessThan || partitionType.value !== 'MANUAL_RANGE'; });
  }
  function updatePartitionSettings() {
    const type = partitionType.value;
    const needsColumn = type !== 'NONE';
    partitionColumnSetting.hidden = !needsColumn;
    partitionColumn.disabled = !needsColumn;
    partitionColumn.required = needsColumn;
    const autoRange = type === 'AUTO_RANGE';
    partitionUnitSetting.hidden = !autoRange;
    partitionUnit.disabled = !autoRange;
    partitionUnit.required = autoRange;
    manualRangeSettings.hidden = type !== 'MANUAL_RANGE';
    manualListSettings.hidden = type !== 'MANUAL_LIST';
    dynamicPartitionSettings.hidden = type !== 'DYNAMIC';
    manualRangeSettings.querySelectorAll('input, select, textarea, button').forEach(input => { input.disabled = type !== 'MANUAL_RANGE'; });
    manualListSettings.querySelectorAll('input, select, textarea, button').forEach(input => { input.disabled = type !== 'MANUAL_LIST'; });
    dynamicPartitionSettings.querySelectorAll('textarea').forEach(input => { input.disabled = type !== 'DYNAMIC'; });
    updateManualRangeStyle(); renumberPartitionRows();
  }
  function updatePhysicalSettings() {
    updateFieldConstraints();
    updatePartitionSettings();
    const fieldHints = { ODS: 'ODS 请与源表字段对应，使用稳定业务键支持回放去重。', DIM: 'DIM 的键列对应维度业务键，用于维护成员当前值。', DWD: 'DWD 按明细粒度确定业务键；事件明细可在第三步选择保留重复记录。', DWS: 'DWS 请先确定汇总粒度，键列组成指标的分组维度。', ADS: 'ADS 按报表查询粒度定义键列，区分重算快照与增量指标。' };
    guidance.textContent = '键列须连续排在前面且不可空。' + (fieldHints[layer.value] || '') + 'Doris 数据模型和聚合规则在第三步选择。';
    updateLayerHint(); refreshSuggestions(); updateAggregationRules(); updateDistributionSettings();
  }
  function updateWizardSummary() { const tableName = document.getElementById('tableName').value.trim() || '未命名表'; form.querySelector('[data-step-summary="1"]').textContent = `${tableName} / ${layer.value || '未选择分层'}`; const all = fields(); form.querySelector('[data-step-summary="2"]').textContent = `${all.length} 个字段 / ${all.filter(field => field.key).length} 个键列`; }
  function renumber() {
    [...body.rows].forEach((row, index) => row.querySelectorAll('[data-property]').forEach(input => { input.name = `fields[${index}].${input.dataset.property}`; input.id = `field-${index}-${input.dataset.property}`; }));
    body.querySelectorAll('[data-type-picker]').forEach(initializeTypePicker); add.disabled = body.rows.length >= 100; updatePhysicalSettings(); updateWizardSummary();
  }
  function setNotice(message) { wizardNotice.textContent = message || ''; wizardNotice.hidden = !message; }
  function focusInvalid(element) { element.focus({ preventScroll: true }); element.scrollIntoView({ block: 'nearest', behavior: 'instant' }); }
  function validateControls(step) {
    const inputs = [...form.querySelectorAll(`[data-wizard-step="${step}"] input, [data-wizard-step="${step}"] select, [data-wizard-step="${step}"] textarea`)].filter(control => !control.disabled && control.type !== 'hidden'); const invalid = inputs.find(control => !control.checkValidity());
    if (!invalid) return true; invalid.reportValidity(); focusInvalid(invalid); return false;
  }
  function validateFields() {
    const all = fields(); if (!all.length) { setNotice('请至少维护一个字段。'); add.focus(); return false; }
    if (!all.some(field => field.key)) { setNotice('请至少选择一个键列。'); focusInvalid(all[0].row.querySelector('[data-property="keyColumn"]')); return false; }
    const names = new Set(); let afterNonKey = false;
    for (const field of all) {
      if (!field.name) { setNotice('字段名不能为空。'); focusInvalid(field.row.querySelector('[data-property="name"]')); return false; }
      if (names.has(field.name.toLowerCase())) { setNotice(`字段名“${field.name}”重复。`); focusInvalid(field.row.querySelector('[data-property="name"]')); return false; }
      names.add(field.name.toLowerCase()); const parsed = parseType(field.dataType);
      if (!isValidType(parsed)) { setNotice(`字段“${field.name}”的数据类型无效。`); focusInvalid(field.row.querySelector('[data-property="dataType"]')); return false; }
      if (!field.key) afterNonKey = true;
      if (field.key && afterNonKey) { setNotice(`字段“${field.name}”已设为键列，但位于非键字段之后；请用“上移字段”移到所有非键字段前面。`); focusInvalid(field.row.querySelector('[data-property="keyColumn"]')); return false; }
      if (field.key && field.nullable) { setNotice(`键列“${field.name}”不能设置为可空。`); focusInvalid(field.row.querySelector('[data-property="nullable"]')); return false; }
      if (field.key && ['FLOAT', 'DOUBLE', 'STRING'].includes(parsed?.kind)) { setNotice(`键列“${field.name}”不支持 ${parsed.kind} 类型。`); focusInvalid(field.row.querySelector('[data-property="dataType"]')); return false; }
    } return true;
  }
  function validatePhysical() {
    updatePhysicalSettings();
    if (modelType.value === 'AGGREGATE') { const rules = [...aggregationRules.querySelectorAll('[data-aggregate-index]')]; if (!rules.length) { setNotice('AGGREGATE KEY 至少需要一个非键字段。'); focusInvalid(modelType); return false; } const missing = rules.find(select => !select.value); if (missing) { setNotice('请为每个非键字段选择聚合函数。'); focusInvalid(missing); return false; } }
    if (partitionType.value !== 'NONE' && !partitionColumn.value) { setNotice(`${partitionTypeLabel()}需要可用的非空键列；请返回第二步维护字段。`); focusInvalid(partitionColumn); return false; }
    if (partitionType.value === 'MANUAL_RANGE' || partitionType.value === 'MANUAL_LIST') {
      const rows = partitionRows();
      if (!rows.length) { setNotice(`${partitionTypeLabel()}至少需要添加一个分区定义。`); const button = form.querySelector(`[data-add-partition="${partitionType.value === 'MANUAL_RANGE' ? 'range' : 'list'}"]`); focusInvalid(button); return false; }
      const missing = rows.find(row => [...row.querySelectorAll('[data-partition-property]')].filter(input => !input.disabled).some(input => !input.value.trim()));
      if (missing) { setNotice('请完整填写每个手动分区的名称和边界或值。'); focusInvalid(missing.querySelector('[data-partition-property]')); return false; }
    }
    if (partitionType.value === 'DYNAMIC') {
      const properties = document.querySelector('[data-dynamic-partition-properties]');
      if (!properties.value.trim()) { setNotice('动态分区需要填写完整 PROPERTIES(...) 配置。'); focusInvalid(properties); return false; }
    }
    if (distributionType.value === 'HASH' && !distributionSelection().length) { setNotice('HASH 分桶至少需要选择一个分桶键。'); focusInvalid(distributionKeyList); return false; }
    return validateControls(3);
  }
  function validateStep(step) { setNotice(''); if (step === 1) return validateControls(1); if (step === 2) return validateControls(2) && validateFields(); return validatePhysical(); }
  function showStep(step) {
    activeStep = step; wizardPanels.forEach(panel => { panel.hidden = Number(panel.dataset.wizardStep) !== step; }); wizardSteps.forEach(button => { const target = Number(button.dataset.wizardTarget); button.setAttribute('aria-current', target === step ? 'step' : 'false'); button.classList.toggle('is-complete', target < step); }); form.querySelectorAll('[data-wizard-current]').forEach(element => { element.hidden = Number(element.dataset.wizardCurrent) !== step; }); setNotice('');
  }
  function attemptStep(target) {
    if (target === activeStep) return;
    if (target > activeStep) { for (let step = activeStep; step < target; step += 1) { showStep(step); if (!validateStep(step)) return; } }
    showStep(target);
    const panel = wizardPanels.find(item => Number(item.dataset.wizardStep) === target);
    panel.tabIndex = -1;
    panel.setAttribute('aria-label', ['', '第一步：模型信息', '第二步：字段定义', '第三步：Doris 物理设置'][target]);
    panel.focus({ preventScroll: true });
    const panelTop = panel.getBoundingClientRect().top;
    const topbarBottom = document.querySelector('.topbar')?.getBoundingClientRect().bottom || 0;
    // Keep the viewport steady when using the visible step tabs. Bottom controls may need to reveal the new panel.
    if (panelTop < topbarBottom || panelTop >= window.innerHeight) panel.scrollIntoView({ block: 'start', behavior: 'instant' });
  }

  layer.addEventListener('change', () => { updateDimension(true); updatePhysicalSettings(); updateWizardSummary(); }); modelType.addEventListener('change', updatePhysicalSettings); partitionType.addEventListener('change', updatePhysicalSettings); partitionColumn.addEventListener('change', () => partitionColumn.setCustomValidity('')); rangeStyle.addEventListener('change', updateManualRangeStyle);
  distributionType.addEventListener('change', () => {
    if (distributionType.value === 'HASH' && !distributionSelection().length) defaultDistributionKeys = true;
    updatePhysicalSettings();
  }); autoBuckets.addEventListener('change', updateDistributionSettings);
  distributionKeyList.addEventListener('change', event => {
    const input = event.target;
    if (!input.matches('[data-distribution-key]')) return;
    defaultDistributionKeys = false;
    const selected = distributionSelection().filter(value => value.toLowerCase() !== input.value.toLowerCase());
    if (input.checked) selected.push(input.value);
    distributionColumns.value = selected.join(',');
    distributionColumns.setCustomValidity(selected.length ? '' : 'HASH 分桶至少需要一个分桶键。');
  });
  document.getElementById('partition-fields-action').addEventListener('click', () => {
    attemptStep(2);
    const target = fields().find(field => partitionCandidateTypes().includes(parseType(field.dataType)?.kind) && !field.key);
    focusInvalid(target ? target.row.querySelector('[data-property="keyColumn"]') : add);
  });
  form.querySelectorAll('[data-add-partition]').forEach(button => button.addEventListener('click', () => addPartitionRow(button.dataset.addPartition)));
  [manualRangeList, manualListList].forEach(list => list.addEventListener('click', event => { const button = event.target.closest('[data-remove-partition]'); if (!button) return; button.closest('tr').remove(); renumberPartitionRows(); }));
  aggregationRules.addEventListener('change', event => { const index = Number(event.target.dataset.aggregateIndex); if (!Number.isInteger(index)) return; const hidden = body.rows[index]?.querySelector('[data-property="aggregation"]'); if (hidden) hidden.value = event.target.value; });
  body.addEventListener('input', event => { const picker = event.target.closest('[data-type-picker]'); if (picker && event.target.matches('[data-type-length], [data-type-precision], [data-type-scale], [data-type-datetime-precision]')) synchronizeType(picker); updatePhysicalSettings(); });
  body.addEventListener('change', event => { const picker = event.target.closest('[data-type-picker]'); if (picker && event.target.matches('[data-type-select]')) selectType(picker); if (picker && event.target.matches('[data-type-length], [data-type-precision], [data-type-scale], [data-type-datetime-precision]')) synchronizeType(picker); updatePhysicalSettings(); });
  add.addEventListener('click', () => { if (body.rows.length >= 100) return; body.append(document.getElementById('field-template').content.cloneNode(true)); const typeSelect = body.lastElementChild.querySelector('[data-type-select]'); typeSelect.selectedIndex = [...typeSelect.options].indexOf(selectedOptionForKind(typeSelect, 'VARCHAR')); renumber(); focusInvalid(body.lastElementChild.querySelector('input')); });
  body.addEventListener('click', event => { const button = event.target.closest('button'); if (!button) return; const row = button.closest('tr'); if (button.hasAttribute('data-remove')) row.remove(); if (button.dataset.move === 'up' && row.previousElementSibling) body.insertBefore(row, row.previousElementSibling); if (button.dataset.move === 'down' && row.nextElementSibling) body.insertBefore(row.nextElementSibling, row); renumber(); });
  form.querySelectorAll('[data-wizard-next]').forEach(button => button.addEventListener('click', () => attemptStep(Number(button.dataset.wizardNext)))); form.querySelectorAll('[data-wizard-back]').forEach(button => button.addEventListener('click', () => attemptStep(Number(button.dataset.wizardBack)))); wizardSteps.forEach(button => button.addEventListener('click', () => attemptStep(Number(button.dataset.wizardTarget))));
  form.addEventListener('input', event => { if (event.target.closest('[data-wizard-step="1"]')) updateWizardSummary(); });
  form.addEventListener('submit', event => {
    if (activeStep < 3) { event.preventDefault(); attemptStep(activeStep + 1); return; }
    for (let step = 1; step <= 3; step += 1) { showStep(step); if (!validateStep(step)) { event.preventDefault(); return; } }
  });
  form.noValidate = true; form.classList.add('model-wizard-enhanced'); updateDimension(false); renumber(); const errorStep = Number(form.dataset.errorStep); showStep([1, 2, 3].includes(errorStep) ? errorStep : 1);
})();
