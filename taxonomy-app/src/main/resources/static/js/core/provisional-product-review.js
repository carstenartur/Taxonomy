(function () {
    'use strict';
    const panel = document.getElementById('provisionalProductPanel');
    if (!panel) return;

    const form = document.getElementById('provisionalProductFilter');
    const filter = document.getElementById('provisionalProductQuery');
    const results = document.getElementById('provisionalProductResults');
    const status = document.getElementById('provisionalProductStatus');
    let request;
    const pending = new Set();

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
            addReviews(details, queue, mapping, request.signal);
            results.append(details);
        }
        status.textContent = label('count', queue.mappings.length);
    }

    function node(tag, text) {
        const element = document.createElement(tag);
        if (text != null) element.textContent = text;
        return element;
    }

    function addReviews(details, queue, mapping, signal) {
        const area = node('div');
        const feedback = node('p');
        feedback.setAttribute('role', 'status');
        const key = mapping.productCode;
        const history = node('ol');
        const more = node('button', label('more'));
        more.type = 'button'; more.className = 'btn btn-sm btn-outline-secondary';
        let head;
        let cursor;
        let reading = false;
        let initialized = false;
        function paint(entry) {
            const d = entry.decision;
            const item = node('li', label(d.action) + ' · ' + d.reviewer + ' · ' + d.reviewedAt);
            item.append(fields([['version', d.mappingVersion], ['originalDecisionParent', d.original.proposedParentCode],
                ['parent', d.proposedParent], ['secondary', d.secondary.join(', ') || label('none')],
                ['reason', d.rationale], ['commit', entry.commit]]));
            const receipt = node('button', label('receipt'));
            receipt.type = 'button'; receipt.className = 'btn btn-sm btn-outline-secondary me-2';
            receipt.addEventListener('click', () => {
                const url = URL.createObjectURL(new Blob([JSON.stringify(entry)], {type: 'application/json'}));
                const link = node('a');
                link.href = url; link.download = 'mapping-review-' + entry.commit + '.json';
                document.body.append(link); link.click(); link.remove();
                window.setTimeout(() => URL.revokeObjectURL(url), 1000);
            });
            item.append(receipt);
            if (d.proposedSha256) {
                const link = node('a', label('proposal') + ' · ' + d.proposedVersion);
                link.href = window.TaxonomyReferenceApi.mappingOverlayUrl(key, entry.commit);
                item.append(link, fields([['overlay', d.proposedSha256]]));
            }
            return item;
        }
        async function loadHistory(next) {
            if (reading || signal.aborted) return;
            reading = true; more.disabled = true;
            try {
                const page = await window.TaxonomyReferenceApi.mappingReviewHistory(key, next, signal);
                if (signal.aborted) return;
                if (!next) {
                    head = page.head; history.replaceChildren();
                    area.replaceChildren(reviewForm(), node('h4', label('history')), history, more);
                }
                for (const entry of page.decisions) history.append(paint(entry));
                cursor = page.next; more.hidden = !cursor;
                feedback.textContent = '';
            } catch (error) {
                if (!signal.aborted) {
                    if (error.status === 401 || error.status === 403) {
                        reset(); status.textContent = label('unavailable'); return;
                    }
                    head = null; area.replaceChildren(); initialized = false;
                    feedback.textContent = label('unavailable');
                }
            } finally {reading = false; more.disabled = false;}
        }
        function reviewForm() {
            const form = node('form');
            form.className = 'my-3';
            const action = node('select'), parent = node('input'), secondary = node('input'), rationale = node('textarea');
            const inputs = {action, parent, secondary, rationale};
            for (const name of ['ACCEPT', 'CHANGE', 'REJECT']) {
                const option = node('option', label(name)); option.value = name; action.append(option);
            }
            action.value = 'ACCEPT';
            for (const [name, input] of Object.entries(inputs)) {
                input.id = 'mapping-' + name + '-' + key;
                const caption = node('label', label(name));
                caption.htmlFor = input.id; caption.className = 'form-label';
                input.className = name === 'action' ? 'form-select' : 'form-control';
                form.append(caption, input);
            }
            parent.value = mapping.proposedParentCode;
            secondary.value = mapping.secondaryClassificationCodes.join(', ');
            rationale.required = true; rationale.maxLength = 2000;
            function mode() {parent.disabled = secondary.disabled = action.value !== 'CHANGE'; parent.required = !parent.disabled;}
            action.addEventListener('change', mode); mode();
            const save = node('button', label('submit'));
            save.type = 'submit'; save.className = 'btn btn-primary mt-2';

            form.append(save);
            form.addEventListener('submit', async event => {
                event.preventDefault();
                if (signal.aborted || !head || pending.has(key) || !rationale.value.trim()) return;
                const command = {catalogueRevision: queue.catalogueRevision, mappingVersion: queue.mappingVersion,
                    overlaySha256: queue.overlaySha256, expectedHead: head, action: action.value, rationale: rationale.value.trim()};
                if (action.value === 'CHANGE') {
                    command.proposedParent = parent.value.trim();
                    command.secondary = secondary.value.split(',').map(code => code.trim()).filter(Boolean);
                }
                pending.add(key); save.disabled = true;
                try {
                    const saved = await window.TaxonomyReferenceApi.reviewMapping(key, command);
                    if (signal.aborted) return;
                    head = saved.commit; history.prepend(paint(saved)); rationale.value = '';
                    feedback.textContent = label('saved');
                } catch (error) {
                    if (signal.aborted) return;
                    if (error.status === 401 || error.status === 403) {
                        reset(); status.textContent = label('unavailable'); return;
                    }
                    if (error.status === 409) head = null;
                    feedback.textContent = label(error.status === 409 ? 'conflict' : 'failed');
                } finally {pending.delete(key); save.disabled = !head;}
            });
            return form;
        }
        details.append(feedback, area);
        more.addEventListener('click', () => {if (cursor) loadHistory(cursor);});
        details.addEventListener('toggle', () => {
            if (details.open && !initialized && !signal.aborted) {initialized = true; loadHistory(null);}
        });
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
