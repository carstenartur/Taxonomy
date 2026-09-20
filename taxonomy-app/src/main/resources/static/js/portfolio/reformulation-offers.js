/* Read-only saved proposal surface. All external strings go through textContent. */
(function () {
    'use strict';
    const match = window.location.pathname.match(/^\/projects\/(\d+)\/requirements\/(\d+)\/?$/);
    const container = document.getElementById('reformulationList');
    if (!match || !container) return;
    const de = (new URLSearchParams(window.location.search).get('lang') || document.documentElement.lang || 'en').startsWith('de');
    const labels = de ? {
        heading: 'Neuformulierungsangebote', empty: 'Noch kein Neuformulierungsangebot gespeichert.',
        state: 'Neuformulierungsangebot – nicht übernommen', original: 'Original', proposal: 'Vorschlag',
        questions: 'Fragen', noQuestions: 'Keine Fragen erfasst. Dies ist keine fachliche Freigabe.',
        source: 'Quellversion', revision: 'Entwurfsrevision', loading: 'Wird geladen…',
        failure: 'Neuformulierungsangebote konnten nicht geladen werden.', details: 'Gespeicherten Entwurf anzeigen',
        unevaluated: 'Entwurf: Quellabdeckung noch nicht geprüft.'
    } : {
        heading: 'Reformulation offers', empty: 'No reformulation offer has been saved yet.',
        state: 'Reformulation offer – not adopted', original: 'Original', proposal: 'Proposal',
        questions: 'Questions', noQuestions: 'No questions recorded. This does not mean expert approval.',
        source: 'Source version', revision: 'Draft revision', loading: 'Loading…',
        failure: 'Reformulation offers could not be loaded.', details: 'Show saved draft',
        unevaluated: 'Draft: source coverage has not been evaluated.'
    };
    document.getElementById('reformulationHeading').textContent = labels.heading;
    container.textContent = labels.loading;
    function element(tag, value, classes) {
        const result = document.createElement(tag);
        if (value !== undefined) result.textContent = value;
        if (classes) result.className = classes;
        return result;
    }
    window.TaxonomyPortfolioApi.listReformulations(Number(match[1]), Number(match[2])).then(function (offers) {
        container.replaceChildren();
        if (!offers.length) { container.textContent = labels.empty; return; }
        offers.forEach(function (offer) {
            const details = element('details', undefined, 'border rounded p-3 mb-2');
            details.append(element('summary', labels.details + ' · ' + labels.revision + ' ' + offer.currentRevision.number));
            details.append(element('p', labels.state, 'fw-semibold mt-2'));
            details.append(element('p', labels.source + ' ' + offer.baseline.sourceVersionId + ' · Snapshot ' + offer.baseline.snapshotId, 'text-break'));
            details.append(element('p', labels.unevaluated, 'text-body-secondary'));
            const row = element('div', undefined, 'row g-3');
            [[labels.original, offer.baseline.originalText], [labels.proposal, offer.currentRevision.text]].forEach(function (entry) {
                const column = element('div', undefined, 'col-12 col-lg-6');
                column.append(element('h3', entry[0], 'h6'));
                column.append(element('pre', entry[1], 'text-wrap text-break border rounded p-3'));
                row.append(column);
            });
            details.append(row);
            details.append(element('h3', labels.questions, 'h6 mt-3'));
            if (!offer.currentRevision.questions.length) details.append(element('p', labels.noQuestions));
            offer.currentRevision.questions.forEach(function (question) {
                details.append(element('p', question.wording + ' (' + question.state + ')'));
            });
            container.append(details);
        });
    }).catch(function () { container.textContent = labels.failure; });
}());
