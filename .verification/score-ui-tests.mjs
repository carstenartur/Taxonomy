
test('scoped mappings use raw and effective score evidence, not numeric index defaults', () => {
  const h = harness(true);
  h.state.snapshotDetail.analysis.scoreDetails = { source: { nodeCode: 'source', kind: 'PRODUCT_SUITABILITY', rawScore: 80, effectiveRelevance: 32 } };
  h.renderMappings([{ nodeCode: 'source', directScore: 32, relevance: 0.32, confidence: 0 }]);
  assert.match(h.elements.get('mappingTable').innerHTML, /<td>80%<\/td><td>32%<\/td>/);
});
test('scoped unassessed mappings never display synthetic zero scores', () => {
  const h = harness(true);
  h.state.snapshotDetail.analysis.scoreDetails = {};
  h.renderMappings([{ nodeCode: 'unassessed', directScore: 0, relevance: 0, confidence: 0 }]);
  assert.doesNotMatch(h.elements.get('mappingTable').innerHTML, /<td>0%<\/td>/);
});
test('scoped explicit zero remains a known negative assessment', () => {
  const h = harness(true);
  h.state.snapshotDetail.analysis.scoreDetails = { source: { nodeCode: 'source', kind: 'HIERARCHICAL_RELEVANCE', rawScore: 0, effectiveRelevance: 0 } };
  h.renderMappings([{ nodeCode: 'source', directScore: 85, relevance: 0.85, confidence: 0 }]);
  assert.match(h.elements.get('mappingTable').innerHTML, /<td>0%<\/td><td>0%<\/td>/);
});
