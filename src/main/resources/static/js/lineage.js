(() => {
  const graph = window.metadataLineage;
  const svg = document.getElementById('lineage-graph');
  if (!svg || !graph?.nodes?.length) return;
  const ns = 'http://www.w3.org/2000/svg';
  const create = (tag, attrs, parent = svg, text) => {
    const element = document.createElementNS(ns, tag);
    for (const [key, value] of Object.entries(attrs)) element.setAttribute(key, String(value));
    if (text !== undefined) element.textContent = text;
    parent.appendChild(element);
    return element;
  };
  const defs = create('defs', {});
  const marker = create('marker', {id: 'lineage-arrow', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto-start-reverse'}, defs);
  create('path', {d: 'M 0 0 L 10 5 L 0 10 z', class: 'lineage-arrow'}, marker);
  const minimum = Math.min(...graph.nodes.map(n => n.level));
  const groups = new Map();
  for (const node of graph.nodes) {
    if (!groups.has(node.level)) groups.set(node.level, []);
    groups.get(node.level).push(node);
  }
  const largest = Math.max(...[...groups.values()].map(items => items.length));
  const height = Math.max(420, largest * 115 + 80);
  const positions = new Map();
  for (const [level, nodes] of groups) nodes.forEach((node, index) => positions.set(node.key, {
    x: 45 + (level - minimum) * 330,
    y: (height - nodes.length * 115) / 2 + index * 115
  }));
  for (const edge of graph.edges) {
    const from = positions.get(edge.from), to = positions.get(edge.to);
    if (!from || !to) continue;
    const x1 = from.x + 250, y1 = from.y + 40, x2 = to.x, y2 = to.y + 40;
    const bend = Math.max(40, Math.abs(x2 - x1) / 2);
    const curve = edge.from === edge.to
      ? `M${x1},${y1} C${x1+65},${y1-105} ${to.x+125},${to.y-80} ${to.x+125},${to.y}`
      : `M${x1},${y1} C${x1+bend},${y1} ${x2-bend},${y2} ${x2},${y2}`;
    const path = create('path', {d: curve, class: 'lineage-edge', 'marker-end': 'url(#lineage-arrow)'});
    create('title', {}, path, `${edge.kind} · ${edge.task}`);
  }
  for (const node of graph.nodes) {
    const p = positions.get(node.key);
    const attrs = {transform: `translate(${p.x} ${p.y})`, class: `lineage-node${node.level === 0 ? ' lineage-focus' : ''}${node.present ? '' : ' lineage-uncollected'}`};
    const group = node.metadataId ? create('a', {...attrs, href: `/metadata/${node.metadataId}`, 'aria-label': `${node.source} ${node.table}，查看元数据`}) : create('g', attrs);
    create('title', {}, group, `${node.source} / ${node.table}${node.present ? '' : '（未采集或最近未发现）'}`);
    create('rect', {width: 250, height: 80, rx: 9}, group);
    const shorten = (value, max) => value.length > max ? `${value.slice(0, max - 1)}…` : value;
    create('text', {x: 14, y: 23, class: 'lineage-node-source'}, group, shorten(`${node.source}${node.layer ? ' · ' + node.layer : ''}`, 25));
    create('text', {x: 14, y: 49, class: 'lineage-node-table'}, group, shorten(node.table, 28));
    create('text', {x: 14, y: 67, class: 'lineage-node-status'}, group, node.level === 0 ? '当前表' : node.present ? '查看元数据 →' : '未采集 / 最近未发现');
  }
  const bounds = {x: 0, y: 0, width: (Math.max(...graph.nodes.map(n => n.level)) - minimum) * 330 + 380, height};
  let view = {...bounds};
  const render = () => svg.setAttribute('viewBox', `${view.x} ${view.y} ${view.width} ${view.height}`);
  render();
  document.querySelectorAll('[data-lineage-zoom]').forEach(button => button.addEventListener('click', () => {
    const action = button.dataset.lineageZoom;
    if (action === 'fit') view = {...bounds};
    else {
      const scale = action === 'in' ? 0.75 : 1.33;
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
    // SVG's CTM includes preserveAspectRatio letterboxing and current zoom.
    const matrix = svg.getScreenCTM();
    view.x = drag.view.x - (event.clientX - drag.x) / matrix.a;
    view.y = drag.view.y - (event.clientY - drag.y) / matrix.d;
    render();
  });
  for (const name of ['pointerup', 'pointercancel', 'lostpointercapture']) svg.addEventListener(name, () => { drag = null; });
})();
