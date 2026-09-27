'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2], 'utf8');
const draftStart = source.indexOf('    let offer,');
const draftEnd = source.indexOf('    function requireCleanTransition()', draftStart);
const updateStart = source.indexOf('    async function update(');
const updateEnd = source.indexOf('    function changeView(', updateStart);
assert(draftStart >= 0 && draftEnd > draftStart && updateStart >= 0 && updateEnd > updateStart);

function workspace() {
    let message = '', release;
    const sandbox = {
        project: 7, requirement: 11,
        t: key => ({dirty: 'Unsaved edit has been preserved.', saved: 'Saved.'})[key] || key,
        announce: value => { message = value; }, render() {},
        api: {updateReformulation: () => new Promise(resolve => { release = resolve; })}
    };
    vm.createContext(sandbox);
    vm.runInContext(source.slice(draftStart, draftEnd) + '\n' + source.slice(updateStart, updateEnd)
        + '\nglobalThis.controls = {trackDraft, update, drafts, setOffer: value => {offer = value;}};', sandbox);
    const controls = sandbox.controls;
    controls.setOffer({id: 'offer-1', currentRevision: {number: 1, text: 'Saved source'}});
    const input = new EventTarget(); input.value = 'Saved source';
    controls.trackDraft('proposal', [input], () => ({text: input.value, rationale: 'Manual change'}),
        draft => { input.value = draft.text; });
    return {
        controls, input, message: () => message,
        announce: sandbox.announce,
        type(text) { input.value = text; input.dispatchEvent(new Event('input')); },
        blurChange() { input.dispatchEvent(new Event('change')); },
        save() { return controls.update('revisions', {text: input.value, rationale: 'Manual change'}, 'proposal'); },
        acknowledge(text) { release({id: 'offer-1', currentRevision: {number: 2, text}}); }
    };
}

(async () => {
    const view = workspace();
    view.type('Human draft');
    const generation = view.controls.drafts.get('proposal');
    const refreshed = 'Independent reformulation offer. Unsaved edit has been preserved.';
    view.announce(refreshed);
    view.blurChange();
    assert.equal(view.message(), refreshed, 'Unchanged blur must not rewrite the live status and move the click target');
    assert.equal(view.controls.drafts.get('proposal'), generation, 'Duplicate input/change must retain the edit generation');

    const saved = workspace(); saved.type('Submitted wording');
    const request = saved.save();
    saved.blurChange(); saved.acknowledge('Submitted wording'); await request;
    assert.equal(saved.controls.drafts.size, 0, 'An unchanged blur during saving must not manufacture an unsaved edit');
    assert.equal(saved.message(), 'Saved.');

    const changed = workspace(); changed.type('A');
    const pending = changed.save();
    changed.type('B'); changed.type('A'); changed.blurChange();
    changed.acknowledge('A'); await pending;
    assert.equal(changed.controls.drafts.get('proposal').text, 'A', 'Intervening edits must survive even when their final text equals the submitted text');
    assert.match(changed.message(), /Unsaved edit/);
    console.log('REFORMULATION_DRAFT_EVENTS_OK');
})().catch(error => { console.error(error); process.exitCode = 1; });
