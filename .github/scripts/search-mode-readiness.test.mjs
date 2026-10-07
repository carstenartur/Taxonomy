import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-search.js', import.meta.url), 'utf8');

function harness() {
    const select = { value: 'fulltext' };
    select.options = ['fulltext', 'semantic', 'hybrid', 'graph'].map(value => ({
        value, disabled: false, get selected() { return select.value === value; }
    }));
    const badge = { classList: { add() {}, remove() {} }, textContent: '', title: '' };
    let response = { ok: true, json: async () => ({ available: false, graphReady: false }) };
    const window = {};
    runInNewContext(source, {
        window,
        document: {
            addEventListener() {},
            getElementById(id) { return id === 'searchModeSelect' ? select : id === 'embeddingStatusBadge' ? badge : null; }
        },
        TaxonomyI18n: { t: key => key },
        TaxonomyUtils: { escapeHtml: value => value },
        fetch: async () => {
            if (response instanceof Error) throw response;
            return response;
        }
    });
    return {
        select, badge,
        disabled(mode) { return select.options.find(option => option.value === mode).disabled; },
        async status(data, ok = true) {
            response = data instanceof Error ? data : { ok, json: async () => data };
            window.TaxonomySearch.checkEmbeddingStatus();
            await new Promise(resolve => setImmediate(resolve));
        }
    };
}

test('node readiness enables semantic search while graph waits for relations', async () => {
    const ui = harness();
    await ui.status({ available: true, graphReady: false });
    assert.equal(ui.disabled('semantic'), false);
    assert.equal(ui.disabled('hybrid'), false);
    assert.equal(ui.disabled('graph'), true);
});

test('graph requires explicit readiness from the server', async () => {
    const ui = harness();
    await ui.status({ available: true });
    assert.equal(ui.disabled('graph'), true);
    await ui.status({ available: true, graphReady: true });
    assert.equal(ui.disabled('graph'), false);
});

test('relation failure clears a selected graph mode and keeps semantic search usable', async () => {
    const ui = harness();
    await ui.status({ available: true, graphReady: true });
    ui.select.value = 'graph';
    await ui.status({ available: true, graphReady: false });
    assert.equal(ui.select.value, 'fulltext');
    assert.equal(ui.disabled('graph'), true);
    assert.equal(ui.disabled('semantic'), false);
});

for (const failure of ['network', 'http']) {
    test(`${failure} status failure clears previously available modes and badge`, async () => {
        const ui = harness();
        await ui.status({ available: true, graphReady: true });
        ui.select.value = 'graph';
        await ui.status(failure === 'network' ? new Error('fixture unavailable')
            : { available: true, graphReady: true }, false);
        assert.equal(ui.select.value, 'fulltext');
        for (const mode of ['semantic', 'hybrid', 'graph']) assert.equal(ui.disabled(mode), true);
        assert.equal(ui.badge.textContent, 'search.embeddings.unavailable');
    });
}
