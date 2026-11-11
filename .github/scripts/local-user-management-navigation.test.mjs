import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test, {afterEach} from 'node:test';
const cleanups = [];
afterEach(() => { while (cleanups.length) cleanups.pop()(); });
import vm from 'node:vm';
import canonical from './canonical-api-fixture.cjs';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/security/taxonomy-role-surface.js', import.meta.url), 'utf8');

function surface(initial, { i18n = true, basePath = "/taxonomy" } = {}) {
  const elements = new Map();
  function element() {
    return {
      children: [], dataset: {}, nodeType: 1,
      classList: { toggle() {}, remove() {} },
      setAttribute() {}, querySelectorAll() { return []; },
      append(...children) { children.forEach(child => this.appendChild(child)); },
      appendChild(child) { this.children.push(child); if (child.id) elements.set(child.id, child); },
      insertBefore(child) { this.appendChild(child); },
      remove() { elements.delete(this.id); this.children.forEach(child => child.remove()); }
    };
  }
  const adminTab = element();
  elements.set('tab-admin', adminTab);
  let response = initial;
  const sandbox = {
    document: { currentScript: { src: 'https://example.test' + basePath + '/js/security/taxonomy-role-surface.js?v=1139' }, readyState: 'loading', body: element(), addEventListener() {}, dispatchEvent() {},
      createElement: element, querySelectorAll() { return []; },
      getElementById(id) { return elements.get(id) || null; } },
    Node: { ELEMENT_NODE: 1 }, MutationObserver: class { observe() {} },
    URL, CustomEvent: class {}, console: { error() {} },
    window: { location: { href: 'https://example.test' + basePath + '/' }, alert() {}, addEventListener() {}, setTimeout() {},
      TaxonomyApiClient: { getJson() { return response instanceof Error ? Promise.reject(response) : Promise.resolve(response); } },
      TaxonomyI18n: { ready: () => Promise.resolve(), t: key => key, resolveUrl: path => '/taxonomy' + path } }
  };
  if (!i18n) delete sandbox.window.TaxonomyI18n;
  vm.runInNewContext(source, sandbox);
  // currentScript is no longer available when the asynchronous account request finishes.
  sandbox.document.currentScript = null;
  return { elements, api: sandbox.window.TaxonomyRoleSurface,
    async refresh(next = response) { response = next; await this.api.refresh(); await Promise.resolve(); } };
}

const admin = { username: 'admin', roles: ['ADMIN'], administrator: true, architectureMutationAllowed: true };

test('local administrator gets a context-relative navigation link', async () => {
  const ui = surface({ ...admin, localUserManagementAllowed: true });
  await ui.refresh();
  assert.equal(ui.elements.get('localUserManagementLink').href, '/taxonomy/admin/users');
  await ui.refresh();
  assert.equal(ui.elements.get('tab-admin').children.filter(el => el.id === 'localUserManagementAdminEntry').length, 1);
});

test('absent or disabled capability explains account management without exposing a create link', async () => {
  for (const flag of [undefined, false]) {
    const ui = surface({ ...admin, localUserManagementAllowed: flag });
    await ui.refresh();
    assert.equal(ui.elements.get('localUserManagementAdminEntry')?.dataset.managementMode, 'unavailable');
    assert.equal(ui.elements.has('localUserManagementLink'), false);
  }
});

test('non-administrators cannot expose the link even with a malformed true capability', async () => {
  const ui = surface({ username: 'reader', roles: ['USER'], administrator: false, localUserManagementAllowed: true });
  await ui.refresh();
  assert.equal(ui.elements.has('localUserManagementAdminEntry'), false);
});

test('profile change removes an already rendered link', async () => {
  const ui = surface({ ...admin, localUserManagementAllowed: true });
  await ui.refresh();
  await ui.refresh({ ...admin, localUserManagementAllowed: false });
  assert.equal(ui.elements.get('localUserManagementAdminEntry')?.dataset.managementMode, 'unavailable');
  assert.equal(ui.elements.has('localUserManagementLink'), false);
});

