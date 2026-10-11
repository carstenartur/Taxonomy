/**
 * Product demo without AI (issue #1184).
 *
 * - On the analysis page, the trimmed input "produktdemo" plus the normal start
 *   button starts an isolated demo project instead of a provider analysis. The
 *   typed draft is left untouched; no other project or requirement is modified.
 * - On a demo requirement page, a persistent banner names the demo mode, the
 *   scenario stations, the visible demo answers and the reference comparison.
 *
 * All AI replies of a demo come from the server-side scenario replay. This module
 * never calls a provider and never decides, answers or adopts anything implicitly.
 * Server calls go through TaxonomyProductDemoApi; the requirement route comes from
 * TaxonomyRequirementDetail.route, so neither transport nor routing is duplicated.
 */
(function () {
    'use strict';

    const COMMAND = 'produktdemo';
    const resolveUrl = window.TaxonomyI18n?.resolveUrl || (path => path);
    const api = () => window.TaxonomyProductDemoApi;
    const locale = (new URLSearchParams(window.location.search).get('lang')
        || document.documentElement.lang || 'en').toLowerCase().startsWith('de') ? 'de' : 'en';
    const text = {
        de: {
            starting: 'Produktdemo wird isoliert angelegt …',
            failed: 'Die Produktdemo konnte nicht gestartet werden: ',
            stations: 'Stationen der Demo', decisions: 'Sichtbare Demo-Antworten (über den normalen Antwortpfad eingeben)',
            reviews: 'Demo-Prüfentscheidungen', candidateHint: 'Erzeugt die Neugenerierung nach Ihren Antworten einen geschützten Kandidaten, laden Sie ihn mit „Kandidat in den Editor laden“, speichern Sie den Entwurf und übernehmen Sie ihn danach mit Vorschau und Bestätigung.',
            provider: 'Aktive Szenario-Antwort', version: 'Anforderungsversion', compare: 'Referenzbewertung anzeigen',
            close: 'Demo beenden', closed: 'Diese Produktdemo ist beendet; weitere Szenario-Antworten werden abgewiesen.',
            overall: 'Gesamtergebnis', check: 'Prüfung', iteration: 'Iteration', status: 'Status', expected: 'Erwartet', actual: 'Beobachtet',
            notice: 'Nur die externe KI-Antwort wird durch das Szenario ersetzt. Analyse, Reformulierung, Übernahme und Exporte laufen über die normalen Produktpfade.'
        },
        en: {
            starting: 'Creating isolated product demo …',
            failed: 'The product demo could not be started: ',
            stations: 'Demo stations', decisions: 'Visible demo answers (enter them through the normal answer path)',
            reviews: 'Demo review decisions', candidateHint: 'If regeneration after your answers yields a protected candidate, use “Load candidate into editor”, save the draft and then adopt it with preview and confirmation.',
            provider: 'Active scenario reply', version: 'Requirement version', compare: 'Show reference evaluation',
            close: 'End demo', closed: 'This product demo is closed; further scenario replies are rejected.',
            overall: 'Overall result', check: 'Check', iteration: 'Iteration', status: 'Status', expected: 'Expected', actual: 'Observed',
            notice: 'Only the external AI reply is replaced by the scenario. Analysis, reformulation, adoption and exports use the normal product paths.'
        }
    };
    const t = key => text[locale][key] || text.en[key] || key;

    function errorMessage(error) {
        return (error && (error.detail || error.message)) || String(error);
    }

    function el(tag, content, cls) {
        const node = document.createElement(tag);
        if (content != null) node.textContent = content;
        if (cls) node.className = cls;
        return node;
    }

    function isDemoCommand(value) {
        return typeof value === 'string' && value.trim().toLowerCase() === COMMAND;
    }

    function requirementUrl(run) {
        return resolveUrl('/projects/' + run.projectId + '/requirements/' + run.requirementId)
            + '?lang=' + locale + '&demo=' + encodeURIComponent(run.id);
    }

    // ── Start from the analysis input ─────────────────────────────────────────
    let starting = false;
    async function startDemo(status) {
        if (starting) return null;
        starting = true;
        if (status) { status.textContent = t('starting'); status.classList.remove('d-none'); }
        try {
            const run = await api().startRun();
            window.location.assign(requirementUrl(run));
            return run;
        } catch (error) {
            if (status) status.textContent = t('failed') + errorMessage(error);
            return null;
        } finally {
            starting = false;
        }
    }

    function interceptAnalyze(event) {
        const target = event.target && typeof event.target.closest === 'function'
            ? event.target.closest('#analyzeBtn') : null;
        if (!target) return;
        const input = document.getElementById('businessText');
        if (!input || !isDemoCommand(input.value)) return;
        // The demo command never reaches the provider-backed analysis.
        event.preventDefault();
        event.stopImmediatePropagation();
        let status = document.getElementById('productDemoStatus');
        if (!status) {
            status = el('div', null, 'alert alert-info mt-2');
            status.id = 'productDemoStatus';
            status.setAttribute('role', 'status');
            input.insertAdjacentElement('afterend', status);
        }
        startDemo(status);
    }

    // ── Banner on demo requirement pages ──────────────────────────────────────
    function list(title, items) {
        const section = el('section', null, 'mt-2');
        section.append(el('h3', title, 'h6'));
        const ol = el('ol', null, 'small mb-1');
        items.forEach(item => ol.append(item));
        section.append(ol);
        return section;
    }

    function renderComparison(target, comparison) {
        target.replaceChildren();
        const report = comparison.report;
        target.append(el('p', t('overall') + ': ' + report.overall, 'fw-semibold mb-1'));
        const table = el('table', null, 'table table-sm small mb-0');
        const head = el('tr');
        ['check', 'iteration', 'status', 'expected', 'actual'].forEach(key => head.append(el('th', t(key))));
        const thead = el('thead');
        thead.append(head);
        table.append(thead);
        const body = el('tbody');
        report.checks.forEach(check => {
            const row = el('tr');
            row.dataset.demoCheck = check.id;
            [check.id, check.iteration, check.status, check.expected, check.actual]
                .forEach(value => row.append(el('td', value == null ? '' : String(value), 'text-break')));
            body.append(row);
        });
        table.append(body);
        target.append(table);
    }

    function renderBanner(run) {
        let banner = document.getElementById('productDemoBanner');
        if (!banner) {
            banner = el('section', null, 'alert alert-warning product-demo-banner');
            banner.id = 'productDemoBanner';
            banner.setAttribute('aria-label', run.banner[locale] || run.banner.en);
            const main = document.querySelector('main') || document.body;
            main.prepend(banner);
        }
        banner.replaceChildren();
        banner.dataset.demoRun = run.id;
        banner.append(el('strong', run.banner[locale] || run.banner.en, 'd-block'));
        banner.append(el('p', t('notice'), 'small mb-1'));
        banner.append(el('p', t('provider') + ': ' + (run.activeProvider || '–') + ' · '
            + t('version') + ': R' + Math.max(0, run.currentRequirementVersion - 1), 'small mb-1'));
        if (run.closedAt) {
            banner.append(el('p', t('closed'), 'small fw-semibold mb-1'));
        }
        const details = el('details');
        details.append(el('summary', t('stations')));
        details.append(list(t('stations'), run.stations.map(s => el('li', s[locale] || s.en))));
        details.append(list(t('decisions'), run.demoDecisions.map(d =>
            el('li', d.question + ': ' + d.values.join(', ') + (d.rationale ? ' – ' + d.rationale : '')))));
        details.append(list(t('reviews'), run.demoReviews.map(r =>
            el('li', r.action + ': ' + r.wording + (r.rationale ? ' – ' + r.rationale : '')))));
        details.append(el('p', t('candidateHint'), 'small mb-1'));
        banner.append(details);

        const controls = el('div', null, 'd-flex gap-2 mt-2 flex-wrap');
        const output = el('div', null, 'mt-2');
        output.setAttribute('aria-live', 'polite');
        const compare = el('button', t('compare'), 'btn btn-sm btn-outline-dark');
        compare.type = 'button';
        compare.dataset.demoCompare = '';
        compare.addEventListener('click', async () => {
            try { renderComparison(output, await api().comparison(run.id)); }
            catch (error) { output.textContent = t('failed') + errorMessage(error); }
        });
        controls.append(compare);
        if (!run.closedAt) {
            const close = el('button', t('close'), 'btn btn-sm btn-outline-danger');
            close.type = 'button';
            close.dataset.demoClose = '';
            close.addEventListener('click', async () => {
                try { renderBanner(await api().closeRun(run.id)); }
                catch (error) { output.textContent = t('failed') + errorMessage(error); }
            });
            controls.append(close);
        }
        banner.append(controls, output);
        return banner;
    }

    async function showBannerForRequirementPage() {
        const route = window.TaxonomyRequirementDetail?.route;
        if (!route) return null;
        let run;
        try { run = await api().runForProject(route.projectId); }
        catch (ignored) { return null; }
        return run && run.projectId === route.projectId ? renderBanner(run) : null;
    }

    window.addEventListener('click', interceptAnalyze, true);
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', showBannerForRequirementPage);
    else showBannerForRequirementPage();

    window.TaxonomyProductDemo = { isDemoCommand: isDemoCommand, startDemo: startDemo, renderBanner: renderBanner };
}());
