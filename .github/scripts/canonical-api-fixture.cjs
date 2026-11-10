/* Load the production transport in isolated feature fixtures, as pages do. */
const {readFileSync} = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const disposers = new Set();
const apiRoot = path.resolve(__dirname, '../../taxonomy-app/src/main/resources/static/js');
function install(context, clients = []) {
    const window = context.window || context;
    context.window ||= window;
    const document = context.document;
    const FixtureURL = context.URL?.prototype ? context.URL : class extends URL {};
    if (context.URL && !context.URL.prototype) Object.assign(FixtureURL, context.URL);
    Object.assign(context, {URL: FixtureURL,
        URLSearchParams, Headers, Request, Response, FormData, AbortController, DOMException,
        CustomEvent: context.CustomEvent || class {constructor(type, options) {this.type = type; this.detail = options?.detail;}},
        setTimeout: context.setTimeout || setTimeout, clearTimeout: context.clearTimeout || clearTimeout});
    if (!Object.prototype.hasOwnProperty.call(context, 'crypto')) context.crypto = require('node:crypto').webcrypto;
    window.location ||= {href: 'https://example.test/', origin: 'https://example.test', pathname: '/', search: ''};
    window.location.href ||= 'https://example.test' + (window.location.pathname || '/');
    window.location.origin ||= new URL(window.location.href).origin;
    const addListener = window.addEventListener?.bind(window) || (() => {});
    window.addEventListener = function (type, listener, options) {
        if (type === 'pagehide') disposers.add(() => listener({type: 'pagehide'}));
        return addListener(type, listener, options);
    };
    document.addEventListener ||= () => {};
    document.dispatchEvent ||= () => true;
    document.querySelector ||= () => null;
    if (!window.TaxonomyI18n && context.TaxonomyI18n) window.TaxonomyI18n = context.TaxonomyI18n;
    const fetch = window.fetch || context.fetch;
    window.fetch = function (url, init) {
        return Promise.resolve(fetch(url, init)).then(response => {
            // Earlier domain fixtures omit HTTP metadata unrelated to their assertions.
            if (response.status === undefined) response.status = response.ok === false ? 500 : 200;
            if (response.ok === undefined) response.ok = response.status >= 200 && response.status < 300;
            if (!response.headers) response.headers = new Headers({'Content-Type': 'application/json'});
            if (!response.clone) response.clone = () => response;
            if (!response.text && response.json) response.text = async () => JSON.stringify(await response.json());
            return response;
        });
    };
    if (!vm.isContext(context)) vm.createContext(context);
    const source = readFileSync(path.join(apiRoot, 'api/taxonomy-api-client.js'), 'utf8');
    vm.runInContext(source.slice(0, source.indexOf('(function loadAuthenticatedUiSurfaces')), context);
    for (const client of clients) vm.runInContext(readFileSync(path.join(apiRoot, client), 'utf8'), context);
    return context;
}
function dispose() {
    const pending = [...disposers]; disposers.clear();
    pending.forEach(close => close());
}
module.exports = {install, dispose};
