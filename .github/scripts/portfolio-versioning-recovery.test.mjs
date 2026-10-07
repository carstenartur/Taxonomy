import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const resources = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const source = readFileSync(new URL('portfolio/portfolio-versioning.js', resources), 'utf8');
const utilities = readFileSync(new URL('shared/taxonomy-utils.js', resources), 'utf8');
const branchIds = ['commitBranch', 'materializeBranch', 'mergeSource', 'mergeTarget'];
const selections = { commitBranch: 'release', materializeBranch: 'review', mergeSource: 'proposal', mergeTarget: 'main' };
const drafts = { commitMessage: '  Reviewed the revised requirements.\nKeep this context.  ', mergeMessage: '  Merge the reviewed proposal.  ' };
const labels = {
    en: {
        committed: 'Portfolio committed successfully.', materialized: 'Branch materialized successfully.',
        merged: 'Branches merged successfully.', refreshFailed: 'The portfolio preview could not be refreshed.',
        sourceTargetDiffer: 'Source and target branch must differ', refresh: 'Refresh'
    },
    de: {
        committed: 'Das Portfolio wurde erfolgreich committed.', materialized: 'Der Branch wurde erfolgreich materialisiert.',
        merged: 'Die Branches wurden erfolgreich zusammengeführt.', refreshFailed: 'Die Portfoliovorschau konnte nicht aktualisiert werden.',
        sourceTargetDiffer: 'Quell- und Zielbranch müssen verschieden sein', refresh: 'Aktualisieren'
    }
};
const operations = [
    { name: 'commit', control: 'commitForm', event: 'submit', method: 'commitPortfolio', success: 'committed',
        request: { branch: 'release', message: drafts.commitMessage.trim() } },
    { name: 'materialization', control: 'applyMaterialize', event: 'click', method: 'materializePortfolio', success: 'materialized',
        request: { branch: 'review', expectedHead: 'review-head-123456789' } },
    { name: 'merge', control: 'mergeForm', event: 'submit', method: 'mergePortfolio', success: 'merged',
        request: { sourceBranch: 'proposal', targetBranch: 'main', message: drafts.mergeMessage.trim() } }
];

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}

