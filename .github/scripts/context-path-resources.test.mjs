import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const resources = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const [i18nSource, utilsSource, aboutSource] = await Promise.all([
    'taxonomy-i18n.js', 'shared/taxonomy-utils.js', 'shared/taxonomy-about.js'
].map(path => readFile(new URL(path, resources), 'utf8')));
const origin = 'https://taxonomy.example.test';

// Model the link attributes and URL resolution used by the browser. The real
// bootstrap and utilities run unchanged; unrelated page features have no DOM.
async function boot({ prefix = '', page = '/', stylesheet, withI18n = true, responses = {} }) {
    const pageUrl = origin + page;
    const links = [];
    const appendedStylesheets = [];
    const listeners = new Map();
    const elements = new Map();
    const requests = [];

    function createElement(tag) {
        const attributes = new Map();
        const link = {
            tagName: tag.toUpperCase(),
            dataset: {},
            children: [],
            appendChild(child) { this.children.push(child); return child; },
            getAttribute(name) {
                if (name.startsWith('data-')) {
                    const key = name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
                    return this.dataset[key] ?? null;
                }
                return attributes.get(name) ?? null;
            },
            setAttribute(name, value) {
                if (name.startsWith('data-')) {
                    const key = name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
                    this.dataset[key] = String(value);
                } else {
                    attributes.set(name, String(value));
                }
            }
        };
        Object.defineProperties(link, {
            rel: { get: () => attributes.get('rel') || '', set: value => attributes.set('rel', value) },
            href: { get: () => new URL(attributes.get('href'), pageUrl).href, set: value => attributes.set('href', value) }
        });
        return link;
    }

    function matches(link, selector) {
        const tag = selector.match(/^[a-z]+/i)?.[0];
        if (tag && link.tagName !== tag.toUpperCase()) return false;
        assert.equal(selector.replace(/^[a-z]+/i, '').replace(/\[[^\]]+\]/g, ''), '',
            'The small DOM only supports tag and attribute selectors');
        return [...selector.matchAll(/\[([\w-]+)(?:(~=|=)["']([^"']*)["'])?\]/g)]
            .every(([, name, operator, value]) => {
                const actual = link.getAttribute(name);
                if (!operator) return actual !== null;
                return operator === '~=' ? (actual || '').split(/\s+/).includes(value) : actual === value;
            });
    }

    let existingStylesheet;
    if (stylesheet) {
        existingStylesheet = createElement('link');
        existingStylesheet.rel = 'stylesheet';
        existingStylesheet.href = stylesheet;
        links.push(existingStylesheet);
    }
    const document = {
        currentScript: { src: `${origin}${prefix}/js/taxonomy-i18n.js` },
        readyState: 'loading',
        documentElement: { lang: 'en' },
        cookie: '',
        querySelector(selector) { return links.find(link => matches(link, selector)) || null; },
        getElementById(id) { return elements.get(id) || null; },
        createElement,
        head: {
            appendChild(link) {
                links.push(link);
                if (link.rel.split(/\s+/).includes('stylesheet')) appendedStylesheets.push(link.href);
                return link;
            }
        },
        addEventListener(event, listener) {
            if (!listeners.has(event)) listeners.set(event, []);
            listeners.get(event).push(listener);
        },
        dispatchEvent() {}
    };
    const context = vm.createContext({
        document, URL, console, CustomEvent: class {},
        location: { href: pageUrl },
        localStorage: { getItem() { return null; } },
        async fetch(url) {
            requests.push(url);
            return { ok: true, async json() { return responses[url] || {}; } };
        }
    });
    context.window = context;
    if (withI18n) {
        vm.runInContext(i18nSource, context, { filename: 'taxonomy-i18n.js' });
        await context.TaxonomyI18n.ready();
    }
    document.currentScript = { src: `${origin}${prefix}/js/shared/taxonomy-utils.js` };
    const initializeAgain = () => vm.runInContext(utilsSource, context, { filename: 'taxonomy-utils.js' });
    initializeAgain();
    document.readyState = 'interactive';
    for (const listener of listeners.get('DOMContentLoaded') || []) listener();
    return { links, appendedStylesheets, existingStylesheet, initializeAgain, context, document, elements, requests };
}

