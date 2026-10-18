const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = name => readFileSync(path.resolve(__dirname, '../../main/resources/static/js', name), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

function dom() {
    const nodes = new Map();
    function element(tag = 'div', id) {
        const classes = new Set(), listeners = new Map();
        const node = {tagName: tag, children: [], dataset: {}, attributes: {}, style: {}, disabled: false,
            textContent: '', innerHTML: '', value: '',
            classList: {add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c),
                toggle(c, on) { if (on === undefined ? !classes.has(c) : on) classes.add(c); else classes.delete(c); }},
            setAttribute(key, value) {this.attributes[key] = String(value);},
            removeAttribute(key) {delete this.attributes[key];},
            appendChild(child) {this.children.push(child); return child;},
            replaceChildren(...children) {this.children = children;},
            addEventListener(name, fn) {listeners.set(name, fn);},
            click() {return this.disabled ? Promise.resolve() : listeners.get('click')?.({target: this});},
            focus() {},
            querySelectorAll(selector) {
                return this.children.flatMap(child => [ ...(child.tagName === selector ? [child] : []), ...child.querySelectorAll(selector) ]);
            }
        };
        if (id) {node.id = id; nodes.set(id, node);}
        return node;
    }
    const document = {readyState: 'complete', documentElement: {lang: 'de'},
        getElementById: id => nodes.get(id), createElement: element,
        addEventListener() {}, querySelectorAll: () => [], querySelector: () => null};
    return {nodes, element, document};
}

function operation(status = 'PARTIAL') {
    return {operationId: 'saved-operation', status, provider: 'CUSTOM_OPENAI', verificationPasses: 2, completedPasses: 2,
        selectedSnapshotId: 'snapshot-old-1', message: 'Partial result',
        jobs: [1, 2].map(n => ({id: 'pass-' + n, projectId: 41, status: 'PARTIAL', items: [
            {id: n + 10, status: 'PARTIAL', snapshotId: 'snapshot-old-' + n, requirementId: 7, attempt: 1}
        ]}))};
}

async function uiFixture(options = {}) {
    const d = dom(), requests = [], timers = [], redirects = [];
    for (const id of ['requirementCopilotCard', 'copilotRun', 'copilotRunSpinner', 'copilotCancel', 'copilotProfile',
        'copilotForce', 'copilotError', 'copilotPolicy', 'copilotOperation', 'copilotOperationStatus',
        'copilotOperationPhase', 'copilotOperationMessage', 'copilotResultActions', 'copilotSelectedSnapshot',
        'copilotOpenResult', 'copilotContinuationActions', 'requirementLive']) d.element('div', id);
    let current = operation();
    const window = {location: {pathname: '/projects/41/requirements/7', search: '?lang=de',
        href: 'http://localhost/projects/41/requirements/7?lang=de', replace: url => redirects.push(String(url))},
        TaxonomyUtils: {escapeHtml: value => String(value)},
        setTimeout: (fn, ms) => {timers.push({fn, ms}); return timers.length;},
        TaxonomyCopilotApi: {
            status: async () => ({manualCopilotReady: true}), latest: async () => current,
            get: async () => {requests.push({method: 'status'}); return current;}
        },
        TaxonomyPortfolioApi: {
            getAnalysisContinuation: async (project, job, item) => {
                requests.push({method: 'GET', project, job, item});
                return {available: options.unavailable !== job, reason: 'A failed provider attempt needs its own recovery decision',
                    snapshotId: 'snapshot-old-' + (item - 10), continuationVersion: 100 + item, continuationId: job, state: 'STOPPED'};
            },
            continueAnalysis: async (project, job, item, decision) => {
                requests.push({method: 'POST', project, job, item, snapshotId: decision.snapshotId, revision: decision.continuationVersion});
                if (options.rejectedPost) {
                    current = operation(options.observedStatus);
                    current.selectedSnapshotId = 'snapshot-completed';
                    throw new Error('Continuation rejected');
                }
                if (options.terminalResponse) {
                    current = operation(options.terminalResponse);
                    current.selectedSnapshotId = 'snapshot-completed';
                    throw new Error('Connection lost after completion');
                }
                current = operation(options.lostResponse ? 'PENDING' : 'PARTIAL');
                current.jobs[0].status = current.jobs[0].items[0].status = options.lostResponse ? 'PENDING' : 'SUCCESS';
                if (!options.lostResponse) {
                    current.jobs[0].items[0].snapshotId = 'snapshot-new-1';
                    current.selectedSnapshotId = 'snapshot-new-1';
                }
                if (options.lostResponse) throw new Error('Connection lost after admission');
                return current.jobs[0];
            }
        }
    };
    vm.runInNewContext(source('portfolio/requirement-copilot.js'), {window, document: d.document, URL, URLSearchParams});
    await tick(); await tick();
    return {...d, requests, timers, redirects, window,
        buttons: () => d.nodes.get('copilotContinuationActions').querySelectorAll('button')};
}

