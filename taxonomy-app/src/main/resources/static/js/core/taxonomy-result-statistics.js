/* Scoped result inventory. Event driven; no polling, extra requests or inferred approval. */
(function () {
    'use strict';
    if (window.TaxonomyResultStatistics) return;
    function list(value) { return Array.isArray(value) ? value : []; }
    function summary(state, de) {
        function t(german, english) { return de ? german : english; }
        var seenObjects = new Set(), nodes = new Map(), roots = new Map();
        var stack = list(state.taxonomyData).slice();
        while (stack.length) {
            var node = stack.pop();
            if (!node || typeof node !== 'object' || seenObjects.has(node)) continue;
            seenObjects.add(node);
            list(node.children).forEach(function (child) { stack.push(child); });
            if (typeof node.code !== 'string' || !node.code || nodes.has(node.code)) continue;
            nodes.set(node.code, node);
            var root = node.taxonomyRoot || t('Nicht zugeordnet', 'Unclassified');
            roots.set(root, (roots.get(root) || 0) + 1);
        }
        var parts = [];
        if (nodes.size) {
            parts.push(t('Geladene Knoten: ', 'Loaded nodes: ') + nodes.size);
            parts.push(Array.from(roots.keys()).sort().map(function (root) { return root + ': ' + roots.get(root); }).join(' · '));
            var containers = 0, positive = 0;
            nodes.forEach(function (node, code) {
                if (node.taxonomyContainer === true || (code === node.taxonomyRoot && node.level === 0 && !node.parentCode)) containers++;
                var score = state.currentScores && state.currentScores[code];
                if (typeof score === 'number' && Number.isFinite(score) && score > 0) positive++;
            });
            parts.push(t('Davon Navigationsknoten: ', 'Of these, navigation nodes: ') + containers);
            if (state.currentScores) parts.push(t('Positive Bewertungen: ', 'Positive assessments: ') + positive);
        }
        var view = state.currentArchView;
        if (view) {
            var elements = new Set(), relations = new Map();
            list(view.includedElements).forEach(function (node) { if (node && node.nodeCode) elements.add(node.nodeCode); });
            list(view.includedRelationships).forEach(function (relation) {
                if (!relation || !relation.sourceCode || !relation.targetCode || !relation.relationType) return;
                var key = JSON.stringify([relation.sourceCode, relation.targetCode, relation.relationType]);
                var proposal = list(relation.requirementEvidence).length > 0
                    || relation.origin === 'LLM_SUPPORTED' || relation.origin === 'SUGGESTED_CANDIDATE';
                relations.set(key, relations.get(key) === true || proposal);
            });
            var proposals = Array.from(relations.values()).filter(Boolean).length;
            parts.push(t('Architektur: ', 'Architecture: ') + elements.size + t(' Knoten', ' nodes'));
            parts.push(t('Beziehungen: ', 'Relationships: ') + relations.size
                + t(' · davon KI-Vorschläge: ', ' · of these AI proposals: ') + proposals);
            parts.push(t('Fachliche Bestätigung: nicht aufgezeichnet', 'Human confirmation: not recorded'));
        }
        return parts.join(' | ');
    }
    function render() {
        var state = window.TaxonomyState;
        if (!state) return;
        var line = document.getElementById('taxonomyResultStatistics');
        if (!line) {
            var anchor = document.getElementById('statusArea');
            if (!anchor) return;
            line = document.createElement('p');
            line.id = 'taxonomyResultStatistics';
            line.className = 'small text-body-secondary mt-2 mb-2';
            line.setAttribute('role', 'status');
            line.setAttribute('aria-live', 'polite');
            anchor.insertAdjacentElement('afterend', line);
        }
        var value = summary(state, (document.documentElement.lang || 'en').toLowerCase().startsWith('de'));
        if (line.textContent !== value) line.textContent = value;
        line.hidden = !value;
    }
    var scheduled = false;
    function schedule() {
        if (scheduled) return;
        scheduled = true;
        // The view event may precede assigning the final architecture in the same transaction.
        queueMicrotask(function () { scheduled = false; render(); });
    }
    window.TaxonomyResultStatistics = { refresh: schedule };
    ['taxonomy:view-rendered', 'taxonomy:analysis-progress', 'taxonomy:analysis-invalidated',
        'taxonomy:analysis-evidence-imported', 'taxonomy:analysis-draft-restored',
        'taxonomy:analysis-draft-reset'].forEach(function (name) { document.addEventListener(name, schedule); });
    schedule();
}());