for (const { prefix, stylesheet } of [
    { prefix: '', stylesheet: '/css/taxonomy-ergonomics.css' },
    { prefix: '/taxonomy', stylesheet: '/taxonomy/css/taxonomy-ergonomics.css' },
    { prefix: '/team/taxonomy', stylesheet: '/team/taxonomy/css/taxonomy-ergonomics.css' }
]) {
    for (const route of ['/', '/projects/42/architecture-workbench/snapshot-7']) {
        const page = prefix + route;
        test(`missing ergonomics stylesheet loads once at the application path from ${page}`, async () => {
            const app = await boot({ prefix, page });
            app.initializeAgain();
            assert.deepEqual(app.appendedStylesheets, [origin + stylesheet]);
            assert.equal(app.links.length, 1);
        });

        test(`rendered ergonomics stylesheet is reused without another request from ${page}`, async () => {
            const app = await boot({ prefix, page, stylesheet });
            app.initializeAgain();
            assert.deepEqual(app.appendedStylesheets, []);
            assert.equal(app.links.length, 1);
            assert.equal(app.links[0], app.existingStylesheet);
            assert.equal(app.existingStylesheet.getAttribute('href'), stylesheet);
        });
    }
}

test('root deployment loads ergonomics once without the optional i18n bootstrap', async () => {
    const app = await boot({ page: '/projects/42', withI18n: false });
    app.initializeAgain();
    assert.deepEqual(app.appendedStylesheets, [origin + '/css/taxonomy-ergonomics.css']);
    assert.equal(app.links.length, 1);
});

test('root deployment reuses its rendered stylesheet without the optional i18n bootstrap', async () => {
    const app = await boot({
        page: '/projects/42', stylesheet: '/css/taxonomy-ergonomics.css', withI18n: false
    });
    app.initializeAgain();
    assert.deepEqual(app.appendedStylesheets, []);
    assert.equal(app.links.length, 1);
});

const aboutResources = {
    projectLicenseUrl: '/api/about/license',
    noticeUrl: '/api/about/notice',
    thirdPartyNoticesUrl: '/api/about/third-party',
    runtimeThirdPartyLicensesUrl: '/api/about/runtime-licenses',
    sbomJsonUrl: '/api/about/sbom.json',
    sbomXmlUrl: '/api/about/sbom.xml'
};

async function renderAboutLinks(prefix, data) {
    const app = await boot({
        prefix, page: prefix + '/projects/42/architecture-workbench/snapshot-7',
        responses: { [prefix + '/api/about']: data }
    });
    const legal = app.document.createElement('div');
    app.elements.set('aboutLegalResources', legal);
    app.elements.set('aboutThirdPartyContent', { parentNode: {} });
    app.document.currentScript = { src: `${origin}${prefix}/js/shared/taxonomy-about.js` };
    vm.runInContext(aboutSource, app.context, { filename: 'taxonomy-about.js' });
    app.context.TaxonomyAbout.loadAboutInfo();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(app.requests.at(-1), prefix + '/api/about');
    return legal.children.flatMap(child => child.children).filter(child => child.tagName === 'A');
}

for (const prefix of ['', '/taxonomy', '/team/taxonomy']) {
    test(`About legal and SBOM links retain application prefix ${prefix || '/'}`, async () => {
        const links = await renderAboutLinks(prefix, aboutResources);
        assert.deepEqual(links.map(link => link.href), [
            `${origin}${prefix}/api/about/license`,
            `${origin}${prefix}/api/about/notice`,
            `${origin}${prefix}/api/about/third-party`,
            `${origin}${prefix}/api/about/runtime-licenses`,
            `${origin}${prefix}/api/about/sbom.json`,
            `${origin}${prefix}/api/about/sbom.xml`
        ]);
    });
}

test('About leaves external and already-prefixed resource URLs unchanged', async () => {
    const links = await renderAboutLinks('/team/taxonomy', {
        projectLicenseUrl: 'https://licenses.example.test/LICENSE',
        noticeUrl: '/team/taxonomy/api/about/notice'
    });
    assert.deepEqual(links.map(link => link.href), [
        'https://licenses.example.test/LICENSE',
        `${origin}/team/taxonomy/api/about/notice`
    ]);
});
