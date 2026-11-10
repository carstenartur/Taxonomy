import { readFileSync } from 'node:fs';

// The real Java metamodel/API contract reads the same authored fixture tuples.
export const primaryProposalCandidates = Object.freeze(JSON.parse(readFileSync(
  new URL('../fixtures/ui-primary-proposals.json', import.meta.url), 'utf8')).map(Object.freeze));

export async function createPendingProposalFixtures(submit, assert) {
  const ids = new Set();
  const attempts = [];
  for (const [sourceCode, targetCode, relationType] of primaryProposalCandidates) {
    const response = await submit({ sourceCode, targetCode, relationType, confidence: 0.73,
      rationale: `Primary workflow ${sourceCode}-${targetCode}-${relationType}` });
    const detail = `${sourceCode}-${targetCode}-${relationType}: ${response.status} ${response.text || JSON.stringify(response.json) || ''}`.trim();
    attempts.push(detail);
    assert(response.status === 409 || (response.status === 200 && response.json?.id),
      `Authored proposal fixture was rejected: ${detail}`);
    if (response.status === 200) ids.add(response.json.id);
    if (ids.size >= 4) break;
  }
  assert(ids.size >= 4,
    `Unable to create four independent proposals; created ${ids.size}; ${attempts.join(', ')}`);
  return [...ids];
}
