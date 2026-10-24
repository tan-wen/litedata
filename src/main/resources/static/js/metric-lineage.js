(() => {
  const graph = window.metricLineage, helper = window.MetricLineageGraph;
  const svg = document.getElementById('metric-lineage-graph');
  if (!svg || !graph?.nodes?.length || !helper) return;
  const ns = 'http://www.w3.org/2000/svg';
  const panel = document.querySelector('[data-metric-lineage-selection]');
  const live = document.querySelector('[data-metric-lineage-live]');
  const clear = document.querySelector('[data-metric-lineage-clear]');
  const create = (tag, attrs, parent = svg, text) => {
    const element = document.createElementNS(ns, tag);
    Object.entries(attrs).forEach(([key, value]) => element.setAttribute(key, String(value)));
    if (text !== undefined) element.textContent = text;
    parent.appendChild(element); return element;
  };
  const safeHref = value => typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') ? value : null;
  const kind = (value, label = '') => label.endsWith('.*') && ['WAREHOUSE_FIELD', 'SOURCE_FIELD'].includes(value)
    ? (value === 'SOURCE_FIELD' ? '业务源行集' : '仓库行集')
    : ({METRIC: '指标字段', WAREHOUSE_FIELD: '仓库字段', SOURCE_FIELD: '业务源字段', BOUNDARY: '追溯边界'})[value] || value || '节点';
  const shorten = (value, max) => {
    const text = String(value || '—'); if (text.length <= max) return text;
    const suffix = text.includes('.') ? text.slice(text.lastIndexOf('.')) : '';
    if (suffix && suffix.length <= max - 5) return `${text.slice(0, max - suffix.length - 1)}…${suffix}`;
    const tail = Math.max(5, Math.floor((max - 1) / 2)); return `${text.slice(0, max - tail - 1)}…${text.slice(-tail)}`;
  };
  const caption = node => {
    const dot = node.label.lastIndexOf('.'), field = node.label.slice(dot + 1);
    if (node.kind === 'METRIC') return {heading: kind(node.kind), name: `${node.detail.split(' · ')[0]} · ${field === 'metric_value' ? '指标值' : field}`};
    const table = node.label.slice(0, dot).split('.').pop() || '';
    return {heading: `${kind(node.kind, node.label)} · ${table.length > 25 ? '…' + table.slice(-24) : table}`, name: field === '*' ? '行集（*）' : field};
  };
  const defs = create('defs', {});
  for (const [id, className] of [['metric-lineage-arrow', 'lineage-arrow'], ['metric-lineage-arrow-active', 'metric-lineage-arrow-active']]) {
    const marker = create('marker', {id, viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto-start-reverse'}, defs);
    create('path', {d: 'M 0 0 L 10 5 L 0 10 z', class: className}, marker);
  }
  const columns = new Map();
  graph.nodes.forEach(node => { if (!columns.has(node.level)) columns.set(node.level, []); columns.get(node.level).push(node); });
  const levels = [...columns.keys()].sort((a, b) => a - b);
  const height = Math.max(420, Math.max(...[...columns.values()].map(items => items.length)) * 115 + 80);
  const positions = new Map();
  levels.forEach((level, column) => columns.get(level).forEach((node, index) => positions.set(node.key, {x: 45 + column * 330, y: (height - columns.get(level).length * 115) / 2 + index * 115})));
  const edgeElements = [];
  for (const [index, edge] of (graph.edges || []).entries()) {
    const from = positions.get(edge.from), to = positions.get(edge.to); if (!from || !to) continue;
    const x1 = from.x + 250, y1 = from.y + 40, x2 = to.x, y2 = to.y + 40, bend = Math.max(40, Math.abs(x2 - x1) / 2);
    const d = edge.from === edge.to ? `M${x1},${y1} C${x1 + 65},${y1 - 105} ${to.x + 125},${to.y - 80} ${to.x + 125},${to.y}` : `M${x1},${y1} C${x1 + bend},${y1} ${x2 - bend},${y2} ${x2},${y2}`;
    const path = create('path', {d, class: 'lineage-edge', 'marker-end': 'url(#metric-lineage-arrow)'});
    create('title', {}, path, [edge.role, edge.expression, edge.task].filter(Boolean).join(' · ') || '关系'); edgeElements[index] = path;
  }
  const nodeElements = new Map();
  graph.nodes.forEach(node => {
    const point = positions.get(node.key);
    const label = caption(node);
    const group = create('g', {transform: `translate(${point.x} ${point.y})`, class: `lineage-node metric-lineage-node${node.level === 0 ? ' lineage-focus' : ''}${node.kind === 'BOUNDARY' ? ' lineage-uncollected' : ''}`, role: 'button', tabindex: 0, 'aria-pressed': 'false', 'data-lineage-node': node.key, 'aria-label': `${kind(node.kind, node.label)} ${node.label}，选择以聚焦上游关系`});
    create('title', {}, group, [kind(node.kind, node.label), node.label, node.detail, node.status].filter(Boolean).join(' · '));
    create('rect', {width: 250, height: 80, rx: 9}, group);
    create('text', {x: 14, y: 21, class: 'lineage-node-source'}, group, label.heading);
    create('text', {x: 14, y: 46, class: 'lineage-node-table'}, group, shorten(label.name, 32));
    create('text', {x: 14, y: 66, class: 'lineage-node-status'}, group, shorten(node.status || node.detail, 34));
    nodeElements.set(node.key, group);
  });
  let selectedKey = null;
  const reset = () => {
    selectedKey = null;
    nodeElements.forEach(element => { element.classList.remove('metric-lineage-selected', 'metric-lineage-ancestor', 'metric-lineage-unrelated'); element.setAttribute('aria-pressed', 'false'); });
    edgeElements.forEach(edge => { if (edge) { edge.classList.remove('metric-lineage-edge-active', 'metric-lineage-edge-muted'); edge.setAttribute('marker-end', 'url(#metric-lineage-arrow)'); } });
    if (panel) panel.hidden = true; if (clear) clear.disabled = true; if (live) live.textContent = '未选择节点，显示当前范围全部关系。';
  };
  const select = key => {
    if (selectedKey === key) return reset();
    const trace = helper.traceUpstream(graph.nodes, graph.edges || [], key); if (!trace.selectedKey) return reset();
    selectedKey = key; const node = graph.nodes.find(item => item.key === key);
    nodeElements.forEach((element, nodeKey) => {
      const isSelected = nodeKey === key, ancestor = trace.ancestorKeys.has(nodeKey);
      element.classList.toggle('metric-lineage-selected', isSelected); element.classList.toggle('metric-lineage-ancestor', ancestor); element.classList.toggle('metric-lineage-unrelated', !isSelected && !ancestor); element.setAttribute('aria-pressed', String(isSelected));
    });
    edgeElements.forEach((edge, index) => { if (edge) { const active = trace.edgeIndexes.has(index); edge.classList.toggle('metric-lineage-edge-active', active); edge.classList.toggle('metric-lineage-edge-muted', !active); edge.setAttribute('marker-end', `url(#${active ? 'metric-lineage-arrow-active' : 'metric-lineage-arrow'})`); } });
    const count = [...trace.ancestorKeys].filter(item => nodeElements.has(item)).length;
    if (panel && node) {
      panel.hidden = false;
      document.querySelector('[data-metric-lineage-selection-name]').textContent = node.label;
      document.querySelector('[data-metric-lineage-selection-type]').textContent = kind(node.kind, node.label);
      document.querySelector('[data-metric-lineage-selection-status]').textContent = node.status || '—';
      document.querySelector('[data-metric-lineage-selection-detail]').textContent = node.detail || '—';
      document.querySelector('[data-metric-lineage-selection-count]').textContent = `${count} 个上游节点，${trace.edgeIndexes.size} 条关联关系`;
      const link = document.querySelector('[data-metric-lineage-selection-link]'), href = safeHref(node.href); link.hidden = !href;
      if (href) link.href = node.kind === 'METRIC' ? `${href}${href.includes('?') ? '&' : '?'}mode=${window.metricLineageMode === 'full' ? 'full' : 'direct'}` : href;
    }
    if (clear) clear.disabled = false; if (live) live.textContent = `已选择 ${node?.label || '节点'}，高亮 ${count} 个上游节点和 ${trace.edgeIndexes.size} 条关联关系。`;
  };
  nodeElements.forEach((element, key) => {
    element.addEventListener('click', event => { event.stopPropagation(); select(key); });
    element.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); select(key); } });
  });
  clear?.addEventListener('click', reset); svg.addEventListener('keydown', event => { if (event.key === 'Escape') reset(); });
  const bounds = {x: 0, y: 0, width: Math.max(380, levels.length * 330 + 50), height};
  const locateMetric = () => {
    const canvas = svg.getBoundingClientRect();
    if (Math.min(canvas.width / bounds.width, canvas.height / bounds.height) >= .65) return {...bounds};
    const scale = Math.max(.85, canvas.width / bounds.width);
    const width = canvas.width / scale, viewportHeight = canvas.height / scale;
    const target = graph.nodes.find(node => node.level === 0 && node.key.endsWith(':metric_value')) || graph.nodes.find(node => node.level === 0) || graph.nodes[0];
    const point = positions.get(target.key);
    return {x: Math.max(0, point.x + 295 - width), y: Math.max(0, point.y + 40 - viewportHeight / 2), width, height: viewportHeight};
  };
  let view = locateMetric();
  const render = () => svg.setAttribute('viewBox', `${view.x} ${view.y} ${view.width} ${view.height}`); render();
  document.querySelectorAll('[data-lineage-zoom]').forEach(button => button.addEventListener('click', () => {
    if (button.dataset.lineageZoom === 'fit') view = {...bounds};
    else if (button.dataset.lineageZoom === 'focus') view = locateMetric();
    else { const scale = button.dataset.lineageZoom === 'in' ? .75 : 1.33, width = Math.max(280, Math.min(bounds.width * 3, view.width * scale)), nextHeight = view.height * width / view.width; view = {x: view.x + (view.width - width) / 2, y: view.y + (view.height - nextHeight) / 2, width, height: nextHeight}; } render();
  }));
  let drag;
  svg.addEventListener('pointerdown', event => { if (event.button !== 0 || event.target.closest('[data-lineage-node]')) return; drag = {x: event.clientX, y: event.clientY, view: {...view}}; svg.setPointerCapture(event.pointerId); });
  svg.addEventListener('pointermove', event => { if (!drag) return; const matrix = svg.getScreenCTM(); view.x = drag.view.x - (event.clientX - drag.x) / matrix.a; view.y = drag.view.y - (event.clientY - drag.y) / matrix.d; render(); });
  ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(name => svg.addEventListener(name, () => { drag = null; }));
  reset();
})();
