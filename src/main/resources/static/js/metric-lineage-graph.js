(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) root.MetricLineageGraph = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, () => {
  function traceUpstream(nodes, edges, selectedKey) {
    const known = new Set((nodes || []).map(node => node.key));
    if (!known.has(selectedKey)) return {selectedKey: null, ancestorKeys: new Set(), edgeIndexes: new Set()};
    const incoming = new Map();
    (edges || []).forEach((edge, index) => {
      if (!incoming.has(edge.to)) incoming.set(edge.to, []);
      incoming.get(edge.to).push({edge, index});
    });
    const visited = new Set([selectedKey]);
    const ancestors = new Set();
    const edgeIndexes = new Set();
    const queue = [selectedKey];
    while (queue.length) {
      const target = queue.shift();
      for (const {edge, index} of incoming.get(target) || []) {
        edgeIndexes.add(index);
        if (edge.from !== selectedKey) ancestors.add(edge.from);
        if (!visited.has(edge.from)) {
          visited.add(edge.from);
          queue.push(edge.from);
        }
      }
    }
    return {selectedKey, ancestorKeys: ancestors, edgeIndexes};
  }
  return {traceUpstream};
});
