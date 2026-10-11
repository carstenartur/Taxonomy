import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const js = path => readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/' + path, import.meta.url), 'utf8');
const source = js('portfolio/product-demo.js');
const apiModule = js('api/product-demo-api.js');
// The real shared API client (without its authenticated-surface bootstrap) carries CSRF and problem details.
const clientSource = js('api/taxonomy-api-client.js');
const clientCore = clientSource.slice(0, clientSource.indexOf('(function loadAuthenticatedUiSurfaces'));

function node(tag = 'div') {
    const listeners = new Map();
    return {
        tagName: tag, children: [], dataset: {}, textContent: '', className: '', id: '',
        classList: {remove() {}, add() {}},
        append(...children) { this.children.push(...children); },
        prepend(child) { this.children.unshift(child); },
        replaceChildren(...children) { this.children = children; },
        setAttribute(name, value) { this[name] = value; },
        insertAdjacentElement(position, child) { this.sibling = child; },
        addEventListener(name, fn) { listeners.set(name, fn); },
        click() { return listeners.get('click')?.(); },
        closest(selector) { return selector === '#' + this.id ? this : null; },
        text() { return [this.textContent, ...this.children.map(child => child.text ? child.text() : '')].join(' '); }
    };
}

const demoRun = {
    id: 'run-1', projectId: 7, requirementId: 9, banner: {de: 'DEMODUS · Arbeitszeiterfassung', en: 'DEMO MODE · Working-time recording'},
    stations: [{id: 'start', de: 'Start', en: 'Start'}], demoDecisions: [{question: 'F-01', values: ['Alle Mitarbeitenden'], rationale: 'Szenario'}],
    demoReviews: [{wording: 'GPS', action: 'REJECT', rationale: 'Datenschutz'}], activeProvider: 'DEMO.TIME-TRACKING-V1.R0',
    currentRequirementVersion: 1, closedAt: null
};

function harness({pathname = '/', basePath = '', input = '', responses = {}, route = null} = {}) {
    const nodes = new Map();
    const main = node('main');
    const businessText = Object.assign(node('textarea'), {id: 'businessText', value: input});
    const analyze = Object.assign(node('button'), {id: 'analyzeBtn'});
    nodes.set('businessText', businessText);
    nodes.set('analyzeBtn', analyze);
    const windowListeners = new Map();
    const requests = [];
    const assigned = [];
    const fetch = async (url, init) => {
        requests.push({url, method: init?.method || 'GET', body: init?.body, headers: new Headers(init?.headers)});
        const key = (init?.method || 'GET') + ' ' + url;
        const body = responses[key];
        const json = {'Content-Type': 'application/json'};
        if (body === undefined) return new Response(JSON.stringify({detail: 'Not found', code: 'DEMO_RUN_NOT_FOUND'}),
            {status: 404, headers: json});
        return new Response(JSON.stringify(body), {status: 200, headers: json});
    };
    const document = {
        readyState: 'complete', documentElement: {lang: 'de'},
        getElementById: id => nodes.get(id) || null,
        createElement: node,
        querySelector: selector => selector === 'main' ? main
            : selector === 'meta[name="_csrf"]' ? {content: 'token'} : null,
        addEventListener() {},
        dispatchEvent() { return true; }
    };
    const window = {
        fetch,
        location: {pathname, search: '?lang=de', href: 'https://taxonomy.example.test' + pathname,
            origin: 'https://taxonomy.example.test', assign: url => assigned.push(url)},
        TaxonomyI18n: {getBasePath: () => basePath, resolveUrl: path => basePath + path},
        // Published by requirement-detail.js on requirement pages only.
        TaxonomyRequirementDetail: route ? {route} : undefined,
        addEventListener: (name, fn, capture) => windowListeners.set(name + (capture ? ':capture' : ''), fn)
    };
    const context = vm.createContext({window, document, fetch: (...args) => window.fetch(...args), URL, URLSearchParams,
        Request, Response, Headers, AbortController, DOMException, CustomEvent: class { constructor(type, o = {}) { this.type = type; this.detail = o.detail; } },
        crypto: {randomUUID: () => 'request-id'}, setTimeout, clearTimeout, Date, Event: class {}, JSON, Math, String, Number,
        Object, Promise, Array, console});
    vm.runInContext(clientCore, context);
    vm.runInContext(apiModule, context);
    vm.runInContext(source, context);
    return {window, document, main, businessText, analyze, requests, assigned, windowListeners, nodes};
}

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

