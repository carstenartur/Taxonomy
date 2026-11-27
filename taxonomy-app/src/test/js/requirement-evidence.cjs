const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = path.resolve(__dirname, '../../main/resources/static/js/core/taxonomy-requirement-evidence.js');

function renderer() {
    const window = {};
    vm.runInNewContext(readFileSync(source, 'utf8'), {window});
    return window.TaxonomyRequirementEvidence;
}
function claim(overrides = {}) {
    return Object.assign({requirementQuote: 'The storage supports recovery.', subjectKinds: ['PRODUCT'],
        requirementKinds: ['FUNCTIONAL'], contributionType: 'SUPPORTS', evidenceStatus: 'SUPPORTED',
        evidenceBasis: 'EXPLICIT', necessity: 'REQUIRED', scope: 'DELIVERY', condition: '',
        rationale: 'Recovery needs the retained data.', question: ''}, overrides);
}
test('traffic status depends on recorded evidence, independently of scores and relation directness', () => {
    const api = renderer();
    assert.equal(api.status([claim()]), 'SUPPORTED');
    assert.equal(api.status([claim({contributionType:'DIRECT_SUBJECT',evidenceStatus:'UNRESOLVED',question:'Who recovers?'})]), 'UNRESOLVED');
    assert.equal(api.status([claim(), claim({evidenceStatus:'CONFLICT'})]), 'CONFLICT');
    assert.equal(api.status([]), 'NOT_ASSESSED');
    assert.equal(api.status([claim({evidenceStatus:'NOT_APPLICABLE'})]), 'NOT_APPLICABLE');
    assert.equal(api.status([claim({evidenceStatus:'OUT_OF_SCOPE'})]), 'OUT_OF_SCOPE');
});
test('all source evidence and open questions are visible and hostile values remain escaped', () => {
    const api = renderer();
    const question = 'Which <owner> is responsible?';
    const data = {scores:{'BP-1':99}, nodeAssessments:{'BP-1':[claim({evidenceStatus:'UNRESOLVED', question,
        necessity:'OPTIONAL', scope:'EXISTING_ENVIRONMENT', condition:'Only when <approved>',
        requirementQuote:'<ScRiPt>backup note</ScRiPt>', rationale:'Interpretation & explanation'})]}};
    const html = api.html(data, {locale:'de'});
    assert.match(html, /data-requirement-evidence/);
    assert.match(html, /data-evidence-status="UNRESOLVED"/);
    assert.match(html, /Which &lt;owner&gt; is responsible\?/);
    assert.ok(html.includes('&lt;ScRiPt&gt;backup note&lt;/ScRiPt&gt;'));
    assert.match(html, /Only when &lt;approved&gt;/);
    assert.match(html, /Bestehende Umgebung/);
    assert.match(html, /Optional/);
    assert.match(html, /KI-Vorschläge/);
    assert.doesNotMatch(html, /<script\b/i);
    assert.doesNotMatch(html, /99%/);
    assert.equal(api.questions(data).length, 1);
});
test('legacy absence is distinct from an assessed neutral result and does not fabricate aspects', () => {
    const api = renderer();
    const old = api.html({scores:{'CR-1':100}}, {locale:'en'});
    assert.match(old, /Not assessed/);
    assert.doesNotMatch(old, /data-evidence-status="SUPPORTED"/);
    const neutral = api.html({nodeAssessments:{'CR-1':[claim({evidenceStatus:'OUT_OF_SCOPE'})]}}, {locale:'en'});
    assert.match(neutral, /Outside the considered scope/);
    assert.equal(api.questions({}).length, 0);
});
test('multiple source passages retain their own statuses and optional node focus is exact', () => {
    const api = renderer();
    const data = {nodeAssessments:{'BP-1':[claim(),claim({evidenceStatus:'UNRESOLVED',question:'Which process?'})],
        'CR-2':[claim({evidenceStatus:'CONFLICT',rationale:'Contradictory source.'})]}};
    const html = api.html(data, {locale:'en',nodeCode:'BP-1'});
    assert.match(html,/data-evidence-status="SUPPORTED"/);
    assert.match(html,/data-evidence-status="UNRESOLVED"/);
    assert.doesNotMatch(html,/Contradictory source/);
    assert.equal(api.status(data.nodeAssessments['BP-1']), 'UNRESOLVED');
});

test('an assessment without a source passage does not invent a requirement aspect', () => {
    const api = renderer();
    const html = api.html({nodeAssessments:{BP:[claim({requirementQuote:'',evidenceStatus:'NOT_APPLICABLE',subjectKinds:['PROCESS']})]}}, {locale:'en'});
    assert.doesNotMatch(html, /<p class="small">Recorded aspects:/);
});
