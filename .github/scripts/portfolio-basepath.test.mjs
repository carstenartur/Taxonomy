import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const resources = new URL('../../taxonomy-app/src/main/resources/', import.meta.url);
const source = name => readFileSync(new URL('static/js/' + name, resources), 'utf8');
const template = name => readFileSync(new URL('templates/' + name + '.html', resources), 'utf8');
const project = { id: 42, projectKey: 'P-42', title: 'Emergency control', status: 'ACTIVE' };
const requirement = { id: 7, requirementKey: 'REQ-7', title: 'Stop the pump', reviewStatus: 'PROPOSED',
    currentVersion: { id: 11, versionNumber: 1, text: 'Stop the pump before closing the valve.' } };
const job = { id: 'job-7', status: 'SUCCESS', items: [] };
const jobStorage = 'taxonomy.portfolio.analysisJobs.v2';
// RepositoryState.branches is List<String>; object-shaped branch fixtures hide a real API mismatch.
const repository = {
    currentBranch: 'main', headCommit: '0123456789012345678901234567890123456789',
    headTimestamp: '2026-10-07T09:00:00Z', headAuthor: 'qa-reviewer', headMessage: 'Reviewed portfolio',
    branches: ['main', 'review/portfolio', 'release/2026.10'], operationInProgress: false,
    operationKind: null, projectionCommit: '0123456789012345678901234567890123456789',
    projectionBranch: 'main', projectionTimestamp: '2026-10-07T09:00:00Z', projectionStale: false,
    indexCommit: '0123456789012345678901234567890123456789', indexStale: false,
    totalCommits: 3, databaseBacked: false
};
const pages = [
    ['requirement-detail', '/requirements/7', 'requirementHeading', 'detailError'],
    ['portfolio-reports', '/reports', 'reportsProject', 'reportsError'],
    ['portfolio-versioning', '/versioning', 'versioningProject', 'versioningError'],
    ['portfolio-matrices', '/matrices', 'matrixProject', 'matrixError'],
    ['portfolio-import', '/import', 'importProject', 'importError']
];

function createDocument() {
    const nodes = new Map();
    const listeners = new Map();
    const downloads = [];
    function element(tagName = 'div') {
        const attributes = new Map();
        const events = new Map();
        const node = { tagName, children: [], dataset: {}, className: '', value: '', innerHTML: '',
            addEventListener(type, listener) { events.set(type, listener); },
            appendChild(child) { this.children.push(child); child.parentElement = this; return child; },
            append(...children) { children.forEach(child => this.appendChild(child)); },
            setAttribute(name, value) { attributes.set(name, String(value)); this[name] = String(value); },
            getAttribute(name) { return attributes.get(name) ?? null; },
            removeAttribute(name) { attributes.delete(name); delete this[name]; },
            querySelector(selector) { return get(selector); },
            querySelectorAll() { return []; },
            focus() {}, remove() {},
            click() { if (this.tagName === 'a') downloads.push(this.href); events.get('click')?.({target: this}); }
        };
        node.classList = {
            contains(name) { return node.className.split(/\s+/).includes(name); },
            toggle(name, force) {
                const classes = new Set(node.className.split(/\s+/).filter(Boolean));
                if (force ?? !classes.has(name)) classes.add(name); else classes.delete(name);
                node.className = [...classes].join(' ');
            },
            add(...names) { names.forEach(name => this.toggle(name, true)); },
            remove(...names) { names.forEach(name => this.toggle(name, false)); }
        };
        let text = '';
        Object.defineProperty(node, 'textContent', {
            get() { return text; },
            set(value) { text = String(value); this.children = []; this.innerHTML = ''; }
        });
        Object.defineProperty(node, 'options', { get() { return this._options ??= Array.from({length: 5}, () => element('option')); } });
        Object.defineProperty(node, 'previousElementSibling', { get() { return get('previous:' + tagName); } });
        return node;
    }
    function get(id) {
        if (!nodes.has(id)) nodes.set(id, element());
        return nodes.get(id);
    }
    const document = {
        readyState: 'loading', cookie: '', documentElement: {lang: 'de'},
        createElement: element, head: element('head'), body: element('body'),
        getElementById(id) {
            // The job adapter is loaded before it creates its optional job-center UI.
            return ['portfolioJobList', 'portfolioJobSummary'].includes(id) ? null : get(id);
        },
        querySelector(selector) {
            if (selector.startsWith('meta[') || selector.startsWith('script[')) return null;
            return get(selector.startsWith('#') && !selector.includes(' ') ? selector.slice(1) : selector);
        },
        querySelectorAll(selector) {
            return selector === '#textPane h2' ? [get('currentTextHeading'), get('sourceHeading')] : [];
        },
        addEventListener(type, listener) {
            if (!listeners.has(type)) listeners.set(type, []);
            listeners.get(type).push(listener);
        },
        dispatchEvent() {}
    };
    get('reportPreviewFrame').parentElement = element();
    get('relationState').value = 'all';
    get('reportScope').value = 'project';
    get('matrixType').value = 'taxonomy';
    return {document, nodes, get, listeners, downloads};
}

