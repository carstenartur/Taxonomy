import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-utils.js', import.meta.url), 'utf8');
const semanticsSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/security/taxonomy-ui-semantics.js', import.meta.url), 'utf8');
const window = {};
vm.runInNewContext(source, {
  window,
  document: { readyState: 'loading', addEventListener() {} }
});
const utils = window.TaxonomyUtils;
const container = (code, children = []) => ({ code, taxonomyRoot: code, level: 0, parentCode: null, children });
const node = (code, taxonomyRoot, children = []) => ({ code, taxonomyRoot, level: 1, parentCode: taxonomyRoot, children });

test('legacy root metadata identifies sub-taxonomy containers without names or prefix heuristics', () => {
  assert.equal(typeof utils.isTaxonomyContainer, 'function');
  for (const code of ['BP', 'BR', 'CP', 'CI', 'CO', 'CR', 'IP', 'UA']) {
    assert.equal(utils.isTaxonomyContainer(container(code)), true, code);
  }
  assert.equal(utils.isTaxonomyContainer(container('fixture-navigation-root')), true);
});

test('original BP-1000 and BR-1000 entries remain content despite matching their container names', () => {
  for (const code of ['BP', 'BR']) {
    const root = { ...container(code), name: 'Same display name' };
    const original = { ...node(`${code}-1000`, code), name: root.name };
    assert.equal(utils.isTaxonomyContainer(original), false);
    assert.equal(utils.isTaxonomyContainer({ ...original, level: 0, parentCode: null }), false);
  }
});

test('legacy classification requires complete matching root metadata and an absent parent', () => {
  const root = container('BP');
  for (const invalid of [
    null, undefined, {}, { code: 'BP' },
    { ...root, code: null }, { ...root, code: '', taxonomyRoot: '' },
    { ...root, code: '  ', taxonomyRoot: '  ' }, { ...root, taxonomyRoot: null },
    { ...root, taxonomyRoot: 'BR' }, { ...root, level: 1 },
    { ...root, level: undefined }, { ...root, level: '0' },
    { ...root, parentCode: 'BR' }, { ...root, parentCode: 0 }
  ]) {
    assert.equal(utils.isTaxonomyContainer(invalid), false, JSON.stringify(invalid));
  }
  for (const parentCode of [undefined, null, '', ' \t']) {
    assert.equal(utils.isTaxonomyContainer({ ...root, parentCode }), true);
  }
});

test('explicit API booleans take precedence over legacy metadata, including false', () => {
  assert.equal(utils.isTaxonomyContainer({ ...container('BP'), taxonomyContainer: false }), false);
  assert.equal(utils.isTaxonomyContainer({ taxonomyContainer: true }), true);
  assert.equal(utils.isTaxonomyContainer({ taxonomyContainer: false }), false);
  assert.equal(utils.isTaxonomyContainer({ taxonomyContainer: 'true' }), false);
  assert.equal(utils.isTaxonomyContainer({ ...container('BP'), taxonomyContainer: 'false' }), true);
});

test('content roots flatten only classified top-level containers and preserve order and node identity', () => {
  assert.equal(typeof utils.contentRoots, 'function');
  const bpOriginal = node('BP-1000', 'BP');
  const brOriginal = node('BR-1000', 'BR');
  const nestedContainer = container('fixture-nested-root', [node('fixture-child', 'fixture-nested-root')]);
  const ordinaryRoot = { ...container('CP'), taxonomyContainer: false };
  const roots = [container('BP', [bpOriginal, nestedContainer]), ordinaryRoot, container('BR', [brOriginal])];
  const content = utils.contentRoots(roots);
  assert.deepEqual(Array.from(content), [bpOriginal, nestedContainer, ordinaryRoot, brOriginal]);
  assert.equal(content[0], bpOriginal);
  assert.equal(content[1], nestedContainer);
  assert.equal(content[2], ordinaryRoot);
  assert.notEqual(content, roots);
});

test('empty containers contribute no content roots while ordinary leaf nodes remain visible', () => {
  const ordinary = node('BP-1000', 'BP');
  const roots = [container('BP'), { ...container('BR'), children: null },
    { ...container('CP'), children: undefined }, ordinary];
  assert.deepEqual(Array.from(utils.contentRoots(roots)), [ordinary]);
  assert.deepEqual(Array.from(utils.contentRoots([])), []);
  assert.deepEqual(Array.from(utils.contentRoots(null)), []);
  assert.deepEqual(Array.from(utils.contentRoots(undefined)), []);
});

