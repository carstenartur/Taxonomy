import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const root = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const [projectsSource, intakeSource] = await Promise.all([
  readFile(new URL('core/taxonomy-analysis-session-projects.js', root), 'utf8'),
  readFile(new URL('shared/requirement-intake.js', root), 'utf8')
]);
const settle = () => new Promise(resolve => setImmediate(resolve));

class Element extends EventTarget {
  constructor(tagName) {
    super();
    this.tagName = tagName;
    this.children = [];
    this.dataset = {};
    this.value = '';
    this.open = false;
    this.classList = {add() {}, remove() {}};
  }

  append(...children) { children.forEach(child => this.appendChild(child)); }
  appendChild(child) {
    if (this.tagName === 'select' && this.children.length === 0) this.value = String(child.value);
    child.parent = this;
    this.children.push(child);
  }
  replaceChildren(...children) { this.children = []; this.append(...children); }
  remove() { this.parent.children = this.parent.children.filter(child => child !== this); }
  descendants() { return this.children.flatMap(child => [child, ...child.descendants()]); }
  closest(selector) {
    return selector.includes(this.tagName) ? this : this.parent?.closest(selector);
  }
  setAttribute() {}
  focus() { this.focused = true; }
  showModal() { this.open = true; }
  close() {
    this.open = false;
    setImmediate(() => this.fire('close'));
  }
  fire(type) { this.dispatchEvent(new Event(type)); }
  setCustomValidity(message) { this.validationMessage = message; }
  reportValidity() {
    return this.tagName === 'form'
      ? this.descendants().every(child => !child.validationMessage)
      : !this.validationMessage;
  }
}

function harness(projects = [{id: 1, projectKey: 'P1', title: 'One'}, {id: 2, projectKey: 'P2', title: 'Two'}]) {
  const document = Object.assign(new EventTarget(), {
    readyState: 'loading', documentElement: {lang: 'en'}, body: new Element('body'),
    createElement: tagName => new Element(tagName)
  });
  const suggestions = [], reviewRequests = [], writes = [];
  const source = {repositoryId: 'repo', workspaceId: 'work', branch: 'draft'};
  const input = new Element('textarea');
  const C = {
    S: {}, runtime: {}, text: key => key, language: () => 'en', businessTextElement: () => input,
    currentPayload: () => ({businessText: input.value}), comparable: value => JSON.stringify(value),
    meaningful: value => Boolean(value.businessText),
    saveDraft: async () => true, showActionAlert() {},
    jsonRequest(path, options) {
      if (options.method === 'POST') {
        writes.push(path);
        return Promise.resolve({id: 3});
      }
      if (path === '/api/projects') return Promise.resolve(projects);
      return new Promise((resolve, reject) => suggestions.push({path, resolve, reject}));
    }
  };
  const window = {
    __TaxonomyAnalysisSessionContext: C,
    TaxonomyApiClient: {captureContext: () => () => true},
    TaxonomyArchitectureWorkspace: {getSource: () => source},
    TaxonomyArchitectureLifecycleApi: {reviewRequirementIntake(request) { reviewRequests.push(request); }},
    TaxonomyArchitectureLifecycle: {saveRequirement() { writes.push('saveRequirement'); }},
    location: {pathname: '/', search: '', assign() {}},
    setTimeout() {}, clearTimeout() {}
  };
  const context = vm.createContext({window, document});
  vm.runInContext(intakeSource, context, {filename: 'requirement-intake.js'});
  vm.runInContext(projectsSource, context, {filename: 'taxonomy-analysis-session-projects.js'});
  return {
    suggestions, reviewRequests, writes, source,
    async open(value = '') {
      input.value = value;
      window.TaxonomyAnalysisSession.addAsRequirement({preserveDraft: true});
      await settle();
      const dialog = document.body.children.at(-1);
      const form = dialog.children[0];
      function control(labelText) {
        const label = form.descendants().find(node => node.tagName === 'label' && node.textContent === labelText);
        return form.descendants().find(node => node.id === label.htmlFor);
      }
      return {dialog, form, control,
        cancel: () => form.descendants().find(node => node.tagName === 'button' && node.textContent === 'cancel').fire('click')};
    }
  };
}

function selectProject(select, id) {
  select.value = String(id);
  select.fire('change');
}

