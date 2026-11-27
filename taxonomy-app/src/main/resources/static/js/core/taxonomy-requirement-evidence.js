/* Recorded requirement-to-catalogue evidence. No score thresholds or implied human approval. */
(function () {
    'use strict';
    if (window.TaxonomyRequirementEvidence) return;
    const order = ['CONFLICT', 'UNRESOLVED', 'SUPPORTED', 'NOT_APPLICABLE', 'OUT_OF_SCOPE', 'NOT_ASSESSED'];
    const colors = {CONFLICT:'danger', UNRESOLVED:'warning', SUPPORTED:'success'};
    const labels = {
        en: {
            title:'Requirement contributions and evidence', explanation:'AI proposals for the recorded requirement. Colour describes the evidence assessment; it is separate from numerical ranking and human approval.',
            SUPPORTED:'Supported', UNRESOLVED:'Clarification needed', CONFLICT:'Conflicting evidence', NOT_APPLICABLE:'Not applicable', OUT_OF_SCOPE:'Outside the considered scope', NOT_ASSESSED:'Not assessed',
            DIRECT_SUBJECT:'Direct subject', PROVIDES:'Provides', SUPPORTS:'Supports', REQUIRES:'Requires', CONSTRAINS:'Constrains', UNDETERMINED:'Undetermined',
            EXPLICIT:'Explicit source', INFERRED:'Inferred from source', UNSPECIFIED:'Unspecified', REQUIRED:'Required', OPTIONAL:'Optional', ALTERNATIVE:'Alternative', CONTEXT:'Context',
            DELIVERY:'Requested delivery', EXISTING_ENVIRONMENT:'Existing environment', EXCLUDED:'Excluded',
            PRODUCT:'Product', SERVICE:'Service', PROCESS:'Process', INFORMATION:'Information', ROLE:'Role', CAPABILITY:'Capability',
            FUNCTIONAL:'Functional', QUALITY:'Quality', CONSTRAINT:'Constraint', ORGANIZATIONAL:'Organizational', GOAL:'Goal',
            quote:'Original passage', rationale:'Interpretation', question:'Open question', condition:'Condition', kinds:'Recorded aspects',
            classified:'Nodes with recorded assessments', missing:'Scored nodes without assessment metadata', relation:'Contribution', basis:'Basis', necessity:'Necessity', scope:'Scope', details:'Inspect recorded passages'
        },
        de: {
            title:'Anforderungsbeiträge und Beleglage', explanation:'KI-Vorschläge zur aufgezeichneten Anforderung. Die Farbe beschreibt die Bewertung der Beleglage; sie ist unabhängig von numerischer Gewichtung und fachlicher Freigabe.',
            SUPPORTED:'Belegt', UNRESOLVED:'Klärung erforderlich', CONFLICT:'Widersprüchliche Belege', NOT_APPLICABLE:'Nicht anwendbar', OUT_OF_SCOPE:'Außerhalb des betrachteten Umfangs', NOT_ASSESSED:'Nicht beurteilt',
            DIRECT_SUBJECT:'Direkter Gegenstand', PROVIDES:'Stellt bereit', SUPPORTS:'Unterstützt', REQUIRES:'Benötigt', CONSTRAINS:'Schränkt ein', UNDETERMINED:'Ungeklärt',
            EXPLICIT:'Explizite Quelle', INFERRED:'Aus Quelle abgeleitet', UNSPECIFIED:'Nicht festgelegt', REQUIRED:'Erforderlich', OPTIONAL:'Optional', ALTERNATIVE:'Alternative', CONTEXT:'Kontext',
            DELIVERY:'Geforderter Leistungsumfang', EXISTING_ENVIRONMENT:'Bestehende Umgebung', EXCLUDED:'Ausgeschlossen',
            PRODUCT:'Produkt', SERVICE:'Service', PROCESS:'Prozess', INFORMATION:'Information', ROLE:'Rolle', CAPABILITY:'Fähigkeit',
            FUNCTIONAL:'Funktional', QUALITY:'Qualität', CONSTRAINT:'Randbedingung', ORGANIZATIONAL:'Organisatorisch', GOAL:'Ziel',
            quote:'Originalpassage', rationale:'Interpretation', question:'Offene Frage', condition:'Bedingung', kinds:'Erfasste Aspekte',
            classified:'Knoten mit aufgezeichneter Beurteilung', missing:'Bewertete Knoten ohne Beurteilungsmetadaten', relation:'Beitrag', basis:'Belegart', necessity:'Verbindlichkeit', scope:'Umfang', details:'Aufgezeichnete Passagen prüfen'
        }
    };
    const array = value => Array.isArray(value) ? value : [];
    const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    function language(options) {
        return String(options?.locale || (typeof document !== 'undefined' ? document.documentElement?.lang : '') || 'en').startsWith('de') ? 'de' : 'en';
    }
    function status(claims) {
        const values = array(claims).map(claim => claim?.evidenceStatus);
        return order.find(value => values.includes(value)) || 'NOT_ASSESSED';
    }
    function questions(analysis) {
        return Object.entries(analysis?.nodeAssessments || {}).flatMap(([code, claims]) => array(claims)
            .filter(claim => claim?.evidenceStatus === 'UNRESOLVED' && typeof claim.question === 'string' && claim.question.trim())
            .map(claim => ({code, question:claim.question, requirementQuote:claim.requirementQuote || '', rationale:claim.rationale || ''})));
    }
    function badge(value, text) {
        return '<span class="requirement-evidence-status text-bg-' + (colors[value] || 'secondary')
            + '" data-evidence-status="' + escape(value) + '">' + escape(text) + '</span>';
    }
    function html(analysis, options) {
        options = options || {};
        const t = labels[language(options)], map = analysis?.nodeAssessments || {};
        const entries = Object.entries(map).filter(([code, claims]) => (!options.nodeCode || options.nodeCode === code) && array(claims).length)
            .sort((a,b) => order.indexOf(status(a[1])) - order.indexOf(status(b[1])) || a[0].localeCompare(b[0]));
        const kinds = new Set(), names = new Map();
        function visit(nodes) {
            array(nodes).forEach(node => { if (!node) return; names.set(node.code, node.name || node.nameEn || node.code); visit(node.children); });
        }
        visit(options.tree || analysis?.tree);
        entries.forEach(([, claims]) => array(claims).forEach(claim => {
            if (typeof claim.requirementQuote !== 'string' || !claim.requirementQuote.trim()) return;
            [...array(claim.subjectKinds), ...array(claim.requirementKinds)].forEach(kind => { if (kind !== 'UNSPECIFIED') kinds.add(kind); });
        }));
        const missing = Object.keys(analysis?.scores || {}).filter(code => !array(map[code]).length && (!options.nodeCode || code === options.nodeCode)).length;
        let result = '<section class="requirement-evidence border rounded p-3 my-3" data-requirement-evidence>'
            + '<h3 class="h6">' + escape(t.title) + '</h3><p class="small text-body-secondary">' + escape(t.explanation) + '</p>';
        if (!entries.length) return result + '<p class="mb-0">' + badge('NOT_ASSESSED', t.NOT_ASSESSED) + '</p></section>';
        result += '<p class="small mb-2">' + escape(t.classified) + ': ' + entries.length + ' · ' + escape(t.missing) + ': ' + missing + '</p>';
        if (kinds.size) result += '<p class="small">' + escape(t.kinds) + ': ' + escape(Array.from(kinds).map(k => t[k] || k).join(' · ')) + '</p>';
        result += '<details class="requirement-evidence-list"><summary>' + escape(t.details) + '</summary>';
        entries.forEach(([code, claims]) => {
            const aggregate = status(claims);
            result += '<details class="requirement-evidence-node mt-2" data-evidence-node="' + escape(code) + '"><summary>'
                + badge(aggregate, t[aggregate]) + ' <strong>' + escape(code) + '</strong>'
                + (names.has(code) && names.get(code) !== code ? ' · ' + escape(names.get(code)) : '') + '</summary>';
            array(claims).forEach(claim => {
                const value = order.includes(claim.evidenceStatus) ? claim.evidenceStatus : 'NOT_ASSESSED';
                result += '<article class="requirement-evidence-claim mt-2 p-3 border rounded">' + badge(value, t[value])
                    + '<dl class="requirement-evidence-meta mt-2">';
                [['relation',claim.contributionType],['basis',claim.evidenceBasis],['necessity',claim.necessity],['scope',claim.scope]].forEach(([key,v]) => {
                    result += '<dt>' + escape(t[key]) + '</dt><dd>' + escape(t[v] || v || t.UNSPECIFIED) + '</dd>';
                });
                result += '<dt>' + escape(t.kinds) + '</dt><dd>' + escape([...array(claim.subjectKinds), ...array(claim.requirementKinds)].map(v => t[v] || v).join(' · ')) + '</dd></dl>';
                [['quote',claim.requirementQuote],['condition',claim.condition],['rationale',claim.rationale],['question',claim.question]].forEach(([key,v]) => {
                    if (typeof v !== 'string' || !v.trim()) return;
                    result += '<div class="requirement-evidence-' + key + ' mt-2"><strong>' + escape(t[key]) + '</strong>'
                        + (key === 'quote' ? '<blockquote>' : '<p>') + escape(v) + (key === 'quote' ? '</blockquote>' : '</p>') + '</div>';
                });
                result += '</article>';
            });
            result += '</details>';
        });
        return result + '</details></section>';
    }
    function refresh() {
        if (typeof document === 'undefined' || !window.TaxonomyState) return;
        const state = window.TaxonomyState, anchor = document.getElementById('statusArea');
        if (!anchor) return;
        let host = document.getElementById('taxonomyRequirementEvidence');
        if (!host) { host = document.createElement('div'); host.id = 'taxonomyRequirementEvidence'; anchor.insertAdjacentElement('afterend', host); }
        const visible = state.currentScores !== null || Object.keys(state.currentNodeAssessments || {}).length > 0;
        host.hidden = !visible;
        const next = visible ? html({nodeAssessments:state.currentNodeAssessments || {}, scores:state.currentScores || {}, tree:state.taxonomyData}) : '';
        if (host.__evidenceHtml === next) return;
        const opened = Array.from(host.querySelectorAll?.('details[open][data-evidence-node]') || []).map(node => node.dataset.evidenceNode);
        const listOpen = host.querySelector?.('.requirement-evidence-list')?.open;
        host.innerHTML = next; host.__evidenceHtml = next;
        const list = host.querySelector?.('.requirement-evidence-list'); if (list) list.open = Boolean(listOpen);
        host.querySelectorAll?.('[data-evidence-node]').forEach(node => { node.open = opened.includes(node.dataset.evidenceNode); });
    }
    let scheduled = false;
    function schedule() {
        if (scheduled) return;
        scheduled = true;
        queueMicrotask(() => { scheduled = false; refresh(); });
    }
    window.TaxonomyRequirementEvidence = {html, status, questions, refresh:schedule};
    if (typeof document !== 'undefined' && document.addEventListener) {
        ['taxonomy:view-rendered','taxonomy:analysis-progress','taxonomy:analysis-invalidated','taxonomy:analysis-evidence-imported',
            'taxonomy:analysis-draft-restored','taxonomy:analysis-draft-reset'].forEach(name => document.addEventListener(name, schedule));
        schedule();
    }
}());