test('failed capability refresh removes an already rendered link', async () => {
  const ui = surface({ ...admin, localUserManagementAllowed: true });
  await ui.refresh();
  assert.equal(ui.elements.has('documentTemplateAdminEntry'), true);
  await ui.refresh(new Error('unavailable'));
  assert.equal(ui.elements.has('localUserManagementAdminEntry'), false);
  assert.equal(ui.elements.has('documentTemplateAdminEntry'), false);
  assert.equal(ui.api.getContext().localUserManagementAllowed, false);
});

for (const basePath of ['', '/taxonomy', '/gateway/taxonomy']) {
  test('navigation keeps base path without the translation module: ' + (basePath || '/'), async () => {
    const ui = surface({ ...admin, localUserManagementAllowed: true }, { i18n: false, basePath });
    await ui.refresh();
    assert.equal(ui.elements.get('localUserManagementLink').href, basePath + '/admin/users');
  });
}

// Run the real browse/account integration with a deliberately pending account read.
// A denied account must redirect; an unresolved account must not destroy a deep link.
const browseSource = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-browse.js', import.meta.url), 'utf8');
const navigationSource = browseSource.slice(
  browseSource.indexOf('    let administrator = false;'),
  browseSource.indexOf('    function populateProviderDropdown('));

function pendingNavigation(initialPage) {
  let currentPage = initialPage;
  let allowed = false;
  let resolveAccount;
  let rejectAccount;
  const listeners = new Map();
  const bodyClasses = new Set();
  const ready = new Promise((resolve, reject) => { resolveAccount = resolve; rejectAccount = reject; });
  const sandbox = {
    document: {
      body: { classList: { toggle(name, enabled) { enabled ? bodyClasses.add(name) : bodyClasses.delete(name); } } },
      querySelectorAll() { return []; },
      getElementById(id) { return { classList: { contains(name) { return name === 'd-none' && id !== 'tab-' + currentPage; } } }; },
      addEventListener(type, listener) { listeners.set(type, listener); }
    },
    window: {
      TaxonomyRoleSurface: { ready, isAdministrator() { return allowed; } },
      navigateToPage(page) { currentPage = page; }
    },
    initPromptEditor() {}, loadDiagnostics() {}
  };
  vm.runInNewContext(navigationSource + '\nwindow.checkAdminStatus = checkAdminStatus;', sandbox);
  return {
    start: () => sandbox.window.checkAdminStatus(),
    page: () => currentPage,
    unlocked: () => bodyClasses.has('admin-unlocked'),
    resolve(administrator) { allowed = administrator; resolveAccount({ administrator }); },
    reject() { rejectAccount(new Error('Account unavailable')); },
    revoke() { allowed = false; listeners.get('taxonomy:roles-ready')({ detail: { administrator: false } }); }
  };
}

for (const page of ['preferences', 'admin']) {
  test('pending account preserves ' + page + ' deep link without unlocking controls', async () => {
    const ui = pendingNavigation(page);
    const checked = ui.start();
    assert.equal(ui.page(), page);
    assert.equal(ui.unlocked(), false);
    ui.resolve(true);
    await checked;
    assert.equal(ui.page(), page);
    assert.equal(ui.unlocked(), true);
    ui.revoke();
    assert.equal(ui.page(), 'analyze');
    assert.equal(ui.unlocked(), false);
  });

  test('settled denial redirects ' + page + ' without unlocking controls', async () => {
    const ui = pendingNavigation(page);
    const checked = ui.start();
    ui.resolve(false);
    await checked;
    assert.equal(ui.page(), 'analyze');
    assert.equal(ui.unlocked(), false);
  });

  test('failed account read redirects ' + page + ' without unlocking controls', async () => {
    const ui = pendingNavigation(page);
    const checked = ui.start();
    ui.reject();
    await checked;
    assert.equal(ui.page(), 'analyze');
    assert.equal(ui.unlocked(), false);
  });
}

