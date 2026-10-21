const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const storage = new Map();
const source = fs.existsSync(process.argv[2]) ? fs.readFileSync(process.argv[2], 'utf8') : '';
function load(user, locale, disabledStorage = false) {
    const document = {
        readyState: 'complete', documentElement: {lang: locale},
        querySelector: () => user ? {content: user} : null,
        querySelectorAll: () => [], dispatchEvent() {}, addEventListener() {}
    };
    const window = {TaxonomyI18n: {getLocale: () => document.documentElement.lang, resolveUrl: p => p}};
    const localStorage = {
        getItem(key) {if (disabledStorage) throw Error('disabled'); return storage.get(key) ?? null;},
        setItem(key, value) {if (disabledStorage) throw Error('disabled'); storage.set(key, value);}
    };
    vm.runInNewContext(source, {window, document, localStorage, CustomEvent: class {}, console});
    assert.ok(window.TaxonomyContentLanguage, 'Independent content-language policy must be available');
    return {api: window.TaxonomyContentLanguage, document};
}
let {api, document} = load('alice', 'de');
assert.equal(api.analysis(), 'de');
assert.equal(api.documentLanguage('en'), 'en');
assert.equal(api.documentLanguage(null), null, 'Legacy evidence language stays unknown');
api.setPreference('analysis', 'en');
assert.equal(api.analysis(), 'en');
document.documentElement.lang = 'de';
assert.equal(api.analysis(), 'en', 'Fixed output is independent of UI');
assert.equal(load('alice', 'de').api.analysis(), 'en', 'Same user restores selection');
assert.equal(load('bob', 'de').api.analysis(), 'de', 'A different user keeps their default');
api.setPreference('document', 'de');
assert.equal(api.documentLanguage('en'), 'de');
api.setPreference('document', 'ui');
document.documentElement.lang = 'en';
assert.equal(api.documentLanguage('de'), 'en');
api.setPreference('analysis', 'ui');
document.documentElement.lang = 'de';
assert.equal(api.analysis(), 'de');
assert.throws(() => api.setPreference('analysis', 'fr'));
assert.throws(() => api.setPreference('document', 'unknown'));
const privateSession = load('carol', 'de', true).api;
privateSession.setPreference('analysis', 'en');
assert.equal(privateSession.analysis(), 'en', 'Disabled storage still supports the current page');
const anonymous = load(null, 'de').api;
anonymous.setPreference('analysis', 'en');
assert.equal(load(null, 'de').api.analysis(), 'de', 'No shared anonymous persistent preference');
console.log('Content-language selections, user isolation and legacy evidence verified.');