test('reading a restored partial operation offers only server-authorized explicit pass actions', async () => {
    const f = await uiFixture({unavailable: 'pass-2'});
    assert.equal(f.buttons().length, 2);
    assert.equal(f.buttons()[0].disabled, false);
    assert.equal(f.buttons()[1].disabled, true);
    assert.match(f.buttons()[0].textContent, /Fortsetzen.*Prüfdurchlauf 1/);
    assert.ok(f.nodes.get('copilotContinuationActions').children.some(child =>
        child.textContent.includes('offene fachliche Fragen bleiben offen')));
    assert.deepEqual(f.requests.map(r => r.method), ['GET', 'GET']);
    assert.equal(f.redirects.length, 0);
    assert.match(f.nodes.get('copilotOpenResult').href, /snapshot=snapshot-old-1/);
});

test('one click continues that pass and refreshes status and selected snapshot without creating a verification pass', async () => {
    const f = await uiFixture();
    await f.buttons()[0].click(); await tick();
    assert.deepEqual(f.requests.filter(r => r.method === 'POST'), [{method: 'POST', project: 41, job: 'pass-1', item: 11,
        snapshotId: 'snapshot-old-1', revision: 111}]);
    assert.equal(f.buttons().length, 1);
    assert.equal(f.buttons()[0].dataset.jobId, 'pass-2');
    assert.match(f.nodes.get('copilotOpenResult').href, /snapshot=snapshot-new-1/);
    assert.equal(f.nodes.get('copilotOperation').attributes['data-operation-status'], 'PARTIAL');
    assert.equal(f.timers.length, 0, 'partial result remains visible instead of forcing navigation');
    assert.equal(f.redirects.length, 0);
});

test('an uncertain continuation HTTP response causes observation, never an automatic second POST', async () => {
    const f = await uiFixture({lostResponse: true});
    await f.buttons()[0].click(); await tick();
    assert.equal(f.requests.filter(r => r.method === 'POST').length, 1);
    assert.equal(f.nodes.get('copilotOperation').attributes['data-operation-status'], 'PENDING');
    assert.equal(f.buttons().length, 0);
    f.timers.shift().fn(); await tick();
    assert.equal(f.requests.filter(r => r.method === 'POST').length, 1);
    assert.equal(f.requests.filter(r => r.method === 'status').length, 2);
});

for (const failure of ['rejected POST', 'lost response']) {
    for (const status of ['PARTIAL', 'SUCCESS', 'FAILED', 'CANCELLED']) {
        test(`copilot observes ${status} after ${failure} and restores terminal controls without another POST`, async () => {
            // Either failure can observe a terminal operation changed by the server
            // before the status read; the observation remains authoritative.
            const f = await uiFixture(failure === 'rejected POST'
                ? {rejectedPost: true, observedStatus: status} : {terminalResponse: status});
            await f.buttons()[0].click(); await tick();
            assert.equal(f.requests.filter(r => r.method === 'POST').length, 1);
            assert.equal(f.requests.filter(r => r.method === 'status').length, 1);
            assert.equal(f.nodes.get('copilotOperation').attributes['data-operation-status'], status);
            for (const id of ['copilotRun', 'copilotProfile', 'copilotForce']) {
                assert.equal(f.nodes.get(id).disabled, false, id + ' must recover');
            }
            assert.equal(f.nodes.get('copilotRun').attributes['aria-busy'], 'false');
            assert.equal(f.nodes.get('copilotRunSpinner').classList.contains('d-none'), true);
            assert.equal(f.nodes.get('copilotCancel').disabled, true);
            assert.equal(f.timers.length, status === 'SUCCESS' ? 1 : 0);
            if (status === 'SUCCESS') {
                assert.equal(f.timers[0].ms, 600, 'The normal completion action must run, not polling');
                f.timers.shift().fn();
                assert.match(f.redirects[0], /snapshot=snapshot-completed/);
            }
            assert.equal(f.requests.filter(r => r.method === 'POST').length, 1);
        });
    }
}