test('presentation projection leaves frozen catalogue evidence and recorded scores unchanged', () => {
  const original = Object.freeze({
    ...node('BP-1000', 'BP', Object.freeze([])),
    uuid: 'fixture-original-uuid', nameEn: 'Original catalogue entry',
    analysisRole: 'CATEGORY', matchPercentage: 64
  });
  const root = Object.freeze({
    ...container('BP', Object.freeze([original])),
    uuid: 'fixture-container-uuid', analysisRole: 'CATEGORY', matchPercentage: 64
  });
  const roots = Object.freeze([root]);
  const before = JSON.stringify(roots);
  const projected = utils.contentRoots(roots);
  assert.equal(projected[0], original);
  assert.equal(JSON.stringify(roots), before);
  assert.equal(root.children[0], original);
  assert.equal(root.matchPercentage, 64);
  assert.equal(original.matchPercentage, 64);
});

function accessibilityHarness(hasTreeItems = false, moduleSources = [source], initialView = null,
    initialStructure = []) {
  const listeners = new Map();
  const attributes = new Map();
  const observers = [];
  let structure = new Set(initialStructure);
  attributes.set('aria-label', 'Taxonomy Tree');
  if (initialView) attributes.set('data-view-rendered', initialView);

  function attributeChanged(name) {
    for (const observer of observers) {
      if (observer.target === tree && observer.options.attributes &&
          (!observer.options.attributeFilter || observer.options.attributeFilter.includes(name))) {
        observer.records.push({ target: tree, type: 'attributes', attributeName: name, addedNodes: [] });
      }
    }
  }

  function flushMutations() {
    for (let round = 0; round < 20; round++) {
      if (observers.every(observer => observer.records.length === 0)) return;
      for (const observer of observers) {
        const records = observer.records.splice(0);
        if (records.length) observer.callback(records);
      }
    }
    assert.fail('Accessibility observers must reach a settled state');
  }

  const tree = {
    nodeType: 1,
    dataset: {},
    closest: () => null,
    querySelector: selector => selector.split(',').some(part => {
      const element = part.trim();
      return element === '[role="treeitem"]' ? hasTreeItems : structure.has(element);
    }) ? {} : null,
    querySelectorAll: () => [],
    getAttribute: name => attributes.get(name) ?? null,
    setAttribute(name, value) {
      attributes.set(name, String(value));
      attributeChanged(name);
    },
    removeAttribute(name) {
      if (attributes.delete(name)) attributeChanged(name);
    }
  };
  const document = {
    readyState: 'loading',
    addEventListener(event, callback) {
      if (!listeners.has(event)) listeners.set(event, []);
      listeners.get(event).push(callback);
    },
    getElementById: id => id === 'taxonomyTree' ? tree : null,
    querySelector: selector => selector === 'link[data-taxonomy-ergonomics]' ? {} : null
  };
  const context = vm.createContext({
    window: {}, document, Node: { ELEMENT_NODE: 1 },
    MutationObserver: class {
      constructor(callback) { this.callback = callback; }
      observe(target, options) { observers.push({ callback: this.callback, target, options, records: [] }); }
    }
  });
  for (const moduleSource of moduleSources) vm.runInContext(moduleSource, context);
  listeners.get('DOMContentLoaded').forEach(callback => callback());
  flushMutations();
  return {
    tree,
    rendered() {
      (listeners.get('taxonomy:view-rendered') || []).forEach(callback => callback());
      flushMutations();
    },
    replaceContents(items, selectors = []) {
      hasTreeItems = items;
      structure = new Set(selectors);
      for (const observer of observers) {
        if (observer.options.childList) {
          observer.records.push({ target: tree, type: 'childList', addedNodes: [] });
        }
      }
      flushMutations();
    },
    markerChanged: flushMutations
  };
}

test('completed list and tab views without tree items are available to assistive technology', () => {
  for (const view of ['list', 'tabs']) {
    const h = accessibilityHarness();
    h.tree.setAttribute('data-view-rendered', view);
    h.rendered();
    assert.equal(h.tree.getAttribute('aria-busy'), null, view);
    assert.equal(h.tree.getAttribute('role'), 'region', 'container headings belong to the labelled region');
  }
});

