(() => {
  const root = document.querySelector('[data-pipeline-ods-form]'), form = root?.querySelector('[data-pipeline-form]');
  if (!form) return;
  const one = selector => form.querySelector(selector), all = selector => [...form.querySelectorAll(selector)];
  const source = one('[data-source-id]'), sourceTable = one('[data-source-table]'), tableSelect = one('[data-source-table-select]'), tableStatus = one('[data-source-table-status]'), manualButton = one('[data-source-table-manual]'), manualInput = sourceTable;
  const modes = all('[data-target-mode]'), autoTarget = one('[data-auto-target]'), existingTarget = one('[data-existing-target]'), autoInput = one('[data-auto-target-input]'), existingInput = one('[data-existing-target-input]'), autoSettings = one('[data-auto-settings]'), existingPhysical = one('[data-existing-physical]'), autoPreview = one('[data-auto-preview-content]'), existingPreview = one('[data-existing-preview]');
  const previewHash = one('[data-preview-hash]'), confirmed = one('[data-preview-confirm]'), create = one('[data-create-button]'), bucketsAuto = one('[data-auto-buckets]'), buckets = one('[data-fixed-buckets]'), distribution = one('[data-distribution-columns]'), distributionValue = one('[data-distribution-columns-value]'), partitionType = one('[data-partition-type]'), partitionColumn = one('[data-partition-column]');
  const wizardPanels = all('[data-wizard-step]'), wizardButtons = all('[data-wizard-target]'), wizardInput = one('[data-wizard-step-input]'), notice = one('[data-wizard-notice]');
  let request = 0, activeStep = Number(form.dataset.initialStep) || 1, hasFields = all('[data-key-column]').length > 0;
  const mode = () => modes.find(item => item.checked)?.value || 'EXISTING';
  const automatic = () => modes.length > 0 && mode() === 'AUTO';
  const visibleSteps = () => automatic() ? [1, 2, 3, 4] : [1, 4];
  const hidden = (element, value) => { if (element) element.hidden = value; };
  const setNotice = text => { if (notice) { notice.textContent = text || ''; notice.hidden = !text; } };
  const candidates = () => all('[data-key-column]:checked').map(input => { const cells = input.closest('tr')?.querySelectorAll('td') || []; return { name: cells[2]?.textContent.trim() || input.dataset.targetColumn, type: (cells[3]?.textContent.trim() || input.dataset.targetType || '').toUpperCase() }; }).filter(item => item.name && !/^(STRING|FLOAT|DOUBLE)$/.test(item.type));
  const invalidate = ({ keys = false } = {}) => { if (!previewHash) return; previewHash.value = ''; if (confirmed) confirmed.checked = false; if (keys) { hasFields = false; all('[data-key-column]').forEach(item => item.checked = false); distributionValue.value=''; partitionColumn.value=''; delete partitionColumn.dataset.initialValue; partitionType.value='NONE'; hidden(one('[data-auto-preview-content] .table-responsive'), true); refreshKeys(); refreshPartition(); } hidden(one('[data-ddl-preview]'), true); hidden(one('[data-preview-stale]'), !automatic()); updateCreate(); };
  const updateCreate = () => { if (create && modes.length) create.disabled = automatic() && (!previewHash?.value || !confirmed?.checked || confirmed?.disabled); };
  const updateBuckets = () => { if (buckets) buckets.disabled = !automatic() || Boolean(bucketsAuto?.checked); };
  const setEnabled = (container, enabled) => { if (container) container.querySelectorAll('input,select,textarea').forEach(control => { if (control.dataset.keepDisabled !== 'true') control.disabled = !enabled; }); };
  const refreshPartition = () => {
    const value = partitionType?.value || 'NONE', partitioned = value !== 'NONE';
    const range = one('[data-manual-range-wrap]'), list = one('[data-manual-list-wrap]'), dynamic = one('[data-dynamic-partition-wrap]'); hidden(one('[data-partition-column-wrap]'), !partitioned); hidden(one('[data-partition-unit-wrap]'), value !== 'AUTO_RANGE'); one('#partitionUnit').disabled = !automatic() || value !== 'AUTO_RANGE'; hidden(one('[data-range-style-wrap]'), value !== 'MANUAL_RANGE'); hidden(range, value !== 'MANUAL_RANGE'); hidden(list, value !== 'MANUAL_LIST'); hidden(dynamic, value !== 'DYNAMIC'); setEnabled(range, automatic() && value === 'MANUAL_RANGE'); setEnabled(list, automatic() && value === 'MANUAL_LIST'); setEnabled(dynamic, automatic() && value === 'DYNAMIC');
    if (partitionColumn) partitionColumn.required = partitioned;
    const lessThan = one('#partitionRangeStyle')?.value === 'LESS_THAN'; all('[data-partition-property="lowerBound"]').forEach(input => { input.disabled = value !== 'MANUAL_RANGE' || lessThan; input.closest('td').hidden = lessThan; });
    hidden(one('[data-range-lower-heading]'), lessThan);
    if (bucketsAuto && value === 'AUTO_LIST') { bucketsAuto.checked = false; bucketsAuto.disabled = true; } else if (bucketsAuto) bucketsAuto.disabled = !automatic();
    updateBuckets();
  };
  const refreshKeys = () => {
    if (!distribution || !partitionColumn) return;
    const chosen = new Set((distributionValue?.value || '').split(',').map(value => value.trim()).filter(Boolean)), partition = partitionColumn.dataset.initialValue ?? partitionColumn.value;
    delete partitionColumn.dataset.initialValue;
    distribution.replaceChildren(); const defaults = new Option('全部键列（默认）', ''); defaults.selected = chosen.size === 0; distribution.add(defaults);
    partitionColumn.replaceChildren(new Option('请选择分区字段', ''));
    const partitionMode = partitionType?.value || 'NONE';
    candidates().forEach(column => { const option = new Option(`${column.name} · ${column.type}`, column.name); option.selected = chosen.has(column.name); distribution.add(option); const date = /^(DATE|DATETIME(?:\([0-6]\))?)$/.test(column.type); const range = /^(TINYINT|SMALLINT|INT|BIGINT|LARGEINT)$/.test(column.type) || date; const list = range || /^(CHAR|VARCHAR)\(/.test(column.type); if ((['AUTO_RANGE','DYNAMIC'].includes(partitionMode) && date) || (partitionMode === 'MANUAL_RANGE' && range) || (/LIST/.test(partitionMode) && list)) partitionColumn.add(new Option(`${column.name} · ${column.type}`, column.name)); });
    partitionColumn.value = [...partitionColumn.options].some(option => option.value === partition) ? partition : '';
    distributionValue.value = [...distribution.selectedOptions].map(option=>option.value).filter(Boolean).join(',');
    const columnType=candidates().find(column=>column.name===partitionColumn.value)?.type;
    const unit=one('#partitionUnit');
    [...unit.options].forEach(option=>{ option.disabled=columnType==='DATE' && ['HOUR','MINUTE','SECOND'].includes(option.value); });
    if(unit.selectedOptions[0]?.disabled)unit.value='DAY';
  };
  const updateMode = () => { const auto = automatic(); hidden(autoTarget, !auto); hidden(existingTarget, auto); all('[data-auto-settings]').forEach(card => hidden(card,!auto)); hidden(existingPhysical, auto); hidden(autoPreview, !auto); hidden(existingPreview, auto); hidden(one('[data-ddl-preview]'), !auto || !previewHash.value); hidden(one('[data-preview-stale]'), !auto || Boolean(previewHash.value)); all('[data-preview-button]').forEach(button=>hidden(button,!auto)); if (!auto && confirmed) confirmed.checked = false; if (autoInput) autoInput.disabled = !auto; if (existingInput) existingInput.disabled = auto || modes.length===0; [bucketsAuto, buckets, distribution, partitionType, partitionColumn].filter(Boolean).forEach(item => item.disabled = !auto); if(create)create.textContent=modes.length===0?'保存修改':auto?'创建表、登记模型并创建管道':'创建管道'; refreshPartition(); updateCreate(); };
  const validStep = step => {
    if (step === 1) { const required = [one('#name'), source, sourceTable, ...(!automatic() ? [one('[data-watermark]'),existingInput] : [])].filter(Boolean); const bad = required.find(control => !control.disabled && (!control.value.trim() || !control.checkValidity())); if (bad) { setNotice('请先完成来源与 ODS 目标。'); if(!bad.hidden)bad.focus(); return false; } return true; }
    if (!automatic()) return true;
    if(step===2 && (!hasFields || !all('[data-key-column]:checked').length)) { setNotice(hasFields?'请选择共同唯一且稳定的非空业务键。':'请先读取当前来源表的结构。'); return false; }
    const controls = all(`[data-wizard-step="${step}"] input, [data-wizard-step="${step}"] select, [data-wizard-step="${step}"] textarea`).filter(control => !control.disabled && !control.hidden && control.type !== 'hidden');
    const bad = controls.find(control => !control.checkValidity());
    if (bad) { setNotice('请先完成当前步骤的必填项。'); bad.focus(); return false; }
    if (step === 3 && partitionType?.value !== 'NONE' && !partitionColumn?.value) { setNotice('请选择当前分区方式可用的非空键列。'); return false; }
    return true;
  };
  const showStep = requested => {
    const steps = visibleSteps();
    activeStep = steps.includes(requested) ? requested : steps[0];
    if (wizardInput) wizardInput.value = String(activeStep);
    wizardPanels.forEach(panel => { panel.hidden = Number(panel.dataset.wizardStep) !== activeStep; });
    wizardButtons.forEach(button => {
      const target = Number(button.dataset.wizardTarget), index = steps.indexOf(target);
      button.setAttribute('aria-current', target === activeStep ? 'step' : 'false');
      button.classList.toggle('is-complete', index >= 0 && index < steps.indexOf(activeStep));
    });
    setNotice(''); window.scrollTo({ top: 0, behavior: 'smooth' });
  };
  const renumber = selector => all(selector).forEach((row, index) => row.querySelectorAll('[data-partition-property]').forEach(input => input.name = `partitions[${index}].${input.dataset.partitionProperty}`));
  const addPartition = type => { const template = document.getElementById(type === 'range' ? 'manual-range-template' : 'manual-list-template'), list = one(type === 'range' ? '[data-manual-range-list]' : '[data-manual-list-list]'); if (!template || !list || list.children.length >= 100) return; list.append(template.content.cloneNode(true)); renumber(type === 'range' ? '[data-manual-range-row]' : '[data-manual-list-row]'); refreshPartition(); invalidate(); };
  const loadTables = async preserve => {
    if (!source || !tableSelect) return;
    if (source.disabled) { tableSelect.replaceChildren(new Option(sourceTable.value || '—', sourceTable.value || '')); tableSelect.value = sourceTable.value; tableSelect.disabled = true; tableSelect.required = false; tableStatus.textContent = '该管道的来源表已固定。'; return; }
    const ticket = ++request; tableSelect.disabled = true; tableSelect.replaceChildren(new Option('正在加载来源表…', '')); tableStatus.textContent = '正在读取当前数据源可访问的表…'; hidden(manualButton, true); hidden(manualInput, true);
    if (!source.value) { tableSelect.replaceChildren(new Option('请先选择数据源', '')); tableSelect.required = false; tableStatus.textContent = '选择数据源后加载当前命名空间可读取的表。'; return; }
    try { const response = await fetch(`/pipelines/source-tables?sourceId=${encodeURIComponent(source.value)}`, { headers: { Accept: 'application/json' } }); if (!response.ok) throw Error(); const tables = await response.json(); if (ticket !== request || !Array.isArray(tables)) return; tableSelect.replaceChildren(new Option(tables.length ? '请选择来源表' : '当前数据源没有可读取的表', '')); tables.forEach(table => tableSelect.add(new Option(table.comment ? `${table.name} · ${table.comment}` : table.name, table.name))); tableSelect.value = preserve ? sourceTable.value : ''; tableSelect.disabled = false; tableStatus.textContent = tables.length ? '来源表随数据源联动加载；预览时会再次读取实际结构。' : '未发现可读取的表，可手动填写表名。'; hidden(manualButton, Boolean(tables.length)); if (!tables.length) { hidden(manualInput, false); manualInput.value = sourceTable.value; } }
    catch (_) { if (ticket !== request) return; tableSelect.replaceChildren(new Option('表清单加载失败', '')); tableStatus.textContent = '无法加载表清单，可手动填写；预览时仍会核对实际结构。'; tableSelect.disabled = true; tableSelect.required = false; hidden(manualButton, false); }
  };
  manualButton?.addEventListener('click', () => { hidden(manualInput, false); manualInput.required = true; manualInput.value = sourceTable.value; manualInput.focus(); });
  manualInput?.addEventListener('input', () => { sourceTable.value = manualInput.value.trim(); invalidate({ keys: true, hidePreview: true }); });
  tableSelect?.addEventListener('change', () => { sourceTable.value = tableSelect.value; invalidate({ keys: true, hidePreview: true }); });
  source?.addEventListener('change', () => { sourceTable.value = ''; if (autoInput) autoInput.value = ''; invalidate({ keys: true, hidePreview: true }); loadTables(false); });
  modes.forEach(item => item.addEventListener('change', () => { invalidate({ hidePreview: true }); updateMode(); }));
  [one('[data-watermark]'), autoInput, existingInput, bucketsAuto, buckets, distribution, partitionType, partitionColumn].filter(Boolean).forEach(item => { item.addEventListener('input', invalidate); item.addEventListener('change', () => { if (item === distribution && distributionValue) distributionValue.value = [...distribution.selectedOptions].map(option => option.value).filter(Boolean).join(','); invalidate(); refreshPartition(); refreshKeys(); }); });
  all('[data-key-column]').forEach(input => input.addEventListener('change', () => { invalidate(); refreshKeys(); })); confirmed?.addEventListener('change', updateCreate);
  all('[data-add-partition]').forEach(button => button.addEventListener('click', () => addPartition(button.dataset.addPartition))); form.addEventListener('click', event => { const button = event.target.closest('[data-remove-partition]'); if (button) { button.closest('tr')?.remove(); renumber('[data-manual-range-row]'); renumber('[data-manual-list-row]'); invalidate(); } });
  form.addEventListener('input', event => { if (event.target.closest('[data-wizard-step="3"]')) invalidate(); }); form.addEventListener('change', event => { if (event.target.closest('[data-wizard-step="3"]')) { invalidate(); refreshPartition(); refreshKeys(); } });
  all('[data-wizard-next]').forEach(button => button.addEventListener('click', () => {
    if (!validStep(activeStep)) return;
    if (activeStep === 1 && automatic() && !hasFields) {
      wizardInput.value = '2'; form.requestSubmit(one('[data-preview-button]'));
    } else {
      const steps = visibleSteps(); showStep(steps[steps.indexOf(activeStep) + 1] ?? activeStep);
    }
  }));
  all('[data-wizard-back]').forEach(button => button.addEventListener('click', () => {
    const steps = visibleSteps(); showStep(steps[steps.indexOf(activeStep) - 1] ?? steps[0]);
  }));
  wizardButtons.forEach(button => button.addEventListener('click', () => {
    const steps = visibleSteps(), target = Number(button.dataset.wizardTarget), index = steps.indexOf(target);
    if (index < 0) return;
    if (index <= steps.indexOf(activeStep) || steps.slice(0, index).every(value => validStep(value))) showStep(target);
  }));
  form.addEventListener('submit', event => { if (event.submitter?.matches('[data-preview-button]')) return; if (automatic() && create?.disabled) event.preventDefault(); });
  const firstNext=one('[data-wizard-next="2"]');
  const describeMode = () => {
    const steps = visibleSteps();
    firstNext.textContent = automatic() ? '下一步：读取来源结构' : '下一步：调度与确认';
    one('[data-wizard-progress]').dataset.stepCount = String(steps.length);
    wizardButtons.forEach(button => {
      const index = steps.indexOf(Number(button.dataset.wizardTarget));
      hidden(button.closest('li'), index < 0);
      button.disabled = index < 0;
      if (index >= 0) button.querySelector('span').textContent = String(index + 1);
    });
    one('[data-final-step-heading]').textContent = automatic() ? '第四步：调度与确认' : '第二步：调度与确认';
  };
  modes.forEach(input => input.addEventListener('change', () => { describeMode(); showStep(1); }));
  describeMode();
  if (sourceTable) sourceTable.hidden = true; form.classList.add('pipeline-wizard-enhanced'); updateMode(); refreshKeys(); loadTables(true); showStep(Math.max(1, Math.min(4, activeStep)));
})();