const indexTemplate = readFileSync(new URL('../../taxonomy-app/src/main/resources/templates/index.html', import.meta.url), 'utf8');
const pageNavigationSource = indexTemplate.match(/<!-- Page Navigation Script -->\s*<script>([\s\S]*?)<\/script>/)?.[1];
assert.ok(pageNavigationSource, 'Exercise the complete production page router');

// Both modules and the router run unchanged. Only DOM/network boundaries are
// replaced so a dynamically loaded RoleSurface can arrive after DOMContentLoaded
// but before the browse module's independent fallback account response settles.
function lateRoleSurfaceNavigation(page) {
  const elements = new Map(), listeners = new Map(), requests = [], activations = [];
  const lifecycle = new Map();
  const deferred = () => {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return {promise, resolve, reject};
  };
  const fallback = deferred(), authority = deferred();
  let accountRead = authority.promise;
  let location = new URL('https://example.test/#' + page);
  function element(id) {
    const classes = new Set(), attributes = new Map();
    const value = {
      id, children: [], dataset: {}, style: {}, nodeType: 1, hidden: false, disabled: false,
      classList: {
        contains: name => classes.has(name),
        add: name => classes.add(name), remove: name => classes.delete(name),
        toggle(name, enabled) { enabled ? classes.add(name) : classes.delete(name); }
      },
      setAttribute(name, content) { attributes.set(name, content); },
      getAttribute: name => attributes.get(name) ?? null,
      addEventListener() {}, querySelectorAll() { return []; }, querySelector() { return null; },
      append(...children) { children.forEach(child => this.appendChild(child)); },
      appendChild(child) { this.children.push(child); if (child.id) elements.set(child.id, child); },
      insertBefore(child) { this.appendChild(child); },
      remove() { elements.delete(this.id); this.children.forEach(child => child.remove()); }
    };
    if (id) elements.set(id, value);
    return value;
  }
  const body = element();
  for (const name of ['analyze', 'architecture', 'graph', 'versions', 'dsl-editor', 'help', 'admin', 'preferences']) {
    element('tab-' + name).classList.add('d-none');
  }
  for (const id of ['leftPanel', 'rightPanel', 'analyzeBtn', 'expandAll', 'collapseAll', 'diagnosticsContent']) element(id);
  const adminControl = element('wsCreateBtn');
  adminControl.hidden = adminControl.disabled = true;
  const document = {
    body, readyState: 'loading', currentScript: null,
    createElement: () => element(),
    getElementById: id => elements.get(id) || null,
    querySelector() { return null; },
    querySelectorAll(selector) { return selector === '#wsCreateBtn' || selector === '.admin-only' ? [adminControl] : []; },
    addEventListener(type, listener) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(listener);
    },
    dispatchEvent(event) { for (const listener of listeners.get(event.type) || []) listener(event); }
  };
  const sandbox = {
    document, URL, AbortController, Promise, setTimeout, clearTimeout,
    setInterval() {}, clearInterval() {},
    addEventListener(type, callback) {if (!lifecycle.has(type)) lifecycle.set(type, []); lifecycle.get(type).push(callback);}, alert() {},
    CustomEvent: class { constructor(type, options = {}) { this.type = type; this.detail = options.detail; } },
    MutationObserver: class { observe() {} }, Node: {ELEMENT_NODE: 1},
    console: {error() {}},
    TaxonomyState: {}, TaxonomyUtils: {escapeHtml: String},
    TaxonomyI18n: {t: key => key, ready: () => Promise.resolve(), resolveUrl: path => path},
    fetch(path) {
      requests.push(path);
      if (path !== '/api/account/me') return new Promise(() => {});
      return requests.filter(path => path === '/api/account/me').length === 1 ? fallback.promise
        : accountRead.then(value => ({ok:true, json:async () => value}));
    },
    get location() { return location; },
    history: {replaceState(_state, _title, value) { location = new URL(value, location); }}
  };
  sandbox.window = sandbox;
  const context = vm.createContext(sandbox);
  document.addEventListener('taxonomy:page-activated', event => activations.push(event.detail.page));
  canonical.install(context, ['api/reference-api.js', 'api/analysis-api.js', 'api/export-api.js', 'api/proposals-api.js']);
  cleanups.push(() => {for (const callback of lifecycle.get('pagehide') || []) callback({persisted:false});});
  vm.runInContext(browseSource, context, {filename: 'taxonomy-browse.js'});
  vm.runInContext(pageNavigationSource, context, {filename: 'index-page-navigation.js'});
  document.dispatchEvent({type: 'DOMContentLoaded'});
  assert.equal(requests.filter(path => path === '/api/account/me').length, 1,
    'DOMContentLoaded must take the real account fallback before RoleSurface exists');
  document.readyState = 'complete';
  vm.runInContext(source, context, {filename: 'taxonomy-role-surface.js'});
  return {
    visible: name => !elements.get('tab-' + name).classList.contains('d-none'),
    page: () => location.hash.slice(1),
    unlocked: () => body.classList.contains('admin-unlocked'),
    administrator: () => sandbox.TaxonomyRoleSurface.isAdministrator(),
    adminControl, requests, activations,
    async settleFallback(failed) {
      if (failed) fallback.reject(new Error('Older fallback failed'));
      else fallback.resolve({ok: true, json: async () => ({administrator: true, roles: ['ADMIN']})});
      await new Promise(resolve => setImmediate(resolve));
    },
    async settleAuthority(result) {
      if (result === 'ERROR') authority.reject(new Error('Authoritative account unavailable'));
      else authority.resolve({username: result.toLowerCase(), roles: [result], administrator: result === 'ADMIN'});
      await sandbox.TaxonomyRoleSurface.ready;
      await new Promise(resolve => setImmediate(resolve));
    },
    async revoke() {
      accountRead = Promise.resolve({username: 'reader', roles: ['USER'], administrator: false});
      await sandbox.TaxonomyRoleSurface.refresh();
    }
  };
}