function harness(locale) {
    const nodes = new Map();
    const documentEvents = new Map();
    function element(tagName = 'div') {
        const events = new Map();
        let content = '';
        let value = '';
        let selected = null;
        const node = {
            tagName, children: [], dataset: {}, className: '', innerHTML: '',
            addEventListener(type, listener) {
                if (!events.has(type)) events.set(type, []);
                events.get(type).push(listener);
            },
            async dispatch(type) {
                const event = { type, target: this, currentTarget: this, defaultPrevented: false,
                    preventDefault() { this.defaultPrevented = true; } };
                for (const listener of events.get(type) || []) await listener(event);
                return event;
            },
            appendChild(child) {
                this.children.push(child);
                if (tagName === 'select' && !selected) selected = child;
                return child;
            },
            focus() { document.activeElement = this; }
        };
        node.classList = {
            contains(name) { return node.className.split(/\s+/).includes(name); },
            toggle(name, force) {
                const names = new Set(node.className.split(/\s+/).filter(Boolean));
                if (force ?? !names.has(name)) names.add(name); else names.delete(name);
                node.className = [...names].join(' ');
            },
            add(...names) { names.forEach(name => this.toggle(name, true)); },
            remove(...names) { names.forEach(name => this.toggle(name, false)); }
        };
        Object.defineProperties(node, {
            textContent: {
                get() { return content; },
                set(text) { content = String(text); this.innerHTML = ''; this.children = []; selected = null; }
            },
            value: {
                get() { return tagName === 'select' ? selected?.value || '' : value; },
                set(next) {
                    if (tagName === 'select') selected = this.children.find(option => option.value === String(next)) || null;
                    else value = String(next);
                }
            },
            options: { get() { return this.children; } }
        });
        return node;
    }
    function get(id) {
        if (!nodes.has(id)) nodes.set(id, element(branchIds.includes(id) ? 'select' : 'div'));
        return nodes.get(id);
    }
    const document = {
        readyState: 'loading', documentElement: { lang: locale }, activeElement: null,
        getElementById: get, createElement: element,
        addEventListener(type, listener) {
            if (!documentEvents.has(type)) documentEvents.set(type, []);
            documentEvents.get(type).push(listener);
        },
        querySelector(selector) { return get(selector === '#dslPreview' ? 'dslPreview' : selector); },
        querySelectorAll() { return [get('commitSubmit'), get('applyMaterialize'), get('mergeSubmit')]; }
    };
    get('dslPreview').previousElementSibling = element();
    ['versioningError', 'versioningInfo', 'versioningBusy', 'applyMaterialize'].forEach(id => get(id).classList.add('d-none'));
    const data = {
        getProject: { id: 42, projectKey: 'PORT-42', title: 'Reviewed portfolio' },
        getGitState: { currentBranch: 'main', headCommit: 'baseline-123456789',
            branches: ['main', 'review', 'release', 'proposal'] },
        exportPortfolio: { activeBranch: 'main', dsl: 'project PORT-42', workspaceId: 'shared',
            projectCount: 1, requirementCount: 3, solutionCount: 2, productCount: 1 },
        getAccount: { architectureMutationAllowed: true },
        previewMaterialization: { targetHead: 'review-head-123456789', changed: true,
            addedLines: 1, removedLines: 0, destructiveChangePossible: false, addedPreview: ['requirement REVIEW'] },
        commitPortfolio: { commitId: 'commit-123456789' },
        materializePortfolio: { branch: 'review', commitId: 'restore-123456789' },
        mergePortfolio: { strategy: 'GIT', mergeCommitId: 'merge-123456789' }
    };
    const calls = [];
    const handlers = Object.fromEntries(Object.keys(data).map(name => [name, () => structuredClone(data[name])]));
    const api = Object.fromEntries(Object.keys(data).map(name => [name, async (...args) => {
        calls.push({ name, args: structuredClone(args) });
        return handlers[name](...args);
    }]));
    const confirmations = [];
    const context = vm.createContext({
        document, URLSearchParams, location: { pathname: '/taxonomy/projects/42/versioning', search: `?lang=${locale}` },
        TaxonomyPortfolioApi: api,
        TaxonomyI18n: { getBasePath: () => '/taxonomy', resolveUrl: path => '/taxonomy' + path },
        confirm(message) { confirmations.push(message); return true; }
    });
    context.window = context;
    vm.runInContext(utilities, context, { filename: 'taxonomy-utils.js' });
    documentEvents.clear(); // Global accessibility initialization is outside this page's mocked DOM.
    vm.runInContext(source, context, { filename: 'portfolio-versioning.js' });
    assert.equal(documentEvents.get('DOMContentLoaded')?.length, 1, 'the unmodified production module must register its initializer');
    return {
        get, document, data, calls, handlers, confirmations,
        initialize() { return documentEvents.get('DOMContentLoaded')[0](); },
        trigger(id, type = 'click') { return get(id).dispatch(type); },
        async edit(id, value) { get(id).value = value; await get(id).dispatch('input'); },
        deferRead() {
            const reply = deferred();
            const started = deferred();
            handlers.getGitState = () => { started.resolve(); return reply.promise; };
            return { ...reply, started: started.promise };
        }
    };
}

async function prepared(locale) {
    const app = harness(locale);
    await app.initialize();
    for (const [id, value] of Object.entries({ ...selections, ...drafts })) await app.edit(id, value);
    return app;
}

function assertDrafts(app, expected = drafts) {
    for (const [id, value] of Object.entries(expected)) assert.equal(app.get(id).value, value, `${id} retains the user's exact value`);
}

function assertSelections(app, expected = selections) {
    for (const [id, value] of Object.entries(expected)) assert.equal(app.get(id).value, value, `${id} keeps its independent selection`);
}

function assertSuccess(app, locale, operation) {
    assert.equal(app.get('versioningInfo').classList.contains('d-none'), false, 'the confirmed mutation remains visible');
    assert.ok(app.get('versioningInfo').textContent.startsWith(labels[locale][operation.success]));
}

async function prepareOperation(app, operation) {
    if (operation.name === 'materialization') await app.trigger('previewMaterialize');
}