async function boot(prefix, path, storedJobs = []) {
    const dom = createDocument();
    const requests = [];
    const timers = [];
    const storage = new Map([[jobStorage, JSON.stringify(storedJobs)]]);
    let currentLocation = new URL(`https://taxonomy.example.test${path}?lang=de`);
    const location = {
        get href() { return currentLocation.href; },
        set href(value) { currentLocation = new URL(value, currentLocation); },
        get origin() { return currentLocation.origin; },
        get pathname() { return currentLocation.pathname; },
        get search() { return currentLocation.search; }
    };
    const context = vm.createContext({
        document: dom.document, location, URL, URLSearchParams, Request, Response, Headers, FormData,
        AbortController, console, CustomEvent: class {},
        localStorage: {getItem: key => storage.get(key) ?? null,
            setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key)},
        setTimeout(callback, delay) { timers.push({callback, delay}); return timers.length; },
        clearTimeout() {}, queueMicrotask() {}, addEventListener() {},
        bootstrap: {Offcanvas: {getOrCreateInstance: () => ({show() {}})}},
        async fetch(input, init) {
            requests.push({input: String(input), init});
            if (context.mockResponse) return context.mockResponse(input, init);
            const pathname = new URL(String(input), location.href).pathname;
            const path = pathname.startsWith(prefix + '/') ? pathname.slice(prefix.length) : pathname;
            let payload = {};
            if (path === '/api/projects/42') payload = project;
            else if (path === '/api/projects/42/requirements') payload = [requirement];
            else if (path === '/api/projects/42/requirements/7') payload = requirement;
            else if (path.endsWith('/versions') || path.endsWith('/snapshots')) payload = [];
            else if (path === '/api/projects/42/portfolio') payload = {project, requirements: [requirement]};
            else if (path === '/api/git/state') payload = repository;
            else if (path === '/api/projects/git/export') payload = {
                workspaceId: 'shared', username: 'qa-reviewer', activeBranch: repository.currentBranch,
                headCommit: repository.headCommit, dsl: 'project P-42', projectCount: 1,
                requirementCount: 1, solutionCount: 0, productCount: 0, exportedAt: repository.headTimestamp
            };
            else if (path.endsWith('/requirements/import-review')) payload = {};
            return new Response(JSON.stringify(payload), {headers: {'Content-Type': 'application/json'}});
        }
    });
    context.window = context;
    dom.document.currentScript = {src: `https://taxonomy.example.test${prefix}/js/taxonomy-i18n.js`};
    vm.runInContext(source('taxonomy-i18n.js'), context);
    await context.TaxonomyI18n.ready();
    dom.document.currentScript = {src: `https://taxonomy.example.test${prefix}/js/api/portfolio-api.js`};
    vm.runInContext(source('shared/taxonomy-utils.js'), context);
    vm.runInContext(source('api/portfolio-api.js'), context);
    dom.listeners.clear(); // Count and run feature initializers independently of global accessibility setup.
    requests.length = 0;
    function load(name, exposed = '') {
        let code = source('portfolio/' + name + '.js');
        // Keep the complete production closure; expose only existing actions for focused DOM assertions.
        if (exposed) code = code.replace(/\}\)\(\);\s*$/, `window.review = {${exposed}}; })();`);
        vm.runInContext(code, context, {filename: name + '.js'});
        return context.review;
    }
    return {...dom, context, requests, timers, storage, load,
        readJobs: () => JSON.parse(storage.get(jobStorage) || '[]'),
        async initialize() {
            for (const listener of dom.listeners.get('DOMContentLoaded') || []) await listener();
        }};
}

