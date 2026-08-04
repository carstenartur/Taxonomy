/* Guided portfolio decisions loaded before the legacy portfolio handlers. */
(function () {
    'use strict';

    const locale = (new URLSearchParams(window.location.search).get('lang')
        || document.documentElement.lang || 'en').toLowerCase().startsWith('de') ? 'de' : 'en';
    const nodeCache = new Map();
    let currentConflictButton = null;
    let searchTimer = null;

    const labels = {
        en: {
            taxonomySearch: 'Search taxonomy by code or title',
            taxonomyHelp: 'Enter at least two characters. Suggestions show code, title and taxonomy area.',
            compareProducts: 'Compare product candidates',
            product: 'Product', coverage: 'Coverage', review: 'Review', selection: 'Selection',
            source: 'Source', exclusions: 'Hard exclusions', strengths: 'Strengths', weaknesses: 'Weaknesses',
            noExclusions: 'None recorded', conflictTitle: 'Review requirement conflict',
            requirementA: 'Requirement A', requirementB: 'Requirement B', conflictType: 'Conflict type',
            confidence: 'Confidence', evidence: 'Evidence', decision: 'Decision',
            proposed: 'Keep proposed', confirm: 'Confirm conflict', reject: 'Reject conflict',
            resolve: 'Resolve conflict', resolution: 'Resolution and rationale',
            resolutionHelp: 'Explain how the project resolves or accepts this conflict. The text becomes part of the audit trail.',
            cancel: 'Cancel', save: 'Save reviewed decision', required: 'A resolution is required when resolving a conflict.',
            saved: 'Conflict decision saved.', failed: 'The conflict decision could not be saved.'
        },
        de: {
            taxonomySearch: 'Taxonomie nach Code oder Titel durchsuchen',
            taxonomyHelp: 'Mindestens zwei Zeichen eingeben. Vorschläge zeigen Code, Titel und Taxonomiebereich.',
            compareProducts: 'Produktkandidaten vergleichen',
            product: 'Produkt', coverage: 'Abdeckung', review: 'Prüfung', selection: 'Auswahl',
            source: 'Quelle', exclusions: 'Harte Ausschlusskriterien', strengths: 'Stärken', weaknesses: 'Schwächen',
            noExclusions: 'Keine hinterlegt', conflictTitle: 'Anforderungskonflikt prüfen',
            requirementA: 'Anforderung A', requirementB: 'Anforderung B', conflictType: 'Konflikttyp',
            confidence: 'Konfidenz', evidence: 'Evidenz', decision: 'Entscheidung',
            proposed: 'Vorgeschlagen lassen', confirm: 'Konflikt bestätigen', reject: 'Konflikt verwerfen',
            resolve: 'Konflikt lösen', resolution: 'Lösung und Begründung',
            resolutionHelp: 'Beschreiben Sie, wie das Projekt diesen Konflikt löst oder akzeptiert. Der Text wird Teil des Audit-Trails.',
            cancel: 'Abbrechen', save: 'Geprüfte Entscheidung speichern', required: 'Beim Lösen eines Konflikts ist eine Begründung erforderlich.',
            saved: 'Konfliktentscheidung wurde gespeichert.', failed: 'Die Konfliktentscheidung konnte nicht gespeichert werden.'
        }
    };

    document.addEventListener('DOMContentLoaded', initialize);
    document.addEventListener('click', interceptConflictReview, true);

    function t(key) {
        return (labels[locale] && labels[locale][key]) || labels.en[key] || key;
    }

    function initialize() {
        ensureTaxonomyDatalist();
        ensureConflictDialog();
        enhanceCurrentDom();
        const observer = new MutationObserver(function (records) {
            records.forEach(function (record) {
                record.addedNodes.forEach(function (node) {
                    if (node.nodeType === Node.ELEMENT_NODE) enhanceCurrentDom(node);
                });
            });
        });
        observer.observe(document.body, { childList: true, subtree: true });
        document.getElementById('portfolioTabs')?.addEventListener('shown.bs.tab', function (event) {
            if (event.target.id === 'solutions-tab') refreshProductComparisons();
        });
    }

    function enhanceCurrentDom(root) {
        const scope = root && root.querySelectorAll ? root : document;
        scope.querySelectorAll('.solution-node-code, .product-node-code').forEach(enhanceTaxonomyInput);
        if (scope.matches && scope.matches('.solution-node-code, .product-node-code')) {
            enhanceTaxonomyInput(scope);
        }
        if (scope.querySelector?.('.portfolio-solution-card') || scope.matches?.('.portfolio-solution-card')) {
            refreshProductComparisons();
        }
    }

    function ensureTaxonomyDatalist() {
        if (document.getElementById('taxonomyNodeOptions')) return;
        const datalist = document.createElement('datalist');
        datalist.id = 'taxonomyNodeOptions';
        document.body.appendChild(datalist);
    }

    function enhanceTaxonomyInput(input) {
        if (input.dataset.taxonomyPicker === 'true') return;
        input.dataset.taxonomyPicker = 'true';
        input.setAttribute('list', 'taxonomyNodeOptions');
        input.setAttribute('autocomplete', 'off');
        input.setAttribute('aria-label', t('taxonomySearch'));
        input.placeholder = locale === 'de' ? 'z. B. CP-… oder Titel' : 'e.g. CP-… or title';
        const help = document.createElement('div');
        help.className = 'form-text small';
        help.textContent = t('taxonomyHelp');
        input.insertAdjacentElement('afterend', help);
        input.addEventListener('input', function () {
            window.clearTimeout(searchTimer);
            const query = input.value.trim();
            if (query.length < 2) return;
            searchTimer = window.setTimeout(function () { searchTaxonomy(query); }, 250);
        });
        input.addEventListener('change', function () {
            const selected = nodeCache.get(input.value.trim().toUpperCase());
            if (selected) input.title = selected.code + ' — ' + selected.name;
        });
    }

    async function searchTaxonomy(query) {
        try {
            const response = await fetch('/api/search?q=' + encodeURIComponent(query) + '&maxResults=30', {
                headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store'
            });
            if (!response.ok) return;
            const nodes = await response.json();
            const datalist = document.getElementById('taxonomyNodeOptions');
            datalist.textContent = '';
            nodes.forEach(function (node) {
                const code = String(node.code || '').toUpperCase();
                if (!code) return;
                nodeCache.set(code, { code: code, name: node.nameEn || node.nameDe || code });
                const option = document.createElement('option');
                option.value = code;
                option.label = [node.nameEn || node.nameDe || code, node.taxonomyRoot, node.hierarchyPath]
                    .filter(Boolean).join(' · ');
                datalist.appendChild(option);
            });
        } catch (error) {
            // Search suggestions are progressive enhancement; validation remains server-side.
        }
    }

    async function refreshProductComparisons() {
        const projectId = Number(localStorage.getItem('taxonomy.portfolio.projectId')) || null;
        if (!projectId) return;
        try {
            const response = await fetch('/api/projects/' + projectId + '/portfolio', {
                headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store'
            });
            if (!response.ok) return;
            const portfolio = await response.json();
            (portfolio.solutions || []).forEach(function (projectSolution) {
                const card = document.querySelector(
                    '.portfolio-solution-card .solution-action-select[data-project-solution-id="'
                        + projectSolution.id + '"]')?.closest('.portfolio-solution-card');
                if (!card) return;
                let comparison = card.querySelector('.product-comparison');
                if (!comparison) {
                    comparison = document.createElement('details');
                    comparison.className = 'product-comparison mt-3 border rounded p-2';
                    card.querySelector('.card-body').appendChild(comparison);
                }
                comparison.innerHTML = productComparison(projectSolution.productCandidates || []);
            });
        } catch (error) {
            // Existing product lists remain usable when the comparison enhancement cannot load.
        }
    }

    function productComparison(candidates) {
        const summary = '<summary class="fw-semibold">' + escapeHtml(t('compareProducts')) + '</summary>';
        if (!candidates.length) return summary + '<p class="small text-body-secondary mt-2 mb-0">—</p>';
        return summary + '<div class="table-responsive mt-2"><table class="table table-sm align-middle mb-0">'
            + '<thead><tr><th scope="col">' + escapeHtml(t('product')) + '</th><th scope="col">'
            + escapeHtml(t('coverage')) + '</th><th scope="col">' + escapeHtml(t('review'))
            + '</th><th scope="col">' + escapeHtml(t('selection')) + '</th><th scope="col">'
            + escapeHtml(t('exclusions')) + '</th><th scope="col">' + escapeHtml(t('source'))
            + '</th></tr></thead><tbody>'
            + candidates.map(function (candidate) {
                const product = candidate.product;
                return '<tr><td><strong>' + escapeHtml(product.manufacturer + ' ' + product.productName)
                    + '</strong><div class="small text-body-secondary">'
                    + escapeHtml(product.editionVersion || '') + '</div></td><td>'
                    + Number(candidate.coveragePercent) + '%</td><td>'
                    + escapeHtml(humanize(candidate.reviewStatus)) + '</td><td>'
                    + escapeHtml(humanize(candidate.selectionStatus)) + '</td><td>'
                    + escapeHtml(candidate.hardExclusions || t('noExclusions')) + '</td><td class="small">'
                    + escapeHtml(product.sourceReference || '—') + '</td></tr>';
            }).join('') + '</tbody></table></div>';
    }

    function interceptConflictReview(event) {
        const button = event.target.closest('.conflict-review');
        if (!button || button.disabled) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        currentConflictButton = button;
        const card = button.closest('.portfolio-conflict-card');
        const requirements = card?.querySelectorAll('code') || [];
        const evidence = card?.querySelector('.portfolio-evidence')?.textContent?.trim() || '';
        const title = card?.querySelector('strong')?.textContent?.trim() || '';
        const confidenceText = Array.from(card?.querySelectorAll('.small') || [])
            .map(element => element.textContent).find(value => /confidence|konfidenz/i.test(value)) || '—';
        document.getElementById('guidedConflictTitleText').textContent = title;
        document.getElementById('guidedConflictRequirementA').textContent = requirements[0]?.textContent || '—';
        document.getElementById('guidedConflictRequirementB').textContent = requirements[1]?.textContent || '—';
        document.getElementById('guidedConflictType').textContent = card?.querySelector('.badge')?.textContent || '—';
        document.getElementById('guidedConflictConfidence').textContent = confidenceText;
        document.getElementById('guidedConflictEvidence').textContent = evidence || '—';
        document.getElementById('guidedConflictDecision').value = button.dataset.status || 'PROPOSED';
        document.getElementById('guidedConflictResolution').value = '';
        document.getElementById('guidedConflictResolution').classList.remove('is-invalid');
        bootstrap.Modal.getOrCreateInstance(document.getElementById('guidedConflictDialog')).show();
    }

    function ensureConflictDialog() {
        if (document.getElementById('guidedConflictDialog')) return;
        const modal = document.createElement('div');
        modal.className = 'modal fade';
        modal.id = 'guidedConflictDialog';
        modal.tabIndex = -1;
        modal.setAttribute('aria-labelledby', 'guidedConflictDialogTitle');
        modal.setAttribute('aria-hidden', 'true');
        modal.innerHTML = '<div class="modal-dialog modal-lg"><form class="modal-content" id="guidedConflictForm">'
            + '<div class="modal-header"><h2 class="modal-title fs-5" id="guidedConflictDialogTitle">'
            + escapeHtml(t('conflictTitle')) + '</h2><button type="button" class="btn-close" '
            + 'data-bs-dismiss="modal" aria-label="Close"></button></div><div class="modal-body">'
            + '<h3 class="h6" id="guidedConflictTitleText"></h3><dl class="portfolio-card-meta">'
            + '<dt>' + escapeHtml(t('requirementA')) + '</dt><dd><code id="guidedConflictRequirementA"></code></dd>'
            + '<dt>' + escapeHtml(t('requirementB')) + '</dt><dd><code id="guidedConflictRequirementB"></code></dd>'
            + '<dt>' + escapeHtml(t('conflictType')) + '</dt><dd id="guidedConflictType"></dd>'
            + '<dt>' + escapeHtml(t('confidence')) + '</dt><dd id="guidedConflictConfidence"></dd>'
            + '<dt>' + escapeHtml(t('evidence')) + '</dt><dd id="guidedConflictEvidence"></dd></dl>'
            + '<div class="mb-3"><label for="guidedConflictDecision" class="form-label">'
            + escapeHtml(t('decision')) + '</label><select id="guidedConflictDecision" class="form-select">'
            + '<option value="PROPOSED">' + escapeHtml(t('proposed')) + '</option>'
            + '<option value="CONFIRMED">' + escapeHtml(t('confirm')) + '</option>'
            + '<option value="REJECTED">' + escapeHtml(t('reject')) + '</option>'
            + '<option value="RESOLVED">' + escapeHtml(t('resolve')) + '</option></select></div>'
            + '<div><label for="guidedConflictResolution" class="form-label">'
            + escapeHtml(t('resolution')) + '</label><textarea id="guidedConflictResolution" '
            + 'class="form-control" rows="4" maxlength="4000"></textarea><div class="form-text">'
            + escapeHtml(t('resolutionHelp')) + '</div><div class="invalid-feedback">'
            + escapeHtml(t('required')) + '</div></div></div><div class="modal-footer">'
            + '<button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">'
            + escapeHtml(t('cancel')) + '</button><button type="submit" class="btn btn-primary">'
            + escapeHtml(t('save')) + '</button></div></form></div>';
        document.body.appendChild(modal);
        document.getElementById('guidedConflictForm').addEventListener('submit', saveConflictDecision);
    }

    async function saveConflictDecision(event) {
        event.preventDefault();
        if (!currentConflictButton) return;
        const status = document.getElementById('guidedConflictDecision').value;
        const resolution = document.getElementById('guidedConflictResolution');
        if (status === 'RESOLVED' && !resolution.value.trim()) {
            resolution.classList.add('is-invalid');
            resolution.focus();
            return;
        }
        const projectId = Number(localStorage.getItem('taxonomy.portfolio.projectId')) || null;
        const headers = { Accept: 'application/json', 'Content-Type': 'application/json' };
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const headerName = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
        if (token) headers[headerName] = token;
        try {
            const response = await fetch('/api/projects/' + projectId + '/conflicts/'
                + currentConflictButton.dataset.conflictId, {
                method: 'PATCH', headers: headers, credentials: 'same-origin',
                body: JSON.stringify({ status: status, resolutionNote: resolution.value.trim() || null })
            });
            if (!response.ok) {
                const payload = await response.json().catch(function () { return null; });
                throw new Error(payload?.detail || payload?.message || t('failed'));
            }
            bootstrap.Modal.getOrCreateInstance(document.getElementById('guidedConflictDialog')).hide();
            const info = document.getElementById('portfolioInfo');
            if (info) { info.textContent = t('saved'); info.classList.remove('d-none'); }
            document.getElementById('portfolioStatus').textContent = t('saved');
            document.getElementById('refreshPortfolioBtn')?.click();
        } catch (error) {
            const target = document.getElementById('portfolioError');
            if (target) { target.textContent = error.message || t('failed'); target.classList.remove('d-none'); target.tabIndex = -1; target.focus(); }
        }
    }

    function humanize(value) {
        return String(value || '—').toLowerCase().replaceAll('_', ' ')
            .replace(/\b\w/g, function (character) { return character.toUpperCase(); });
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
    }
})();