for (const page of ['preferences', 'admin']) {
  for (const failedFallback of [false, true]) {
    for (const result of ['ADMIN', 'USER', 'ERROR']) {
      test(`late role bootstrap preserves ${page} through ${failedFallback ? 'failed' : 'successful'} fallback until ${result}`, async () => {
        const ui = lateRoleSurfaceNavigation(page);
        assert.equal(ui.visible(page), true);
        await ui.settleFallback(failedFallback);
        assert.equal(ui.visible(page), true, 'Pending authority must preserve the direct-link pane');
        assert.equal(ui.page(), page, 'Pending authority must preserve the requested route');
        assert.equal(ui.unlocked(), false, 'Preserving a route must not unlock administrator controls');
        assert.equal(ui.administrator(), false);
        assert.equal(ui.adminControl.hidden, true);
        assert.equal(ui.adminControl.disabled, true);
        assert.equal(ui.requests.includes('/api/diagnostics'), false);
        await ui.settleAuthority(result);
        const allowed = result === 'ADMIN';
        assert.equal(ui.visible(page), allowed);
        assert.equal(ui.page(), allowed ? page : 'analyze');
        assert.equal(ui.unlocked(), allowed);
        assert.equal(ui.administrator(), allowed);
        assert.equal(ui.adminControl.hidden, !allowed);
        assert.equal(ui.adminControl.disabled, !allowed);
        assert.equal(ui.requests.includes('/api/diagnostics'), allowed);
        if (allowed) {
          await ui.revoke();
          assert.equal(ui.visible(page), false, 'A later confirmed denial must still leave the administrative pane');
          assert.equal(ui.page(), 'analyze');
          assert.equal(ui.unlocked(), false);
          assert.equal(ui.adminControl.disabled, true);
        }
      });
    }
  }
}
