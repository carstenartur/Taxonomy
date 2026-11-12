(function () {
    'use strict';
    const panel = document.getElementById('provisionalProductPanel');
    if (!panel) return;

    const form = document.getElementById('provisionalProductFilter');
    const filter = document.getElementById('provisionalProductQuery');
    const results = document.getElementById('provisionalProductResults');
    const status = document.getElementById('provisionalProductStatus');
    let request;

    function label(key, ...args) {
        return window.TaxonomyI18n.t('admin.productReview.' + key, ...args);
    }

    function fields(entries) {
        const list = document.createElement('dl');
        list.className = 'mt-2';
        entries.forEach(([key, value]) => {
            const name = document.createElement('dt');
            const text = document.createElement('dd');
            name.textContent = label(key);
            text.textContent = value ?? label('unknown');
            list.append(name, text);
        });
        return list;
    }

    function render(queue) {
        results.append(fields([['version', queue.mappingVersion], ['catalogue', queue.catalogueRevision],
            ['workbook', queue.workbookSha256], ['overlay', queue.overlaySha256]]));
        for (const mapping of queue.mappings) {
            const details = document.createElement('details');
            const summary = document.createElement('summary');
            summary.textContent = mapping.productCode + ' — ' + mapping.productTitle;
            details.append(summary, fields([['sourceParent', mapping.sourceParentReference],
                ['parent', mapping.proposedParentCode], ['secondary', mapping.secondaryClassificationCodes.join(', ') || label('none')],
                ['confidence', mapping.confidence], ['state', mapping.state === 'PROVISIONAL' ? label('provisional') : mapping.state],
                ['sourceState', mapping.sourceState], ['reason', mapping.justification]]));
            results.append(details);
        }
        status.textContent = label('count', queue.mappings.length);
    }

    function reset() {
        request?.abort();
        request = null;
        results.replaceChildren();
        status.textContent = '';
    }

    async function refresh() {
        reset();
        const active = new AbortController();
        request = active;
        try {
            await window.TaxonomyI18n.ready();
            if (active.signal.aborted) return;
            status.textContent = label('loading');
            const response = await window.TaxonomyReferenceApi.provisionalInformationProducts(filter.value.trim(), active.signal);
            const queue = await response.json();
            if (!active.signal.aborted) render(queue);
        } catch (error) {
            if (!active.signal.aborted) {
                results.replaceChildren();
                status.textContent = label('unavailable');
            }
        }
    }

    form.addEventListener('submit', event => {
        event.preventDefault();
        refresh();
    });
    panel.addEventListener('toggle', () => {
        if (panel.open) refresh();
        else reset();
    });
    window.addEventListener('pagehide', reset);
}());
