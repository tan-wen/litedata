const test = require('node:test');
const assert = require('node:assert/strict');
const {traceUpstream} = require('../../main/resources/static/js/metric-lineage-graph.js');

const nodes = ['root', 'left', 'right', 'shared', 'unrelated', 'cycle-a', 'cycle-b'].map(key => ({key}));
const edges = [
  {from: 'left', to: 'root'}, {from: 'right', to: 'root'}, {from: 'shared', to: 'left'},
  {from: 'shared', to: 'right'}, {from: 'unrelated', to: 'cycle-a'}, {from: 'cycle-a', to: 'cycle-b'},
  {from: 'cycle-b', to: 'cycle-a'}, {from: 'missing-source', to: 'root'}
];

test('traces every branch and shared ancestor without unrelated branches', () => {
  const trace = traceUpstream(nodes, edges, 'root');
  assert.deepEqual([...trace.ancestorKeys].sort(), ['left', 'missing-source', 'right', 'shared']);
  assert.deepEqual([...trace.edgeIndexes].sort((a, b) => a - b), [0, 1, 2, 3, 7]);
});

test('stops safely at cycles while retaining cycle relations', () => {
  const trace = traceUpstream(nodes, edges, 'cycle-a');
  assert.deepEqual([...trace.ancestorKeys].sort(), ['cycle-b', 'unrelated']);
  assert.deepEqual([...trace.edgeIndexes].sort((a, b) => a - b), [4, 5, 6]);
});

test('unknown selection has no highlighted nodes or edges', () => {
  const trace = traceUpstream(nodes, edges, 'does-not-exist');
  assert.equal(trace.selectedKey, null);
  assert.equal(trace.ancestorKeys.size, 0);
  assert.equal(trace.edgeIndexes.size, 0);
});