test('the API client sends only the selected immutable snapshot and revision with CSRF protection', async () => {
    const requests = [], window = {location: {pathname: '/projects'}};
    const document = {querySelector(selector) {return selector.includes('_csrf_header') ? {content: 'X-CSRF-TEST'} : {content: 'token'};}};
    vm.runInNewContext(source('api/portfolio-api.js'), {window, document, URL, URLSearchParams,
        fetch: async (url, options) => {requests.push({url, options}); return {ok: true, status: 202, json: async () => ({id: 'job'})};}});
    await window.TaxonomyPortfolioApi.getAnalysisContinuation(41, 'job', 7);
    await window.TaxonomyPortfolioApi.continueAnalysis(41, 'job', 7,
        {snapshotId: 'snapshot', continuationVersion: 123, available: true, maxCalls: 999, verificationPasses: 10});
    assert.equal(requests[0].url, '/api/projects/41/analysis-jobs/job/items/7/continuation');
    assert.equal(requests[1].url, '/api/projects/41/analysis-jobs/job/items/7/continue');
    assert.equal(requests[1].options.method, 'POST');
    assert.equal(requests[1].options.headers['X-CSRF-TEST'], 'token');
    assert.deepEqual(JSON.parse(requests[1].options.body), {snapshotId: 'snapshot', continuationVersion: 123});
});

function productionSection(text, start, end) {
    const first = text.indexOf(start), last = text.indexOf(end, first + start.length);
    assert.ok(first >= 0 && last > first, 'The production browser section must still exist: ' + start);
    return text.slice(first, last);
}

