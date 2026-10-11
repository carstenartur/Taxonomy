import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/product-demo.js', import.meta.url), 'utf8');

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

function harness({pathname = '/', basePath = '', input = '', responses = {}} = {}) {
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
        requests.push({url, method: init?.method || 'GET', body: init?.body, headers: init?.headers});
        const key = (init?.method || 'GET') + ' ' + url;
        const body = responses[key];
        if (body === undefined) return {ok: false, status: 404, statusText: 'Not Found', json: async () => ({})};
        return {ok: true, status: 200, json: async () => body};
    };
    const document = {
        readyState: 'complete', documentElement: {lang: 'de'},
        getElementById: id => nodes.get(id) || null,
        createElement: node,
        querySelector: selector => selector === 'main' ? main
            : selector === 'meta[name="_csrf"]' ? {content: 'token'} : null,
        addEventListener() {}
    };
    const window = {
        location: {pathname, search: '?lang=de', assign: url => assigned.push(url)},
        TaxonomyI18n: {getBasePath: () => basePath, resolveUrl: path => basePath + path},
        addEventListener: (name, fn, capture) => windowListeners.set(name + (capture ? ':capture' : ''), fn)
    };
    vm.runInNewContext(source, {window, document, fetch, URLSearchParams, Event: class {}, JSON, Math, String, Number});
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
    assert.equal(h.requests[0].headers['X-CSRF-TOKEN'], 'token');
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
    assert.match(h.businessText.sibling.textContent, /nicht gestartet/);
});

test('demo requirement pages show a persistent banner under the base path', async () => {
    const h = harness({pathname: '/taxonomy/projects/7/requirements/9', basePath: '/taxonomy',
        responses: {'GET /api/product-demo/runs': [demoRun]}});
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

test('normal requirement pages remain unlabelled', async () => {
    const h = harness({pathname: '/projects/8/requirements/1', responses: {'GET /api/product-demo/runs': [demoRun]}});
    await flush(); await flush();
    assert.equal(h.main.children.length, 0);
});
