import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/security/taxonomy-role-surface.js', import.meta.url), 'utf8');

function surface(initial, { i18n = true, basePath = "/taxonomy" } = {}) {
  const elements = new Map();
  function element() {
    return {
      children: [], nodeType: 1,
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

test('absent or disabled capability never creates the local administration link', async () => {
  for (const flag of [undefined, false]) {
    const ui = surface({ ...admin, localUserManagementAllowed: flag });
    await ui.refresh();
    assert.equal(ui.elements.has('localUserManagementAdminEntry'), false);
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
  assert.equal(ui.elements.has('localUserManagementAdminEntry'), false);
});

test('failed capability refresh removes an already rendered link', async () => {
  const ui = surface({ ...admin, localUserManagementAllowed: true });
  await ui.refresh();
  await ui.refresh(new Error('unavailable'));
  assert.equal(ui.elements.has('localUserManagementAdminEntry'), false);
  assert.equal(ui.api.getContext().localUserManagementAllowed, false);
});

for (const basePath of ['', '/taxonomy', '/gateway/taxonomy']) {
  test('navigation keeps base path without the translation module: ' + (basePath || '/'), async () => {
    const ui = surface({ ...admin, localUserManagementAllowed: true }, { i18n: false, basePath });
    await ui.refresh();
    assert.equal(ui.elements.get('localUserManagementLink').href, basePath + '/admin/users');
  });
}