for (const locale of ['en', 'de']) {
    test(`${locale}: initial load still sets message and branch defaults`, async () => {
        const app = harness(locale);
        await app.initialize();
        for (const id of branchIds) {
            assert.deepEqual(Array.from(app.get(id).options, option => [option.value, option.textContent]),
                [['main', 'main'], ['review', 'review'], ['release', 'release'], ['proposal', 'proposal']],
                `${id} renders each RepositoryState string branch as its option value and label`);
        }
        assertDrafts(app, { commitMessage: 'Portfolio PORT-42: reviewed project state', mergeMessage: 'Merge portfolio branches for PORT-42' });
        assertSelections(app, { commitBranch: 'main', materializeBranch: 'main', mergeSource: 'review', mergeTarget: 'main' });
        assert.equal(app.get('refreshPreview').textContent, labels[locale].refresh);
        assert.equal(app.get('reportsLink').href, `/taxonomy/projects/42/reports?lang=${locale}`);
        assert.equal(app.get('versioningError').textContent, '');
    });

    for (const empty of [false, true]) {
        const expected = empty ? { commitMessage: '', mergeMessage: '' } : drafts;
        test(`${locale}: manual refresh preserves ${empty ? 'deliberately empty' : 'written'} messages and independent branches`, async () => {
            const app = await prepared(locale);
            for (const [id, value] of Object.entries(expected)) await app.edit(id, value);
            app.data.exportPortfolio.dsl = 'project PORT-42 refreshed';
            await app.trigger('refreshPreview');
            assert.equal(app.get('dslPreview').textContent, app.data.exportPortfolio.dsl, 'refresh still renders the new repository data');
            assertDrafts(app, expected);
            assertSelections(app);
            assert.equal(app.get('versioningBusy').classList.contains('d-none'), true);
        });

        test(`${locale}: deferred refresh preserves ${empty ? 'cleared' : 'revised'} drafts and branch edits made while reading`, async () => {
            const app = await prepared(locale);
            const pending = app.deferRead();
            const refresh = app.trigger('refreshPreview');
            await pending.started;
            assert.equal(app.get('versioningBusy').classList.contains('d-none'), false);
            const latest = empty ? expected : { commitMessage: 'Newest commit draft', mergeMessage: 'Newest merge draft' };
            const latestSelections = { commitBranch: 'proposal', materializeBranch: 'release', mergeSource: 'review', mergeTarget: 'release' };
            for (const [id, value] of Object.entries({ ...latest, ...latestSelections })) await app.edit(id, value);
            pending.resolve(app.data.getGitState);
            await refresh;
            assertDrafts(app, latest);
            assertSelections(app, latestSelections);
        });

        test(`${locale}: initial deferred load preserves a ${empty ? 'typed then cleared' : 'typed'} message before defaults arrive`, async () => {
            const app = harness(locale);
            const pending = app.deferRead();
            const initialization = app.initialize();
            await pending.started;
            await app.edit('commitMessage', drafts.commitMessage);
            if (empty) await app.edit('commitMessage', '');
            pending.resolve(app.data.getGitState);
            await initialization;
            assertDrafts(app, { commitMessage: expected.commitMessage, mergeMessage: 'Merge portfolio branches for PORT-42' });
        });
    }

    test(`${locale}: a failed manual refresh preserves drafts and identifies the read failure`, async () => {
        const app = await prepared(locale);
        app.handlers.getGitState = () => { throw new Error('Repository read unavailable'); };
        await app.trigger('refreshPreview');
        assertDrafts(app);
        assertSelections(app);
        assert.equal(app.get('versioningError').textContent, `${labels[locale].refreshFailed} Repository read unavailable`);
        assert.equal(app.get('versioningInfo').classList.contains('d-none'), true);
    });

    test(`${locale}: changing the repository's active branch preserves each available selection`, async () => {
        const app = await prepared(locale);
        app.data.getGitState.currentBranch = 'review';
        await app.trigger('refreshPreview');
        assert.equal(app.get('activeBranch').textContent, 'review');
        assertSelections(app);
    });

    for (const operation of operations) {
        test(`${locale}: ${operation.name} success survives its deferred follow-up refresh`, async () => {
            const app = await prepared(locale);
            await prepareOperation(app, operation);
            const pending = app.deferRead();
            const action = app.trigger(operation.control, operation.event);
            await pending.started;
            assertSuccess(app, locale, operation);
            const request = app.calls.find(call => call.name === operation.method);
            assert.deepEqual(request?.args, [operation.request], 'the mutation submits the reviewed choices and trimmed message');
            await app.edit('commitMessage', 'Next commit draft');
            await app.edit('mergeMessage', '');
            pending.resolve(app.data.getGitState);
            await action;
            assertSuccess(app, locale, operation);
            assertDrafts(app, { commitMessage: 'Next commit draft', mergeMessage: '' });
            assertSelections(app);
            assert.equal(app.get('versioningError').classList.contains('d-none'), true);
            assert.equal(app.get('versioningLive').textContent, app.get('versioningInfo').textContent);
            if (operation.name === 'materialization') {
                assert.equal(app.get('applyMaterialize').classList.contains('d-none'), true);
                assert.equal(app.get('materializePreview').textContent, '');
            }
            if (operation.name === 'merge') assert.ok(app.get('mergeResult').innerHTML.includes(labels[locale].merged));
        });

        test(`${locale}: completed ${operation.name} stays successful when the follow-up read fails`, async () => {
            const app = await prepared(locale);
            await prepareOperation(app, operation);
            app.handlers.getGitState = () => { throw new Error('Repository read unavailable'); };
            await app.trigger(operation.control, operation.event);
            assertSuccess(app, locale, operation);
            assert.equal(app.get('versioningError').textContent, `${labels[locale].refreshFailed} Repository read unavailable`);
            assert.equal(app.get('versioningLive').textContent, app.get('versioningError').textContent);
            assertDrafts(app);
            assertSelections(app);
        });

        test(`${locale}: a rejected ${operation.name} preserves drafts and never reports success`, async () => {
            const app = await prepared(locale);
            await prepareOperation(app, operation);
            const readCount = app.calls.filter(call => call.name === 'getGitState').length;
            app.handlers[operation.method] = () => { throw new Error('Mutation rejected: stale HEAD'); };
            await app.trigger(operation.control, operation.event);
            assert.equal(app.get('versioningInfo').classList.contains('d-none'), true);
            assert.equal(app.get('versioningInfo').textContent, '');
            assert.equal(app.get('versioningError').textContent, 'Mutation rejected: stale HEAD');
            assert.equal(app.document.activeElement, app.get('versioningError'));
            assert.equal(app.calls.filter(call => call.name === 'getGitState').length, readCount, 'a failed mutation does not launch a success refresh');
            assertDrafts(app);
            assertSelections(app);
            if (operation.name === 'materialization') assert.equal(app.get('applyMaterialize').classList.contains('d-none'), false, 'the reviewed attempt remains available for recovery');
            if (operation.name === 'merge') assert.equal(app.get('mergeResult').innerHTML, '');
        });
    }

    for (const invalidSelection of [false, true]) {
        test(`${locale}: an unsuccessful merge retry clears the previous success (${invalidSelection ? 'same branch' : 'API rejection'})`, async () => {
            const app = await prepared(locale);
            const operation = operations.find(item => item.name === 'merge');
            await app.trigger(operation.control, operation.event);
            assert.ok(app.get('mergeResult').innerHTML.includes(labels[locale].merged));
            if (invalidSelection) await app.edit('mergeSource', app.get('mergeTarget').value);
            else app.handlers.mergePortfolio = () => { throw new Error('Merge conflict'); };
            const mutationCount = app.calls.filter(call => call.name === 'mergePortfolio').length;
            await app.trigger(operation.control, operation.event);
            assert.equal(app.get('mergeResult').innerHTML, '', 'the previous merge cannot label this attempt successful');
            assert.equal(app.get('versioningInfo').classList.contains('d-none'), true);
            assert.equal(app.get('versioningError').textContent, invalidSelection ? labels[locale].sourceTargetDiffer : 'Merge conflict');
            assert.equal(app.calls.filter(call => call.name === 'mergePortfolio').length, mutationCount + (invalidSelection ? 0 : 1));
        });
    }
}
