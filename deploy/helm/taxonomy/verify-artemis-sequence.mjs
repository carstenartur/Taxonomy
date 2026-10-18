// Bounded, source-free database observations for this newly admitted serial run.
import assert from 'node:assert/strict';

export function verifySequence(snapshot, stage) {
  assert.ok(['waiting-broker', 'first-dispatched', 'successor-dispatched', 'completed'].includes(stage));
  assert.deepEqual(snapshot.inputs, ['CP', 'IP']);
  assert.deepEqual(snapshot.work.map(work => work.root), ['CP', 'IP']);
  assert.equal(snapshot.totalRoots, 2);
  assert.ok(Number.isSafeInteger(snapshot.revision) && snapshot.revision > 0);
  const [cp, ip] = snapshot.work;
  const afterSettlement = stage === 'successor-dispatched' || stage === 'completed';
  const completed = stage === 'completed';
  assert.equal(snapshot.state, completed ? 'COMPLETED' : afterSettlement ? 'RUNNING' : 'QUEUED');
  assert.equal(snapshot.completedRoots, completed ? 2 : afterSettlement ? 1 : 0);
  assert.deepEqual(snapshot.completions, completed ? ['CP', 'IP'] : afterSettlement ? ['CP'] : []);
  assert.deepEqual(snapshot.intents, afterSettlement
    ? [{root: 'CP', status: 'DISPATCHED'}, {root: 'IP', status: 'DISPATCHED'}]
    : [{root: 'CP', status: stage === 'waiting-broker' ? 'WAITING_FOR_BROKER' : 'DISPATCHED'}]);
  for (const [work, finished, state] of [
    [cp, afterSettlement, afterSettlement ? 'COMPLETED' : 'QUEUED'],
    [ip, completed, completed ? 'COMPLETED' : afterSettlement ? 'QUEUED' : 'WAITING_PREDECESSOR'],
  ]) {
    assert.equal(work.state, state);
    assert.equal(work.settled, finished);
    assert.equal(work.hasResult, finished);
    assert.ok(Number.isSafeInteger(work.attempts));
    if (finished) assert.ok(work.attempts >= (work.root === 'CP' ? 2 : 1));
    else assert.equal(work.attempts, 0);
  }
  // Explicit allowlist: never publish task envelopes, source text, or SQL errors.
  return {observed: true, observedAt: new Date().toISOString(), stage,
    state: snapshot.state, completedRoots: snapshot.completedRoots, totalRoots: 2,
    revision: snapshot.revision, inputs: snapshot.inputs, work: snapshot.work.map(
      ({root, state, settled, hasResult, attempts}) => ({root, state, settled, hasResult, attempts})),
    intents: snapshot.intents.map(({root, status}) => ({root, status})), completions: snapshot.completions};
}

if (process.argv[1]?.endsWith('/verify-artemis-sequence.mjs')) {
  try {
    let body = '';
    for await (const chunk of process.stdin) {
      body += chunk;
      if (Buffer.byteLength(body) > 65536) throw new Error('Oversized observation');
    }
    console.log(JSON.stringify(verifySequence(JSON.parse(body), process.argv[2])));
  } catch {
    console.error('Serial root dispatch observation unavailable or inconsistent');
    process.exitCode = 1;
  }
}
