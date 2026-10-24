const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
global.window = {};
vm.runInThisContext(fs.readFileSync(process.argv[2], 'utf8'), { filename: 'architecture-focus.js' });
const flush = () => new Promise(resolve => setImmediate(resolve));
const context = revision => ({ repositoryId: 'repository', workspaceScopeKey: 'personal', branch: 'draft',
    actor: 'architect', commit: null, revision, writeMode: 'PRIVATE_WORKSPACE' });
function fixture() {
    const calls = [], timers = new Map(); let tick = 0;
    const session = window.ArchitectureFocusSession({ request(body) {
        return new Promise((resolve, reject) => calls.push({ body, resolve, reject }));
    }, schedule(fn) { timers.set(++tick, fn); return tick; }, cancel(id) { timers.delete(id); }, changed() {}, language: () => 'de' });
    session.setContext(context(3), true);
    return { session, calls, dwell() { const ready = [...timers.values()]; timers.clear(); ready.forEach(fn => fn()); } };
}
function reply(call, outcome = 'ASSESSED', suggestions = []) {
    call.resolve({ context: call.body.context, sourceId: call.body.sourceId, sourceHash: 'captured-hash',
        outcome, method: outcome === 'ASSESSED' ? 'AI:test' : 'LOCAL_TEXT_UNASSESSED', modelCalls: outcome === 'ASSESSED' ? 1 : 0,
        candidateCount: suggestions.length, suggestions });
}
const suggestion = { id: 'relation-1', sourceId: 'arch-a', targetId: 'arch-outside-focus', relationType: 'SUPPORTS',
    rationale: 'A design hypothesis, not an observed deployment.', evidence: [{ elementId: 'arch-a', field: 'description', quote: '<script>literal text</script>' }], properties: {} };
(async () => {
    const { session: s, calls, dwell } = fixture();
    s.observe(['arch-a']); dwell(); await flush(); assert.equal(calls.length, 0, 'No background I/O before explicit enable');
    s.setEnabled(true); s.observe(['arch-a']); s.observe([]); dwell(); await flush();
    assert.equal(calls.length, 0, 'Passing over an element briefly must not consume a call');
    s.observe(['arch-a', 'arch-b']); dwell(); await flush();
    assert.equal(calls.length, 1); assert.equal(s.snapshot().states['arch-a'], 'examining');
    assert.equal(s.snapshot().states['arch-b'], 'queued');
    s.observe(['arch-a', 'arch-b']); dwell(); s.setPaused(true);
    reply(calls[0], 'ASSESSED', [suggestion]); await flush();
    assert.equal(calls.length, 1, 'Pause lets the running call complete but starts no queued calls');
    assert.equal(s.snapshot().suggestions[0].targetId, 'arch-outside-focus', 'Focus is a seed, not a target boundary');
    s.setBudget(1); s.setPaused(false); await flush(); assert.equal(calls.length, 1, 'Budget is a hard request ceiling');
    s.setBudget(2); await flush(); assert.equal(calls.length, 2);
    reply(calls[1], 'UNASSESSED'); await flush(); assert.equal(s.snapshot().states['arch-b'], 'unassessed');
    s.inspect('arch-a'); await flush(); assert.equal(calls.length, 2, 'Completed elements are deduplicated for an exact revision');
    s.setContext(context(4), true);
    assert.ok(s.snapshot().suggestions.every(item => item.stale), 'A new revision invalidates evidence');
    s.setBudget(3); s.inspect('arch-a'); await flush(); assert.equal(calls.length, 3);
    s.setContext(context(5), true); s.inspect('arch-b');
    reply(calls[2], 'ASSESSED', [suggestion]); await flush();
    assert.ok(s.snapshot().suggestions.every(item => item.stale), 'Late results from another revision never become actionable');
    assert.equal(s.snapshot().used, 3, 'Revision changes must not reset the spending limit');

    const f = fixture(); f.session.setEnabled(true); f.session.inspect('arch-a'); await flush();
    reply(f.calls[0], 'LOCAL_CANDIDATES', [suggestion]); await flush();
    assert.equal(f.session.snapshot().states['arch-a'], 'unassessed');
    assert.equal(f.session.snapshot().modelCalls, 0, 'Local candidates must not claim model execution');
    const proposed = f.session.snapshot().suggestions[0];
    f.session.dismiss(proposed.id); assert.equal(f.session.snapshot().suggestions.length, 0);
    f.session.inspect('arch-a'); await flush(); assert.equal(f.calls.length, 1, 'Dismissal does not trigger automatic rediscovery');

    const g = fixture(); g.session.setEnabled(true); g.session.inspect('arch-a'); await flush();
    g.calls[0].reject(new Error('provider unavailable')); await flush();
    assert.equal(g.session.snapshot().states['arch-a'], 'failed', 'Failed is distinct from assessed without suggestions');
    g.session.observe(['arch-a']); g.dwell(); await flush(); assert.equal(g.calls.length, 1, 'Failures are not silently retried');
    g.session.inspect('arch-a', true); await flush(); assert.equal(g.calls.length, 2, 'Explicit retry is allowed within budget');
    reply(g.calls[1], 'NO_CANDIDATES'); await flush();
    assert.equal(g.session.snapshot().states['arch-a'], 'noCandidates');
    g.session.setContext({ ...context(3), workspaceScopeKey: 'different' }, false);
    assert.equal(g.session.snapshot().enabled, false, 'Unavailable or read-only scope stops investigation');
    const h = fixture(); h.session.setEnabled(true); h.session.inspect('constructor'); await flush();
    assert.equal(h.calls.length, 1, 'Imported element identities must not collide with Object prototypes');
    reply(h.calls[0], 'NO_CANDIDATES'); await flush(); h.session.inspect('__proto__'); await flush();
    assert.equal(h.calls.length, 2); assert.equal(h.session.snapshot().states.__proto__, 'examining');
    const revisit = fixture(); revisit.session.setEnabled(true); revisit.session.inspect('arch-a'); await flush();
    reply(revisit.calls[0], 'ASSESSED', [suggestion]); await flush();
    revisit.session.setContext(context(4), true); revisit.session.setContext(context(3), true);
    revisit.session.inspect('arch-a'); await flush(); reply(revisit.calls[1], 'ASSESSED', [suggestion]); await flush();
    assert.equal(revisit.session.snapshot().suggestions.length, 1);
    assert.equal(revisit.session.snapshot().suggestions[0].stale, false, 'A fresh investigation after Back/Forward replaces a stale copy of the same result');
    console.log('Focus queue: dwell, deduplication, pause, budget, exact-context suppression, local provenance and explicit retry passed.');
})().catch(error => { console.error(error); process.exitCode = 1; });