test('every standalone Portfolio page loads the shared URL bootstrap before its feature code', () => {
    for (const [name, feature] of [['projects', 'taxonomy-portfolio'], ...pages.map(([name]) => [name, name])]) {
        const html = template(name);
        const bootstrap = html.indexOf('@{/js/taxonomy-i18n.js}');
        assert.ok(bootstrap >= 0, name + ' must load the existing base-path bootstrap');
        assert.ok(bootstrap < html.indexOf('@{/js/api/portfolio-api.js}'), name + ' API load order');
        assert.ok(bootstrap < html.indexOf(`@{/js/portfolio/${feature}.js}`), name + ' feature load order');
    }
});

for (const prefix of ['', '/taxonomy', '/teams/blue/taxonomy']) {
    for (const [name, suffix, heading, error] of pages) {
        test(`${name} initializes the project at ${prefix || '/'}`, async () => {
            const app = await boot(prefix, `${prefix}/projects/42${suffix}`);
            app.load(name);
            assert.equal(app.listeners.get('DOMContentLoaded')?.length, 1, 'the real page initializer must be registered');
            await app.initialize();
            assert.equal(app.get(error).textContent, '', 'initialization should complete without a hidden page error');
            assert.ok(app.get(heading).textContent.length > 0, 'the loaded project/requirement must appear');
            assert.ok(app.requests.some(request => request.input === `${prefix}/api/projects/42`), 'API requests retain the mount prefix');
            assert.equal(app.get('portfolioBack').href, `${prefix}/projects?lang=de`);
            if (name === 'requirement-detail') assert.equal(app.get('matrixLink').href, `${prefix}/projects/42/matrices?lang=de`);
            if (name === 'portfolio-reports') assert.equal(app.get('versioningLink').href, `${prefix}/projects/42/versioning?lang=de`);
            if (name === 'portfolio-versioning') assert.equal(app.get('reportsLink').href, `${prefix}/projects/42/reports?lang=de`);
        });
    }

    test(`versioning renders real RepositoryState branch strings at ${prefix || '/'}`, async () => {
        const app = await boot(prefix, `${prefix}/projects/42/versioning`);
        app.load('portfolio-versioning');
        await app.initialize();
        assert.equal(app.get('versioningError').textContent, '');
        assert.ok(app.requests.some(request => request.input === `${prefix}/api/git/state`));
        for (const id of ['commitBranch', 'materializeBranch', 'mergeSource', 'mergeTarget']) {
            assert.deepEqual(app.get(id).children.map(option => ({label: option.textContent, value: option.value})),
                repository.branches.map(branch => ({label: branch, value: branch})),
                `${id} must expose every backend string as its visible label and submitted value`);
        }
        assert.equal(app.get('activeBranch').textContent, 'main');
        assert.equal(app.get('reportsLink').href, `${prefix}/projects/42/reports?lang=de`);
    });

    test(`report download and matrix detail navigation retain ${prefix || '/'}`, async () => {
        const reports = await boot(prefix, `${prefix}/projects/42/reports`);
        const report = reports.load('portfolio-reports', 'download');
        assert.ok(report, 'reports page must bootstrap');
        reports.get('reportScope').value = 'requirement';
        reports.get('reportRequirement').value = '7';
        report.download('csv');
        assert.deepEqual(reports.downloads, [`${prefix}/api/projects/42/reports/csv?requirementId=7&matrix=taxonomy`]);
        await reports.context.TaxonomyPortfolioApi.fetchReport(42, 'html', {requirementId: 7});
        assert.equal(reports.requests.at(-1).input, `${prefix}/api/projects/42/reports/html?requirementId=7`, 'fetch must not duplicate the resolved prefix');
        assert.equal(reports.requests.at(-1).init.credentials, 'same-origin');

        const matrices = await boot(prefix, `${prefix}/projects/42/matrices`);
        const matrix = matrices.load('portfolio-matrices', 'state, openCellDetail');
        assert.ok(matrix, 'matrices page must bootstrap');
        matrix.state.portfolio = {requirements: [requirement]};
        matrix.openCellDetail('taxonomy', 'REQ-7', 'NODE-1', 80);
        const link = matrices.get('cellDetailBody').children.find(child => child.tagName === 'a');
        assert.equal(link?.href, `${prefix}/projects/42/requirements/7?lang=de`);
    });

    test(`selected-project tools and requirement links keep context at ${prefix || '/'}`, async () => {
        const app = await boot(prefix, `${prefix}/projects`);
        const review = app.load('taxonomy-portfolio', 'state, renderPortfolio, showNoProjectSelected, renderRequirements');
        const tools = app.get('projectTools');
        review.showNoProjectSelected();
        assert.equal(tools.classList.contains('d-none'), true, 'project-specific navigation is hidden without a project');
        // Render two loaded projects in turn, exercising the actual toolbar update, not a URL formatter alone.
        for (const id of [42, 63]) {
            review.state.selectedProjectId = id;
            review.state.portfolio = {project: {...project, id}, requirements: [], metrics: {}};
            review.renderPortfolio();
            assert.equal(tools.classList.contains('d-none'), false);
            for (const [name, path] of [['Import', 'import'], ['Matrices', 'matrices'], ['Reports', 'reports'], ['Versioning', 'versioning']]) {
                assert.equal(app.get(`project${name}Link`).href, `${prefix}/projects/${id}/${path}?lang=de`);
            }
            review.renderRequirements([{...requirement, title: 'Stop <pump> & "verify"'}]);
            const row = app.get('#requirementsTable tbody').children[0].innerHTML;
            assert.match(row, new RegExp(`href="${prefix}/projects/${id}/requirements/7\\?lang=de"`));
            assert.match(row, /Stop &lt;pump&gt; &amp; &quot;verify&quot;/);
            assert.match(row, /aria-label="[^"]*REQ-7[^"]*"/);
            for (const action of ['requirement-analyze', 'requirement-snapshots', 'requirement-confirm']) assert.ok(row.includes(action), action + ' remains available');
        }
        review.showNoProjectSelected();
        assert.equal(tools.classList.contains('d-none'), true);
        assert.equal(app.get('projectImportLink').href, undefined, 'clear a previously selected project target');
    });

    test(`import completion stores a usable analysis job and returns to ${prefix || '/'}`, async () => {
        const app = await boot(prefix, `${prefix}/projects/42/import`);
        const review = app.load('portfolio-import', 'state, confirmImport, registerAnalysisJob');
        assert.ok(review, 'import page must bootstrap');
        app.context.mockResponse = async () => new Response(JSON.stringify({analysisJob: job}), {
            headers: {'Content-Type': 'application/json', Location: '/api/projects/42/analysis-jobs/job-7'}
        });
        review.state.candidates = [{id: 1, selected: true, decision: 'NEW', key: 'REQ-8', title: 'Close valve', text: 'Close the valve.', type: 'FUNCTIONAL'}];
        await review.confirmImport();
        assert.equal(app.get('importError').textContent, '');
        assert.equal(app.readJobs()[0]?.url, `https://taxonomy.example.test${prefix}/api/projects/42/analysis-jobs/job-7`);
        assert.equal(app.readJobs()[0]?.projectId, 42);
        const redirect = app.timers.find(timer => timer.delay === 1200);
        assert.ok(redirect, 'successful import must schedule return navigation');
        redirect.callback();
        assert.equal(app.context.location.pathname + app.context.location.search, `${prefix}/projects?lang=de`);
    });

    test(`analysis-job registration and polling stay within ${prefix || '/'}`, async () => {
        const app = await boot(prefix, `${prefix}/projects`);
        app.load('taxonomy-portfolio-async');
        assert.equal(app.context.taxonomyPortfolioRegisterJob('/api/projects/42/analysis-jobs/job-7', {...job, status: 'RUNNING'}), true);
        assert.equal(app.readJobs()[0]?.url, `https://taxonomy.example.test${prefix}/api/projects/42/analysis-jobs/job-7`);
        assert.equal(app.readJobs()[0]?.projectId, 42);
        app.context.mockResponse = async () => new Response(JSON.stringify(job));
        await app.timers.find(timer => timer.delay === 1500).callback();
        assert.equal(app.requests[0].input, `https://taxonomy.example.test${prefix}/api/projects/42/analysis-jobs/job-7`);
        assert.equal(app.requests[0].init.credentials, 'same-origin');
    });
}

