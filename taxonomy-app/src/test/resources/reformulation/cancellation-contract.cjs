/* Execute the actual run controls against a small DOM/API boundary, without npm dependencies. */
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(process.argv[2], 'utf8');
const start = source.indexOf('    async function refreshRuns()');
const end = source.indexOf('    async function start()', start);
assert(start >= 0 && end > start, 'run control entry point exists');
const code = source.slice(start, end);
function element(tag, text) {
    return {tag, text, children: [], dataset: {}, append(...items) {this.children.push(...items);},
        replaceChildren(...items) {this.children = items;}, setAttribute(k,v) {this[k]=v;}};
}
async function verify(language) {
    const target = element('div');
    const generate = element('button');
    const proposal = {id: 'offer-a', currentRevision: {number: 7, text: 'Saved wording'}};
    const requests = [];
    // Include legacy concurrent runs outside the last-three display window. All must be cancellable.
    let runs = Array.from({length: 5}, (_, n) => ({id:'run-'+n, status: n===0 || n===4 ? 'RUNNING' : 'FAILED'}));
    const sandbox = {
        offer: proposal, currentRequirement: {currentVersionId: 3}, mutationInFlight: false,
        readGeneration: 0, timer: null, project: 10, requirement: 20, lang:language,
        document: {getElementById: id => id==='reformulationRuns' ? target : generate},
        clearTimeout() {}, setTimeout() {}, hasDrafts: () => true, t: key => key,
        el: element, button: (label, action) => Object.assign(element('button',label),{action}),
        announce() {}, perform: fn => fn(), render: () => assert.fail('Cancelling a run must not re-render away unsaved text'),
        api: {
            async listReformulationRuns() {return runs;},
            async getReformulation() {return proposal;}, async getRequirement() {return {currentVersionId:3};},
            async updateReformulation(...args) {
                requests.push(args);
                assert.deepEqual(args.slice(0,5), [10,20,'offer-a','synthesis-runs/run-0/cancel',7]);
                runs = runs.map(r => r.id==='run-0' ? {...r,status:'CANCELLED'} : r);
            }
        }
    };
    vm.createContext(sandbox);
    vm.runInContext(code+'\nthis.refresh=refreshRuns;',sandbox);
    await sandbox.refresh();
    const all = node => [node,...node.children.flatMap(all)];
    const cancel = all(target).filter(node => node.tag==='button' && node.text==='cancelRun');
    assert.equal(cancel.length, 2, 'every active run, including legacy ones, has an explicit cancel control');
    assert.equal(generate.disabled, true, 'do not offer another run while an active run exists');
    await cancel[0].action();
    assert.equal(requests.length, 1, 'cancel exactly the selected run with the current proposal revision');
    assert.equal(sandbox.offer, proposal, 'cancel does not replace proposal text or question drafts');
    assert.equal(sandbox.mutationInFlight, false, 'release the UI mutation guard');
    runs = runs.map(r => ({...r,status:'CANCELLED'}));
    await sandbox.refresh();
    assert.equal(all(target).filter(node=>node.tag==='button' && node.text==='cancelRun').length,0);
    assert.equal(generate.disabled,false,'enable an explicit retry after cancellation');
}
Promise.resolve().then(()=>verify('de')).then(()=>verify('en')).then(()=>console.log('REFORMULATION_CANCEL_CONTROLS_OK'));
