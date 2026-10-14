import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

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
