import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';

const script = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/architecture-responsibility-tree.js', import.meta.url), 'utf8');

class TestElement {
    constructor(tag, document) {
        this.tagName = tag;
        this.ownerDocument = document;
        this.children = [];
        this.dataset = {};
        this.attributes = {};
        this.events = {};
        this.textContent = '';
    }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
    setAttribute(key, value) { this.attributes[key] = String(value); }
    getAttribute(key) { return this.attributes[key] ?? null; }
    addEventListener(key, fn) { this.events[key] = fn; }
    click() { this.focus(); this.events.click(); }
    focus() { this.ownerDocument.activeElement = this; }
    contains(node) { return node === this || this.children.some(child => child.contains(node)); }
}

function fixture() {
    const window = {}, document = {createElement: tag => new TestElement(tag, document)};
    vm.runInNewContext(script, {window, document});
    const root = document.createElement('div'), chosen = [];
    const tree = window.TaxonomyResponsibilityTree.create({root,
        t: (key, ...args) => [key, ...args].join(' '), onSelect: id => chosen.push(id)});
    function all(node = root) { return [node, ...node.children.flatMap(all)]; }
    function by(kind, id) { return all().find(node => node.dataset[kind] === id); }
    function visible() { return all().filter(node => node.dataset.selectId).map(node => node.dataset.selectId); }
    return {root, tree, chosen, all, by, visible};
}

const roles = [
    {id:'BR-1081', title:'Medical Advisor Roles', type:'BusinessRole'},
    {id:'BR-1000', title:'Business Roles', type:'BusinessRole'},
    {id:'BR-1057', title:'Functional Military Roles', type:'BusinessRole'},
    {id:'BR-1002', title:'Advisors and Liaison Roles', type:'BusinessRole'},
    {id:'BR-OTHER', title:'Medical Advisor Roles', type:'BusinessRole'}
];
const hierarchy = {
    'BR-1081':{parentId:'BR-1002'}, 'BR-1000':{parentId:'BR'},
    'BR-1057':{parentId:'BR-1000'}, 'BR-1002':{parentId:'BR-1057'}, 'BR-OTHER':{parentId:'BR'}
};

test('opens the selected catalogue path, keeps repeated titles distinct, and restores keyboard focus', () => {
    const f = fixture();
    f.tree.render({elements:roles, hierarchy, selected:'BR-1081'});
    assert.ok(f.by('selectId', 'BR-1000'));
    assert.ok(f.by('selectId', 'BR-1057'));
    assert.ok(f.by('selectId', 'BR-1002'));
    assert.equal(f.by('selectId', 'BR-1081').getAttribute('aria-current'), 'true');
    const branch = f.by('toggleId', 'BR-1057');
    assert.equal(branch.tagName, 'button');
    branch.click();
    assert.equal(f.by('toggleId', 'BR-1057').getAttribute('aria-expanded'), 'false');
    assert.equal(f.by('toggleId', 'BR-1057'), f.root.ownerDocument.activeElement);
    assert.equal(f.by('selectId', 'BR-1081'), undefined);
    f.by('selectId','BR-OTHER').click();
    assert.deepEqual(f.chosen, ['BR-OTHER']);
    f.tree.render({elements:roles, hierarchy, selected:'BR-OTHER'});
    assert.equal(f.by('selectId', 'BR-1081'), undefined);
});

test('search shows matching titles and ancestors and restores user expansion after clearing', () => {
    const f = fixture();
    const render = query => f.tree.render({elements:roles, hierarchy, query});
    render('');
    f.by('toggleId', 'BR').click();
    assert.equal(f.by('selectId','BR-1057'), undefined);
    render('medical advisor');
    assert.ok(f.by('selectId','BR-1081'));
    assert.ok(f.by('selectId','BR-OTHER'));
    assert.ok(f.by('selectId','BR-1057'));
    render('BR-1081');
    assert.equal(f.by('selectId','BR-OTHER'), undefined);
    render('no such role');
    assert.ok(f.all().some(node => node.textContent.includes('editor.tree.noMatches')));
    render('');
    assert.ok(f.by('selectId','BR-1000'));
    assert.equal(f.by('selectId','BR-1057'), undefined);
    f.tree.reset();
    render('');
    assert.equal(f.by('selectId','BR-1000'), undefined);
});

test('a large branch stays lazy, keeping even a distant selected item accessible', () => {
    const f = fixture();
    const elements = Array.from({length:2564}, (_, i) => ({id:'item-' + String(i).padStart(4,'0')}));
    const links = Object.fromEntries(elements.map(node => [node.id, {parentId:'catalogue'}]));
    f.tree.render({elements, hierarchy:links});
    assert.equal(f.visible().length, 0);
    f.by('toggleId', 'catalogue').click();
    assert.ok(f.visible().length > 0 && f.visible().length < 200);
    const firstSize = f.visible().length;
    f.by('moreId', 'catalogue').click();
    assert.ok(f.visible().length > firstSize);
    f.tree.render({elements, hierarchy:links, selected:'item-2563'});
    assert.ok(f.by('selectId','item-2563'));
    assert.ok(f.visible().length < 250);
});

test('isolated and cyclic legacy elements stay reachable and an empty model has a message', () => {
    const f = fixture();
    f.tree.render({elements:[], hierarchy:{}});
    assert.ok(f.all().some(node => node.textContent.includes('editor.tree.empty')));
    f.tree.render({elements:[{id:'a'}, {id:'b'}, {id:'c'}], hierarchy:{a:{parentId:'b'}, b:{parentId:'a'}}});
    assert.ok(f.by('selectId','c'));
    f.tree.render({elements:[{id:'a'}, {id:'b'}], hierarchy:{a:{parentId:'b'}, b:{parentId:'a'}}, selected:'a', query:'a'});
    assert.ok(f.by('selectId','a'));
});

test('graph selection reopens a collapsed ancestor even when its nested branch remains expanded', () => {
    const f = fixture();
    f.tree.render({elements:roles, hierarchy, selected:'BR-1081'});
    f.by('toggleId', 'BR').click();
    assert.equal(f.by('selectId','BR-1057'), undefined);
    f.tree.render({elements:roles, hierarchy, selected:'BR-1002'});
    assert.equal(f.by('selectId','BR-1002').getAttribute('aria-current'), 'true');
});