test('the trimmed command starts an isolated demo instead of an AI analysis', async () => {
    const h = harness({input: '  ProduktDemo \n', responses: {'POST /api/product-demo/runs': demoRun}});
    let prevented = false, stopped = false;
    h.windowListeners.get('click:capture')({target: h.analyze,
        preventDefault() { prevented = true; }, stopImmediatePropagation() { stopped = true; }});
    await flush();
    assert.ok(prevented && stopped, 'the provider-backed analysis must not start');
    assert.equal(h.requests.length, 1);
    assert.equal(h.requests[0].url, '/api/product-demo/runs');
    assert.equal(h.requests[0].method, 'POST');
    assert.equal(h.requests[0].headers.get('X-CSRF-TOKEN'), 'token', 'CSRF comes from the shared API client');
    assert.equal(h.businessText.value, '  ProduktDemo \n', 'the typed draft is never rewritten');
    assert.deepEqual(h.assigned, ['/projects/7/requirements/9?lang=de&demo=run-1']);
});

test('ordinary requirement text keeps the normal analysis path', async () => {
    const h = harness({input: 'Wir benötigen eine Produktdemo für Kunden.'});
    let prevented = false;
    h.windowListeners.get('click:capture')({target: h.analyze, preventDefault() { prevented = true; }, stopImmediatePropagation() {}});
    await flush();
    assert.equal(prevented, false);
    assert.equal(h.requests.length, 0);
    assert.equal(h.window.TaxonomyProductDemo.isDemoCommand('produktdemo x'), false);
});

test('a failed start is reported and does not navigate', async () => {
    const h = harness({input: 'produktdemo'});
    h.windowListeners.get('click:capture')({target: h.analyze, preventDefault() {}, stopImmediatePropagation() {}});
    await flush(); await flush();
    assert.deepEqual(h.assigned, []);
    assert.match(h.businessText.sibling.textContent, /nicht gestartet werden: Not found/);
});

test('demo requirement pages show a persistent banner under the base path', async () => {
    const h = harness({pathname: '/taxonomy/projects/7/requirements/9', basePath: '/taxonomy',
        route: {projectId: 7, requirementId: 9}, responses: {'GET /api/product-demo/runs?projectId=7': [demoRun]}});
    await flush(); await flush();
    const banner = h.main.children[0];
    assert.ok(banner, 'banner must be rendered');
    assert.equal(banner.dataset.demoRun, 'run-1');
    const content = banner.text();
    assert.match(content, /DEMODUS/);
    assert.match(content, /DEMO\.TIME-TRACKING-V1\.R0/);
    assert.match(content, /F-01: Alle Mitarbeitenden/);
    assert.match(content, /REJECT: GPS/);
});

const findAll = (root, predicate) => [root, ...root.children.flatMap(child => child.children ? findAll(child, predicate) : [])]
    .filter(predicate);

test('only accounts with architecture mutation rights are offered to end the demo', async () => {
    for (const [allowed, expected] of [[true, 1], [false, 0]]) {
        const h = harness({pathname: '/projects/7/requirements/9', route: {projectId: 7, requirementId: 9},
            responses: {'GET /api/product-demo/runs?projectId=7': [demoRun],
                'GET /api/account/me': {architectureMutationAllowed: allowed}}});
        await flush(); await flush(); await flush();
        const banner = h.main.children[0];
        assert.ok(banner, 'banner must be rendered');
        assert.equal(findAll(banner, n => 'demoClose' in n.dataset).length, expected,
            'close button for architectureMutationAllowed=' + allowed);
        assert.equal(findAll(banner, n => 'demoCompare' in n.dataset).length, 1, 'comparison stays readable');
    }
});

test('an unavailable account fails closed and a closed demo never asks for it', async () => {
    const unknown = harness({pathname: '/projects/7/requirements/9', route: {projectId: 7, requirementId: 9},
        responses: {'GET /api/product-demo/runs?projectId=7': [demoRun]}});
    await flush(); await flush(); await flush();
    assert.equal(findAll(unknown.main.children[0], n => 'demoClose' in n.dataset).length, 0);
    const closed = harness({pathname: '/projects/7/requirements/9', route: {projectId: 7, requirementId: 9},
        responses: {'GET /api/product-demo/runs?projectId=7': [{...demoRun, closedAt: '2026-10-11T08:00:00Z'}]}});
    await flush(); await flush(); await flush();
    assert.deepEqual(closed.requests.map(r => r.url), ['/api/product-demo/runs?projectId=7']);
    assert.equal(findAll(closed.main.children[0], n => 'demoClose' in n.dataset).length, 0);
});

test('normal requirement pages remain unlabelled', async () => {
    const h = harness({pathname: '/projects/8/requirements/1', route: {projectId: 8, requirementId: 1},
        responses: {'GET /api/product-demo/runs?projectId=8': []}});
    await flush(); await flush();
    assert.equal(h.main.children.length, 0);
    assert.deepEqual(h.requests.map(r => r.url), ['/api/product-demo/runs?projectId=8'], 'only the project-scoped lookup is used');
});

test('pages without a requirement route never query demo runs', async () => {
    const h = harness({pathname: '/'});
    await flush(); await flush();
    assert.equal(h.requests.length, 0);
});