test('page route matching removes only the exact mounted prefix', async () => {
    for (const [name, suffix] of pages) {
        for (const path of [`/projects/42${suffix}`, `/taxonomy-other/projects/42${suffix}`, `/elsewhere/taxonomy/projects/42${suffix}`, `/taxonomy/projects/42${suffix}/extra`]) {
            const app = await boot('/taxonomy', path);
            app.load(name);
            assert.equal(app.listeners.get('DOMContentLoaded')?.length || 0, 0, `${name} must not activate on ${path}`);
        }
    }
});

test('background submissions recognize both legacy root paths and already mounted paths', async () => {
    const app = await boot('/taxonomy', '/taxonomy/projects');
    app.load('taxonomy-portfolio-async');
    app.context.mockResponse = async () => new Response(JSON.stringify(job), {status: 202,
        headers: {Location: '/taxonomy/api/projects/42/analysis-jobs/job-7'}});
    for (const path of ['/api/projects/42/analyses', '/taxonomy/api/projects/42/analyses', '/taxonomy/api/projects/42/requirements/7/analyses']) {
        const response = await app.context.fetch(path, {method: 'POST'});
        assert.equal(response.status, 200, 'accepted background work reaches the existing nonblocking adapter');
        assert.equal(app.readJobs()[0].projectId, 42);
    }
    for (const path of ['https://elsewhere.example.test/taxonomy/api/projects/42/analyses', '/taxonomy-other/api/projects/42/analyses']) {
        const response = await app.context.fetch(path, {method: 'POST'});
        assert.equal(response.status, 202, 'unrelated applications are never intercepted');
    }
});