test('a delayed suggestion from the old project preserves a manually edited key', async () => {
  const h = harness();
  const d = await h.open();
  const key = d.control('requirementKey');
  selectProject(d.control('project'), 2);
  assert.deepEqual(h.suggestions.map(request => request.path), ['/api/projects/1/requirements', '/api/projects/2/requirements']);
  h.suggestions[1].resolve([{requirementKey: 'REQ-005'}]);
  await settle();
  assert.equal(key.value, 'REQ-006');
  key.value = 'MY-REVIEWED-KEY';
  key.fire('input');
  h.suggestions[0].resolve([{requirementKey: 'REQ-100'}]);
  await settle();
  assert.equal(key.value, 'MY-REVIEWED-KEY');
});

test('a delayed failure from the old project preserves the current suggestion', async () => {
  const h = harness();
  const d = await h.open();
  selectProject(d.control('project'), 2);
  h.suggestions[1].resolve([{requirementKey: 'REQ-005'}]);
  await settle();
  h.suggestions[0].reject(new Error('Old project request failed'));
  await settle();
  assert.equal(d.control('requirementKey').value, 'REQ-006');
});

test('returning to a project only accepts its newest suggestion request', async () => {
  const h = harness();
  const d = await h.open();
  const project = d.control('project');
  const key = d.control('requirementKey');
  selectProject(project, 2);
  selectProject(project, 1);
  h.suggestions[0].resolve([{requirementKey: 'REQ-100'}]);
  await settle();
  assert.equal(key.value, 'REQ-001');
  h.suggestions[2].resolve([{requirementKey: 'REQ-008'}]);
  await settle();
  assert.equal(key.value, 'REQ-009');
});

test('typing and reverting the key keeps it owned by the author during a pending suggestion', async () => {
  const h = harness();
  const d = await h.open();
  const key = d.control('requirementKey');
  key.value = 'MY-KEY';
  key.fire('input');
  key.value = 'REQ-001';
  key.fire('input');
  h.suggestions[0].resolve([{requirementKey: 'REQ-100'}]);
  await settle();
  assert.equal(key.value, 'REQ-001');
});

test('a pending suggestion preserves a key changed without an input event', async () => {
  const h = harness();
  const d = await h.open();
  const key = d.control('requirementKey');
  key.value = 'PREFILLED-KEY';
  h.suggestions[0].resolve([{requirementKey: 'REQ-100'}]);
  await settle();
  assert.equal(key.value, 'PREFILLED-KEY');
});

test('a pending suggestion is ignored after the architecture source changes', async () => {
  const h = harness();
  const d = await h.open();
  h.source.workspaceId = 'other';
  h.suggestions[0].resolve([{requirementKey: 'REQ-100'}]);
  await settle();
  assert.equal(d.control('requirementKey').value, 'REQ-001');
});

test('a dismissed dialog ignores its pending suggestion and the reopened dialog accepts its own', async () => {
  const h = harness();
  const first = await h.open();
  first.cancel();
  const second = await h.open();
  h.suggestions[0].resolve([{requirementKey: 'REQ-100'}]);
  h.suggestions[1].resolve([{requirementKey: 'REQ-003'}]);
  await settle();
  assert.equal(first.control('requirementKey').value, 'REQ-001');
  assert.equal(second.control('requirementKey').value, 'REQ-004');
});

test('an active suggestion failure still falls back to the initial key', async () => {
  const h = harness();
  const d = await h.open();
  h.suggestions[0].resolve([{requirementKey: 'REQ-005'}]);
  await settle();
  assert.equal(d.control('requirementKey').value, 'REQ-006');
  selectProject(d.control('project'), 2);
  h.suggestions[1].reject(new Error('Current project request failed'));
  await settle();
  assert.equal(d.control('requirementKey').value, 'REQ-001');
});

test('rejecting a blank first requirement still allows creating only the project', async () => {
  const h = harness([]);
  const d = await h.open(' \t ');
  const requirement = d.control('requirementText');
  d.form.fire('submit');
  await settle();
  assert.match(requirement.validationMessage, /requirement text/i);
  assert.equal(requirement.focused, true);
  assert.deepEqual(h.reviewRequests, []);
  assert.deepEqual(h.writes, []);

  const includeRequirement = d.control('createFirstRequirement');
  includeRequirement.checked = false;
  includeRequirement.fire('change');
  d.form.fire('submit');
  await settle();
  assert.deepEqual(h.reviewRequests, []);
  assert.deepEqual(h.writes, ['/api/projects']);
});
