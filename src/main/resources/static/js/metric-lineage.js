(() => {
  const graph = window.metricLineage;
  const svg = document.getElementById('metric-lineage-graph');
  if (!svg || !graph?.nodes?.length) return;

  const ns = 'http://www.w3.org/2000/svg';
  const create = (tag, attrs, parent = svg, value) => {
    const element = document.createElementNS(ns, tag);
    for (const [key, attr] of Object.entries(attrs)) element.setAttribute(key, String(attr));
    if (value !== undefined) element.textContent = value;
    parent.appendChild(element);
    return element;
  };
  const relativeHref = value => typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') ? value : null;
  const kindLabel = kind => ({
    METRIC: '指标字段',
    WAREHOUSE_FIELD: '仓库字段',
    SOURCE_FIELD: '业务源字段',
    BOUNDARY: '追溯边界'
  })[kind] || kind || '节点';
  const shorten = (value, max) => {
    const text = String(value || '—');
    if (text.length <= max) return text;
    const dot = text.lastIndexOf('.');
    const suffix = dot >= 0 ? text.slice(dot) : '';
    if (suffix.length && suffix.length <= max - 5) return `${text.slice(0, max - suffix.length - 1)}…${suffix}`;
    const tail = Math.max(5, Math.floor((max - 1) / 2));
    return `${text.slice(0, max - tail - 1)}…${text.slice(-tail)}`;
  };
  const defs = create('defs', {});
  const marker = create('marker', {id: 'metric-lineage-arrow', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto-start-reverse'}, defs);
  create('path', {d: 'M 0 0 L 10 5 L 0 10 z', class: 'lineage-arrow'}, marker);

  const groups = new Map();
  for (const node of graph.nodes) {
    if (!groups.has(node.level)) groups.set(node.level, []);
    groups.get(node.level).push(node);
  }
  const levels = [...groups.keys()].sort((a, b) => a - b);
  const largest = Math.max(...[...groups.values()].map(nodes => nodes.length));
  const height = Math.max(420, largest * 115 + 80);
  const positions = new Map();
  levels.forEach((level, column) => groups.get(level).forEach((node, index) => positions.set(node.key, {
    x: 45 + column * 330,
    y: (height - groups.get(level).length * 115) / 2 + index * 115
  })));

  for (const edge of graph.edges || []) {
    const from = positions.get(edge.from);
    const to = positions.get(edge.to);
    if (!from || !to) continue;
    const x1 = from.x + 250, y1 = from.y + 40, x2 = to.x, y2 = to.y + 40;
    const bend = Math.max(40, Math.abs(x2 - x1) / 2);
    const d = edge.from === edge.to
      ? `M${x1},${y1} C${x1 + 65},${y1 - 105} ${to.x + 125},${to.y - 80} ${to.x + 125},${to.y}`
      : `M${x1},${y1} C${x1 + bend},${y1} ${x2 - bend},${y2} ${x2},${y2}`;
    const path = create('path', {d, class: 'lineage-edge', 'marker-end': 'url(#metric-lineage-arrow)'});
    create('title', {}, path, [edge.role, edge.expression, edge.task].filter(Boolean).join(' · ') || '关系');
  }

  for (const node of graph.nodes) {
    const position = positions.get(node.key);
    const attrs = {transform: `translate(${position.x} ${position.y})`, class: `lineage-node${node.level === 0 ? ' lineage-focus' : ''}${node.kind === 'BOUNDARY' ? ' lineage-uncollected' : ''}`};
    const href = relativeHref(node.href);
    const group = href ? create('a', {...attrs, href, 'aria-label': `${node.label}，查看详情`}) : create('g', attrs);
    create('title', {}, group, [kindLabel(node.kind), node.label, node.detail, node.status].filter(Boolean).join(' · '));
    create('rect', {width: 250, height: 80, rx: 9}, group);
    create('text', {x: 14, y: 21, class: 'lineage-node-source'}, group, shorten(kindLabel(node.kind), 28));
    create('text', {x: 14, y: 46, class: 'lineage-node-table'}, group, shorten(node.label, 28));
    create('text', {x: 14, y: 66, class: 'lineage-node-status'}, group, shorten(node.status || node.detail, 34));
  }

  const bounds = {x: 0, y: 0, width: Math.max(380, levels.length * 330 + 50), height};
  let view = {...bounds};
  const render = () => svg.setAttribute('viewBox', `${view.x} ${view.y} ${view.width} ${view.height}`);
  render();
  document.querySelectorAll('[data-lineage-zoom]').forEach(button => button.addEventListener('click', () => {
    if (button.dataset.lineageZoom === 'fit') view = {...bounds};
    else {
      const scale = button.dataset.lineageZoom === 'in' ? 0.75 : 1.33;
      const width = Math.max(280, Math.min(bounds.width * 3, view.width * scale));
      const height = view.height * width / view.width;
      view = {x: view.x + (view.width - width) / 2, y: view.y + (view.height - height) / 2, width, height};
    }
    render();
  }));
  let drag;
  svg.addEventListener('pointerdown', event => {
    if (event.button !== 0 || event.target.closest('a')) return;
    drag = {x: event.clientX, y: event.clientY, view: {...view}};
    svg.setPointerCapture(event.pointerId);
  });
  svg.addEventListener('pointermove', event => {
    if (!drag) return;
    const matrix = svg.getScreenCTM();
    view.x = drag.view.x - (event.clientX - drag.x) / matrix.a;
    view.y = drag.view.y - (event.clientY - drag.y) / matrix.d;
    render();
  });
  for (const name of ['pointerup', 'pointercancel', 'lostpointercapture']) svg.addEventListener(name, () => { drag = null; });
})();