test('external, sibling and malformed job locations are not stored or polled', async () => {
    const app = await boot('/taxonomy', '/taxonomy/projects');
    app.load('taxonomy-portfolio-async');
    const invalid = ['https://elsewhere.example.test/taxonomy/api/projects/42/analysis-jobs/job-7',
        '//elsewhere.example.test/api/projects/42/analysis-jobs/job-7',
        '/taxonomy-other/api/projects/42/analysis-jobs/job-7',
        'https://taxonomy.example.test/api/projects/42/analysis-jobs/job-7',
        '/taxonomy/api/projects/42/analysis-jobs/job-7/retry-failed',
        '/taxonomy/api/projects/42/analysis-jobs/job-7?next=/outside',
        '/taxonomy/api/projects/42/analysis-jobs/job-7#fragment',
        'https://user:secret@taxonomy.example.test/taxonomy/api/projects/42/analysis-jobs/job-7',
        'https://[', '/taxonomy/api/projects/42/analysis-jobs/job%2Foutside'];
    for (const path of invalid) assert.equal(app.context.taxonomyPortfolioRegisterJob(path, job), false, path);
    assert.deepEqual(app.readJobs(), []);
    assert.equal(app.timers.length, 0);
    const importer = await boot('/taxonomy', '/taxonomy/projects/42/import');
    const review = importer.load('portfolio-import', 'registerAnalysisJob');
    assert.ok(review, 'import page must bootstrap');
    for (const path of [...invalid, '/taxonomy/api/projects/63/analysis-jobs/job-7']) review.registerAnalysisJob(path, job);
    assert.deepEqual(importer.readJobs(), [], 'an imported job belongs to this project and application');
});

test('restored job history validates the mounted application before polling', async () => {
    const valid = 'https://taxonomy.example.test/taxonomy/api/projects/42/analysis-jobs/job-7';
    const app = await boot('/taxonomy', '/taxonomy/projects', [
        {url: valid, projectId: null, job: {...job, status: 'RUNNING'}},
        {url: 'https://elsewhere.example.test/taxonomy/api/projects/42/analysis-jobs/job-7', job: {...job, status: 'RUNNING'}}
    ]);
    const review = app.load('taxonomy-portfolio-async', 'pollActiveJobs, persistJobs');
    review.persistJobs();
    assert.equal(app.readJobs().length, 1);
    assert.equal(app.readJobs()[0].projectId, 42, 'recover the project identity from the canonical mounted job URL');
    review.pollActiveJobs();
    app.context.mockResponse = async () => new Response(JSON.stringify(job));
    await app.timers[0].callback();
    assert.equal(app.requests.length, 1);
    assert.equal(app.requests[0].input, valid);
});

test('job project identity comes from the resource path after the exact application prefix', async () => {
    const prefix = '/api/projects/99/analysis-jobs/tenant';
    const app = await boot(prefix, prefix + '/projects');
    app.load('taxonomy-portfolio-async');
    assert.equal(app.context.taxonomyPortfolioRegisterJob('/api/projects/42/analysis-jobs/job-7', job), true);
    assert.equal(app.readJobs()[0].projectId, 42);
});

test('project navigation stays in one compact toolbar disclosure', () => {
    const html = template('projects');
    assert.ok(/id="projectTools"[^>]*class="[^"]*dropdown[^"]*d-none/.test(html), 'one initially hidden tools disclosure');
    assert.ok(/id="projectToolsToggle"[^>]*[\s\S]*?data-bs-toggle="dropdown"/.test(html), 'use the existing keyboard-operable dropdown');
    for (const name of ['Import', 'Matrices', 'Reports', 'Versioning']) assert.ok(html.includes(`id="project${name}Link"`));
    assert.ok(/aria-labelledby="projectToolsToggle"/.test(html), 'the menu is labelled by its trigger');
});