test('empty loading views stay busy until rendering completes and return to busy for another render', () => {
  const h = accessibilityHarness();
  assert.equal(h.tree.getAttribute('aria-busy'), 'true');
  h.tree.setAttribute('data-view-rendered', 'list');
  h.markerChanged();
  assert.equal(h.tree.getAttribute('aria-busy'), null);
  h.tree.removeAttribute('data-view-rendered');
  h.markerChanged();
  assert.equal(h.tree.getAttribute('aria-busy'), 'true');
});

test('materialized catalogue items retain their existing tree accessibility semantics', () => {
  const h = accessibilityHarness(true);
  assert.equal(h.tree.getAttribute('role'), 'tree');
  assert.equal(h.tree.getAttribute('aria-busy'), null);
});

for (const [order, modules] of [
  ['utils alone', [source]],
  ['security alone', [semanticsSource]],
  ['utils then security', [source, semanticsSource]],
  ['security then utils', [semanticsSource, source]]
]) {
  test(`${order}: completed empty and collapsed container views stay settled`, () => {
    for (const view of ['list', 'tabs']) {
      const h = accessibilityHarness(false, modules, view);
      assert.equal(h.tree.getAttribute('aria-busy'), null, `${view}: completed before initialization`);
      h.rendered();
      assert.equal(h.tree.getAttribute('aria-busy'), null, `${view}: observers must not undo completion`);
      assert.equal(h.tree.getAttribute('role'), 'region');
      if (modules.includes(semanticsSource)) assert.equal(h.tree.getAttribute('tabindex'), '0');
    }
  });

  test(`${order}: loading, completion, and another loading cycle preserve busy state`, () => {
    const h = accessibilityHarness(false, modules);
    assert.equal(h.tree.getAttribute('aria-busy'), 'true');
    h.tree.setAttribute('data-view-rendered', 'list');
    h.markerChanged();
    assert.equal(h.tree.getAttribute('aria-busy'), null);
    h.tree.removeAttribute('data-view-rendered');
    h.markerChanged();
    assert.equal(h.tree.getAttribute('aria-busy'), 'true');
    h.tree.setAttribute('data-view-rendered', 'tabs');
    h.rendered();
    assert.equal(h.tree.getAttribute('aria-busy'), null);
  });

  test(`${order}: legacy materialized items retain the tree role and existing focus target`, () => {
    const h = accessibilityHarness(true, modules);
    assert.equal(h.tree.getAttribute('role'), 'tree');
    if (modules.includes(semanticsSource)) assert.equal(h.tree.getAttribute('tabindex'), '0');
    assert.equal(h.tree.getAttribute('aria-busy'), null);
  });

  test(`${order}: area headings and tab navigation stay outside catalogue trees`, () => {
    for (const structure of [
      ['[data-taxonomy-container]'],
      ['[data-taxonomy-container]', '[role="tree"]'],
      ['[role="tablist"]'],
      ['[role="tablist"]', '[role="tree"]'],
      ['[role="tree"]']
    ]) {
      const h = accessibilityHarness(true, modules, 'list', structure);
      assert.equal(h.tree.getAttribute('role'), 'region', structure.join(', '));
      assert.equal(h.tree.getAttribute('aria-label'), 'Taxonomy Tree');
      assert.equal(h.tree.getAttribute('aria-busy'), null);
      h.rendered();
      assert.equal(h.tree.getAttribute('role'), 'region', 'observers must preserve inner tree ownership');
    }
  });

  test(`${order}: replacing legacy rows with area trees updates the outer role without stale ownership`, () => {
    const h = accessibilityHarness(true, modules, 'list');
    assert.equal(h.tree.getAttribute('role'), 'tree');
    h.replaceContents(true, ['[data-taxonomy-container]', '[role="tree"]']);
    assert.equal(h.tree.getAttribute('role'), 'region');
    h.replaceContents(false, ['[data-taxonomy-container]']);
    assert.equal(h.tree.getAttribute('role'), 'region');
    assert.equal(h.tree.getAttribute('aria-busy'), null, 'collapsed groups remain a completed view');
    h.replaceContents(true);
    assert.equal(h.tree.getAttribute('role'), 'tree', 'ordinary catalogue-only lists still have their tree owner');
  });
}
