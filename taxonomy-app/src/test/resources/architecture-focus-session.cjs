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
// Minimal DOM fixture executes the real pinned-panel rendering and event handlers.
function panelFixture() {
    const document = { activeElement: null };
    class Element {
        constructor(tag) { this.tagName = tag; this.children = []; this.dataset = {}; this.disabled = false; this.text = ''; }
        set textContent(value) { this.text = String(value); this.children = []; }
        get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
        append(...nodes) { this.children.push(...nodes); }
        replaceChildren(...nodes) { if (this.contains(document.activeElement)) document.activeElement = document.body; this.text = ''; this.children = nodes; }
        contains(other) { return this === other || this.children.some(child => child.contains(other)); }
        setAttribute(key, value) { this[key] = value; }
        querySelectorAll(tag) { return this.children.flatMap(child => [...(child.tagName === tag ? [child] : []), ...child.querySelectorAll(tag)]); }
        querySelector() { return this.querySelectorAll('button').find(button => !button.disabled); }
        focus() { document.activeElement = this; }
    }
    const ids = Object.create(null), calls = [], accepted = [];
    document.body = new Element('body'); document.activeElement = document.body;
    document.createElement = tag => new Element(tag);
    document.getElementById = id => ids[id] || (ids[id] = new Element('div'));
    global.document = document;
    const panel = window.ArchitectureFocusPanel({ t: (...parts) => parts.join(' '),
        request: body => new Promise((resolve, reject) => calls.push({ body, resolve, reject })),
        present() {}, accept: item => accepted.push(item), undo() {}, canUndo: () => true, locate() {} });
    panel.setContext(context(3), true); panel.select('arch-a');
    ids.editorLensEnabled.checked = true; ids.editorLensEnabled.onchange();
    return { panel, ids, calls, accepted, inspect() { ids.editorLensInspect.onclick(); },
        retry() { ids.editorLensStates.querySelectorAll('button')[0].onclick(); } };
}
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
    assert.equal(g.session.snapshot().errors['arch-a'], undefined, 'Explicit retry clears the previous request error');
    reply(g.calls[1], 'NO_CANDIDATES'); await flush();
    assert.equal(g.session.snapshot().states['arch-a'], 'noCandidates');
    assert.equal(g.session.snapshot().errors['arch-a'], undefined, 'Successful retry never displays a superseded failure');
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
    revisit.session.setContext(context(4), true); revisit.session.setContext(context(3), true);
    revisit.session.inspect('arch-a'); await flush(); reply(revisit.calls[2], 'LOCAL_CANDIDATES', [suggestion]); await flush();
    assert.equal(revisit.session.snapshot().suggestions[0].stale, false, 'Fresh local investigation replaces a stale assessed result after Back/Forward');
    assert.equal(revisit.session.snapshot().suggestions[0].outcome, 'LOCAL_CANDIDATES');
    const refreshed = { ...suggestion, rationale: 'Fresh assessed rationale',
        evidence: [{ elementId: 'arch-a', field: 'description', quote: 'Fresh exact source excerpt' }],
        properties: { 'x-focus-rationale': 'Fresh assessed rationale', 'x-focus-evidence': 'Fresh exact source excerpt' } };
    const panel = panelFixture(); panel.inspect(); await flush();
    reply(panel.calls[0], 'FAILED', [{ ...suggestion, rationale: 'Old fallback rationale' }]); await flush();
    assert.ok(panel.ids.editorLensReview.textContent.includes('Old fallback rationale'));
    panel.retry(); await flush(); reply(panel.calls[1], 'ASSESSED', [refreshed]); await flush();
    assert.ok(panel.ids.editorLensReview.textContent.includes('Fresh assessed rationale'), 'Successful retry refreshes the actual pinned rationale');
    assert.ok(panel.ids.editorLensReview.textContent.includes('Fresh exact source excerpt'), 'Successful retry refreshes pinned evidence');
    assert.ok(panel.ids.editorLensReview.textContent.includes('AI:test'), 'Pinned method reflects the assessed retry');
    assert.ok(!panel.ids.editorLensReview.textContent.includes('Old fallback rationale'));
    panel.ids.editorLensReview.querySelectorAll('button').find(button => button.dataset.focusAction === 'accept').onclick();
    assert.deepEqual(panel.accepted[0].properties, refreshed.properties, 'Acceptance receives the refreshed persistence payload');
    const pinnedRationale = panel.ids.editorLensReview.children.find(child => child.textContent === refreshed.rationale);
    panel.panel.select('arch-b'); panel.inspect(); await flush(); reply(panel.calls[2], 'NO_CANDIDATES'); await flush();
    assert.ok(panel.ids.editorLensReview.children.includes(pinnedRationale), 'Unrelated queue updates preserve pinned text selection nodes');

    const dedup = fixture(); dedup.session.setEnabled(true); dedup.session.inspect('arch-a'); await flush();
    reply(dedup.calls[0], 'ASSESSED', [{ ...suggestion, targetId: 'arch-b' }]); await flush();
    const originalDuplicate = dedup.session.snapshot().suggestions[0];
    dedup.session.inspect('arch-b'); await flush();
    reply(dedup.calls[1], 'ASSESSED', [{ ...refreshed, targetId: 'arch-b' }]); await flush();
    assert.equal(dedup.session.snapshot().suggestions[0], originalDuplicate, 'Ordinary overlapping investigations retain normal deduplication');

    const quality = fixture(); quality.session.setEnabled(true); quality.session.inspect('arch-a'); await flush();
    reply(quality.calls[0], 'ASSESSED', [{ ...refreshed, targetId: 'arch-b' }]); await flush();
    const assessed = quality.session.snapshot().suggestions[0];
    quality.session.inspect('arch-b'); await flush(); quality.calls[1].reject(new Error('other focus unavailable')); await flush();
    quality.session.inspect('arch-b', true); await flush();
    reply(quality.calls[2], 'FAILED', [{ ...suggestion, targetId: 'arch-b', rationale: 'Unassessed failed retry fallback' }]); await flush();
    assert.equal(quality.session.snapshot().suggestions[0], assessed, 'Failed retry cannot downgrade assessed evidence shared with another focus');
    quality.session.inspect('arch-b', true); await flush();
    reply(quality.calls[3], 'UNASSESSED', [{ ...suggestion, targetId: 'arch-b', rationale: 'Unavailable provider fallback' }]); await flush();
    assert.equal(quality.session.snapshot().suggestions[0], assessed, 'Unassessed retry cannot replace assessed evidence');

    const reassessed = fixture(); reassessed.session.setEnabled(true); reassessed.session.inspect('arch-a'); await flush();
    reply(reassessed.calls[0], 'ASSESSED', [{ ...suggestion, targetId: 'arch-b' }]); await flush();
    reassessed.session.inspect('arch-b'); await flush(); reassessed.calls[1].reject(new Error('retry required')); await flush();
    reassessed.session.inspect('arch-b', true); await flush();
    reply(reassessed.calls[2], 'ASSESSED', [{ ...refreshed, targetId: 'arch-b' }]); await flush();
    assert.equal(reassessed.session.snapshot().suggestions[0].rationale, refreshed.rationale, 'A successful assessed retry may refresh previously assessed evidence');
    assert.deepEqual(reassessed.session.snapshot().suggestions[0].properties, refreshed.properties);

    const retry = fixture(); retry.session.setEnabled(true); retry.session.inspect('arch-a'); await flush();
    const choices = [suggestion, { ...suggestion, id: 'dismissed', targetId: 'arch-dismissed' },
        { ...suggestion, id: 'accepted', targetId: 'arch-accepted' }, { ...suggestion, id: 'pending', targetId: 'arch-pending' }];
    reply(retry.calls[0], 'FAILED', choices); await flush();
    const originals = retry.session.snapshot().suggestions;
    retry.session.dismiss(originals[1].id);
    const acceptance = { operationId: 'accepted-operation', context: context(4) };
    retry.session.accepted(originals[2].id, acceptance);
    const pendingCommand = Object.freeze({ metadata: Object.freeze({ commandId: 'frozen-command' }),
        properties: Object.freeze({ 'x-focus-rationale': 'Previously previewed payload' }) });
    originals[3].command = pendingCommand; originals[3].previewed = true;
    retry.session.inspect('arch-a', true); await flush();
    reply(retry.calls[1], 'ASSESSED', choices.map(item => ({ ...item, rationale: refreshed.rationale, evidence: refreshed.evidence, properties: refreshed.properties })));
    await flush();
    const afterRetry = retry.session.snapshot().suggestions;
    assert.equal(afterRetry.length, 3, 'Retry never resurrects a dismissed decision');
    assert.equal(afterRetry.find(item => item.id === originals[0].id).rationale, refreshed.rationale);
    assert.equal(afterRetry.find(item => item.id === originals[2].id), originals[2], 'Accepted decisions survive retries');
    assert.equal(originals[2].accepted, acceptance);
    assert.equal(afterRetry.find(item => item.id === originals[3].id), originals[3], 'A pending acceptance retains its exact hypothesis');
    assert.equal(originals[3].command, pendingCommand, 'Pending command UUID and payload remain frozen');
    assert.equal(originals[3].rationale, suggestion.rationale);
    console.log('Focus queue: dwell, deduplication, pause, budget, exact-context suppression, local provenance and explicit retry passed.');
})().catch(error => { console.error(error); process.exitCode = 1; });
