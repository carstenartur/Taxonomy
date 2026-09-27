import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';

// Exercise the production browser wait without Playwright/package dependencies.
const source = await readFile(new URL('./ui-primary-session-workflow.mjs', import.meta.url), 'utf8');
const start = source.indexOf('async function waitForCopilotComplete(');
const end = source.indexOf('\n}\n', start) + 2;
assert.ok(start >= 0 && end > start, 'The real completion wait must remain testable');
const waitSource = source.slice(start, end);

function harness({ status = 'SUCCESS', lastText = 'requirement', busy = false, summary = true,
  recovery = 'COMPLETED', followup = 'COMPLETED', scores = { BP: 70 }, architecture = {} } = {}) {
  const elements = {
    businessText: { value: 'requirement', classList: { contains: () => false } },
    copilotBtn: { disabled: busy },
    copilotSpinner: { classList: { contains: () => !busy } },
    statusArea: { textContent: status === 'PARTIAL' ? 'MEMORY_PRESSURE: partial results retained.' : 'Analysis status' }
  };
  const context = vm.createContext({ window: { TaxonomyState: {
    lastAnalyzedText: lastText, lastAnalysisStatus: status, currentScores: scores,
    currentArchView: architecture, analysisRecovery: { state: recovery },
    recoveryContext: { followupState: followup, followupError: 'Could not persist the completed stage' }
  } }, document: { getElementById: id => elements[id] || null, querySelector: () => summary ? {} : null } });
  vm.runInContext(waitSource, context);
  let polls = 0;
  const page = { async waitForFunction(predicate, text, options) {
    polls++;
    assert.equal(options.timeout, 180_000, 'Do not enlarge the normal completion budget');
    const result = predicate(text);
    if (!result) throw new Error('STILL_WAITING');
  } };
  return { wait: () => context.waitForCopilotComplete(page, 'requirement'), polls: () => polls };
}

test('memory-pressure partial completion fails immediately with its real cause', async () => {
  const h = harness({ status: 'PARTIAL', recovery: 'PAUSED', followup: null, summary: false });
  await assert.rejects(h.wait(), /Copilot.*PARTIAL.*MEMORY_PRESSURE/);
  assert.equal(h.polls(), 1);
});
for (const status of ['ERROR', 'CANCELLED']) test(`${status} is reported without waiting for a success timeout`, async () => {
  await assert.rejects(harness({ status, recovery: 'STOPPED', summary: false }).wait(), new RegExp(`Copilot.*${status}`));
});
test('a failed follow-up reports its durable stage failure', async () => {
  await assert.rejects(harness({ followup: 'PAUSED', summary: false }).wait(), /Copilot.*PAUSED.*Could not persist/);
});
test('only a populated complete SUCCESS with ready controls satisfies the wait', async () => {
  await harness().wait();
  for (const incomplete of [{ busy: true }, { summary: false }, { scores: {} }, { architecture: null }]) {
    await assert.rejects(harness(incomplete).wait(), /STILL_WAITING/);
  }
});
test('a stale terminal result cannot end the wait for a different requirement', async () => {
  await assert.rejects(harness({ status: 'PARTIAL', lastText: 'previous requirement', summary: false }).wait(), /STILL_WAITING/);
});