function portfolioContinuationFixture(postResponse, observedResponse) {
    const d = dom(), requests = [], scheduled = [], errors = [];
    const error = d.element('div', 'portfolioError');
    error.classList.add('d-none');
    Object.defineProperty(error, 'textContent', {
        get() {return errors.at(-1) || '';}, set(value) {errors.push(String(value));}
    });
    d.element('div', 'portfolioAlert');
    const jobUrl = 'http://localhost/api/projects/41/analysis-jobs/saved-job';
    const oldJob = {id: 'saved-job', projectId: 41, status: 'PARTIAL', items: [
        {id: 7, requirementId: 9, requirementVersionId: 13, status: 'PARTIAL', attempt: 1, snapshotId: 'old-snapshot'}
    ]};
    const jobs = new Map([[jobUrl, {url: jobUrl, projectId: 41, expanded: true, job: oldJob}]]);
    const decision = {available: true, snapshotId: 'old-snapshot', continuationVersion: 23};
    const button = {disabled: false, dataset: {decisionKey: 'saved-decision', itemId: '7'}};
    const article = {dataset: {jobUrl}};
    const event = {target: {closest(selector) {
        return selector === '.portfolio-job' ? article : selector === '.job-continue' ? button : null;
    }}};
    const continuedJob = { ...oldJob, status: 'PENDING', items: [{ ...oldJob.items[0], status: 'PENDING', attempt: 2 }] };
    const jsonResponse = (value, status = 200) => new Response(JSON.stringify(value), {
        status, headers: {'Content-Type': 'application/json'}
    });
    const failedBody = () => new Response(new ReadableStream({start(controller) {
        controller.error(new TypeError('The accepted response body was interrupted'));
    }}), {status: 202, headers: {'Content-Type': 'application/json'}});
    const fetch = async (url, options) => {
        requests.push({url, method: options.method || 'GET', body: options.body});
        if (options.method === 'POST') {
            assert.equal(url, '/api/projects/41/analysis-jobs/saved-job/items/7/continue');
            return postResponse({continuedJob, jsonResponse, failedBody});
        }
        assert.equal(url, '/api/projects/41/analysis-jobs/saved-job');
        return observedResponse ? observedResponse({continuedJob, jsonResponse, failedBody}) : jsonResponse(continuedJob);
    };
    const window = {location: {pathname: '/projects', href: 'http://localhost/projects', origin: 'http://localhost'}};
    const context = vm.createContext({window, document: d.document, fetch, URL, URLSearchParams,
        jobs, event, locale: 'en', applicationBasePath: '',
        terminalStatuses: new Set(['SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED']),
        continuationDecisions: new Map([['saved-decision', {decision}]]),
        // Isolate card layout and storage, while retaining the actual registry, API,
        // click handler and visible error writer. No continuation API is mocked.
        trimHistory() {}, persistJobs() {}, renderJobs() {},
        schedulePoll(url) {scheduled.push(url);}
    });
    vm.runInContext(source('api/portfolio-api.js'), context);
    const portfolio = source('portfolio/taxonomy-portfolio-async.js');
    vm.runInContext([
        productionSection(portfolio, '    const messages = ', '    restoreJobs();'),
        productionSection(portfolio, '    function m(key)', '    function installFetchAdapter()'),
        productionSection(portfolio, '    function registerJob(', '    function restoreJobs()'),
        productionSection(portfolio, '    async function onJobAction(', '    function renderItemContinuation('),
        productionSection(portfolio, '    function showError(', '    function currentProjectId()')
    ].join('\n'), context);
    return {requests, scheduled, errors, button, oldJob, continuedJob,
        error, job: () => jobs.get(jobUrl).job,
        click: () => vm.runInContext('onJobAction(event)', context)};
}

const invalidContinuationResponses = [
    ['interrupted JSON body', ({failedBody}) => failedBody()],
    ['malformed JSON', () => new Response('{"id":', {status: 202})],
    ['missing job identity', ({jsonResponse}) => jsonResponse({}, 202)],
    ['foreign job', ({continuedJob, jsonResponse}) => jsonResponse({...continuedJob, id: 'foreign-job'}, 202)],
    ['foreign project', ({continuedJob, jsonResponse}) => jsonResponse({...continuedJob, projectId: 42}, 202)],
    ['invalid job status', ({continuedJob, jsonResponse}) => jsonResponse({...continuedJob, status: 'UNKNOWN'}, 202)],
    ['missing continued item', ({continuedJob, jsonResponse}) => jsonResponse({...continuedJob, items: []}, 202)]
];

for (const [name, response] of invalidContinuationResponses) {
    test(`portfolio continuation observes the known job after an accepted response with ${name}`, async () => {
        const f = portfolioContinuationFixture(response);
        await f.click();
        assert.deepEqual(f.requests.map(request => request.method), ['POST', 'GET']);
        assert.deepEqual(JSON.parse(f.requests[0].body), {snapshotId: 'old-snapshot', continuationVersion: 23});
        assert.equal(f.errors.length, 1, 'The invalid accepted response must remain visible');
        assert.ok(f.error.textContent.length > 0);
        assert.equal(f.error.classList.contains('d-none'), false);
        assert.equal(f.job().id, 'saved-job');
        assert.equal(f.job().projectId, 41);
        assert.equal(f.job().status, 'PENDING');
        assert.equal(f.job().items[0].attempt, 2);
        assert.deepEqual(f.scheduled, ['http://localhost/api/projects/41/analysis-jobs/saved-job']);
        assert.equal(f.button.disabled, true);
        await f.click();
        assert.equal(f.requests.filter(request => request.method === 'POST').length, 1);
    });
}

