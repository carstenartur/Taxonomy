(function () {
    'use strict';
    const panel = document.getElementById('projectProductGaps');
    if (!panel) return;
    const route = window.location.pathname.match(/\/projects\/(\d+)\/reports\/?$/);
    const projectId = Number(route?.[1]);
    if (!Number.isSafeInteger(projectId) || projectId <= 0) return;
    const api = window.TaxonomyPortfolioApi;
    const filter = document.getElementById('productGapFilter');
    const classification = document.getElementById('productGapClassification');
    const status = document.getElementById('productGapStatus');
    const results = document.getElementById('productGapFamilies');
    const classes = ['CREATE', 'PROCURE', 'CHANGE', 'UNDECIDED'];
    const pending = new Set();
    let families = [];
    let writable = false;
    let generation = 0;
    let fetchController;

    const t = (key, ...args) => window.TaxonomyI18n.t('portfolio.gap.' + key, ...args);
    function element(tag, text) {
        const node = document.createElement(tag);
        if (text != null) node.textContent = text;
        return node;
    }
    function options(select) {
        for (const name of classes) {
            const option = element('option', t(name));
            option.value = name;
            select.append(option);
        }
    }
    function score(value) {
        return value == null ? t('unknown') : Intl.NumberFormat(document.documentElement.lang,
            {maximumFractionDigits: 6}).format(value) + '%';
    }
    function fact(parent, key, text) {
        parent.append(element('dt', t(key)), element('dd', text ?? t('unknown')));
    }
    function detail(family) {
        const details = element('details');
        const summary = element('summary', family.familyCode + ' — ' + family.familyName + ' · ' + t(family.classification));
        details.className = 'mb-2 text-break';
        details.dataset.gap = family.reviewKey;
        details.append(summary);
        if (family.currentReviewEvidence === false) details.append(element('p', t('changedEvidence')));
        const facts = element('dl');
        fact(facts, 'catalogue', family.catalogueFingerprint);
        details.append(facts);
        for (const observation of family.observations) {
            const group = element('details');
            group.append(element('summary', observation.requirementKey + ' v' + observation.requirementVersion + ' · ' + t('snapshot') + ' ' + observation.snapshotId));
            const evidence = element('dl');
            fact(evidence, 'threshold', score(observation.minimumSuitabilityScore));
            group.append(evidence, element('p', observation.reason));
            for (const product of observation.candidates) {
                const scores = element('dl');
                fact(scores, 'assessed', score(product.assessedScore));
                fact(scores, 'recorded', score(product.recordedScore));
                group.append(element('h4', product.code), scores);
                if (product.reason) group.append(element('p', product.reason));
            }
            details.append(group);
        }
        if (family.history.length) {
            const history = element('details');
            history.append(element('summary', t('history')));
            const entries = element('ol');
            for (const review of family.history) {
                const entry = element('li', 'v' + review.revision + ' · ' + t(review.classification) + ' · ' + review.reviewer + ' · ' + review.reviewedAt);
                entry.append(element('p', review.rationale), element('p', t('reviewEvidence') + ' · ' + review.evidenceFingerprint));
                entries.append(entry);
            }
            history.append(entries);
            details.append(history);
        }
        if (writable) details.append(editor(family));
        return details;
    }
    function editor(family) {
        const form = element('form');
        form.className = 'mt-2';
        const choose = element('select');
        const reason = element('textarea');
        choose.className = 'form-select'; reason.className = 'form-control';
        const choiceLabel = element('label', t('classification'));
        const reasonLabel = element('label', t('rationale'));
        choose.id = 'class-' + family.reviewKey; reason.id = 'reason-' + family.reviewKey;
        choiceLabel.htmlFor = choose.id; reasonLabel.htmlFor = reason.id;
        options(choose); choose.value = family.classification;
        reason.required = true; reason.maxLength = 2000;
        const save = element('button', t('save'));
        save.type = 'submit'; save.className = 'btn btn-primary mt-2';
        save.disabled = pending.has(family.reviewKey);
        form.append(choiceLabel, choose, reasonLabel, reason, save);
        form.addEventListener('submit', async event => {
            event.preventDefault();
            if (!reason.value.trim() || pending.has(family.reviewKey)) return;
            pending.add(family.reviewKey); save.disabled = true;
            const at = generation;
            try {
                const updated = await api.reviewProductGap(projectId, family.reviewKey, {
                    classification: choose.value, rationale: reason.value.trim(), expectedRevision: family.revision,
                    evidenceFingerprint: family.evidenceFingerprint
                });
                if (at !== generation) return;
                families = families.map(item => item.reviewKey === updated.reviewKey ? updated : item);
                render();
                const current = [...results.children].find(node => node.dataset.gap === family.reviewKey);
                if (current) {current.open = true; current.querySelector('summary').focus();}
                else classification.focus();
                status.textContent = t('saved', updated.revision);
            } catch (error) {
                if (at === generation) status.textContent = t(error.status === 409 ? 'conflict' : 'failed');
            } finally {
                pending.delete(family.reviewKey);
                save.disabled = false;
                const current = document.getElementById('class-' + family.reviewKey)?.form;
                if (current) current.querySelector('button').disabled = false;
            }
        });
        return form;
    }
    function render() {
        const term = filter.value.trim().toLocaleLowerCase();
        const shown = families.filter(family => (!classification.value || family.classification === classification.value)
            && (!term || [family.familyCode, family.familyName, ...family.observations.flatMap(observation =>
                [observation.requirementKey, ...observation.candidates.map(product => product.code)])]
                .some(value => value?.toLocaleLowerCase().includes(term))));
        results.replaceChildren(...shown.map(detail));
        status.textContent = t('count', shown.length);
    }
    function reset() {
        generation++;
        fetchController?.abort();
        results.replaceChildren(); families = []; writable = false;
        status.textContent = '';
    }
    async function load() {
        reset();
        const at = generation;
        const controller = new AbortController(); fetchController = controller;
        await window.TaxonomyI18n.ready();
        if (at !== generation) return;
        if (classification.options.length === 1) options(classification);
        status.textContent = t('loading');
        try {
            const [data, account] = await Promise.all([api.productGaps(projectId, {signal: controller.signal}), api.getAccount()]);
            if (at !== generation) return;
            writable = account?.architectureMutationAllowed === true;
            families = data; render();
        } catch (error) {
            if (at === generation) status.textContent = t('unavailable');
        }
    }
    panel.addEventListener('toggle', () => {if (panel.open) load(); else reset();});
    document.getElementById('loadProductGaps').addEventListener('click', load);
    filter.addEventListener('input', render);
    classification.addEventListener('change', render);
    document.addEventListener('taxonomy:repository-context-changed', () => {if (panel.open) load(); else reset();});
    window.addEventListener('pagehide', reset);
}());