for (const [name, response] of [
    ['HTTP failure', ({jsonResponse}) => jsonResponse({detail: 'Status observation failed'}, 503)],
    ['body failure', ({failedBody}) => failedBody()],
    ['invalid job', ({jsonResponse}) => jsonResponse({}, 200)]
]) {
    test(`portfolio continuation exposes a subsequent status ${name} without repeating the accepted POST`, async () => {
        const f = portfolioContinuationFixture(({failedBody}) => failedBody(), response);
        await f.click();
        assert.deepEqual(f.requests.map(request => request.method), ['POST', 'GET']);
        assert.equal(f.errors.length, 2, 'Both the accepted-response problem and failed observation remain reported');
        assert.ok(f.error.textContent.length > 0);
        assert.equal(f.error.classList.contains('d-none'), false);
        assert.deepEqual(f.job(), f.oldJob, 'An unreadable status must not fabricate or replace the authoritative job');
        assert.equal(f.button.disabled, true);
        assert.deepEqual(f.scheduled, []);
        await f.click();
        assert.equal(f.requests.filter(request => request.method === 'POST').length, 1);
    });
}

test('portfolio continuation registers a valid accepted job without a redundant observation GET', async () => {
    const f = portfolioContinuationFixture(({continuedJob, jsonResponse}) => jsonResponse(continuedJob, 202));
    await f.click();
    assert.deepEqual(f.requests.map(request => request.method), ['POST']);
    assert.deepEqual(f.errors, []);
    assert.equal(f.job().status, 'PENDING');
    assert.deepEqual(f.scheduled, ['http://localhost/api/projects/41/analysis-jobs/saved-job']);
});

for (const failure of ['HTTP failure', 'connection failure']) {
    test(`portfolio retries eligibility on later rendering after ${failure} without posting continuation`, async () => {
        const d = dom(), requests = [];
        let resolveFirst;
        const first = new Promise(resolve => {resolveFirst = resolve;});
        const decision = {available: true, snapshotId: 'old-snapshot', continuationVersion: 23};
        const window = {location: {pathname: '/projects'}};
        const context = vm.createContext({window, document: d.document, URL, URLSearchParams,
            continuationDecisions: new Map(), m: key => key,
            fetch: async (url, options) => {
                requests.push({url, method: options.method || 'GET'});
                assert.equal(options.method || 'GET', 'GET', 'Rendering never submits continuation');
                if (requests.length === 1) {
                    await first;
                    if (failure === 'connection failure') throw new TypeError('Network unavailable');
                    return new Response(JSON.stringify({detail: 'Temporarily unavailable'}), {status: 503});
                }
                return new Response(JSON.stringify(decision), {status: 200});
            }});
        vm.runInContext(source('api/portfolio-api.js'), context);
        const portfolio = source('portfolio/taxonomy-portfolio-async.js');
        vm.runInContext(productionSection(portfolio, '    function renderItemContinuation(', '    function countStatuses('), context);
        const job = {id: 'saved-job', projectId: 41};
        const item = {id: 7, snapshotId: 'old-snapshot', attempt: 1};
        const render = () => {
            const target = d.element();
            context.renderItemContinuation(target, job, item);
            return target;
        };
        const firstCard = render(), concurrentCard = render();
        assert.equal(requests.length, 1, 'Concurrent renders share an in-flight eligibility request');
        assert.equal(firstCard.children[0].disabled, true);
        resolveFirst(); await tick();
        assert.equal(firstCard.children[0].disabled, true);
        assert.ok(firstCard.children[1].textContent.length > 0, 'The transient failure remains visible');
        assert.equal(concurrentCard.children[0].disabled, true);
        const laterCard = render(); await tick();
        assert.equal(requests.length, 2, 'A later render retries the read for the same snapshot and attempt');
        assert.equal(laterCard.children[0].disabled, false);
        assert.equal(laterCard.children[1].textContent, '');
        const reopenedCard = render(); await tick();
        assert.equal(reopenedCard.children[0].disabled, false);
        assert.equal(requests.length, 2, 'A successful decision remains cached');
        assert.ok(requests.every(request => request.method === 'GET'));
    });
}
