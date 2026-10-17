/* Explicit relation work supplements only a retained, owned node-only analysis. */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    var panel = document.getElementById('analysisRelationsExtension');
    var button = document.getElementById('extendAnalysisRelationsBtn');
    var hint = document.getElementById('analysisRelationsExtensionHint');
    if (!C || !panel || !button || !hint) return;
    function text(de, en) { return C.language() === 'de' ? de : en; }
    function render() {
        var S = C.S, source = S.recoveryContext, recovery = S.analysisRecovery;
        var scope = S.lastAnalysisScope || source?.request?.analysisScope;
        var nodeOnly = scope?.mode === 'TAXONOMIES_ONLY';
        panel.hidden = !nodeOnly;
        if (!nodeOnly) return;
        var sameText = source?.request?.businessText === (document.getElementById('businessText')?.value || '').trim();
        var owned = source && source.workspaceId === C.runtime.workspaceId && !C.runtime.invalidating;
        var complete = ['COMPLETED', 'COMPLETED_WITH_GAPS'].includes(recovery?.state);
        var compatible = window.TaxonomyAnalysisRecovery?.nodeEvidenceMatches(source);
        var eligible = owned && sameText && complete && compatible && source.request.analysisScope?.mode === 'TAXONOMIES_ONLY'
            && Number.isSafeInteger(recovery.version) && Boolean(window.TaxonomyAnalysisSession?.state?.().ready);
        button.disabled = !eligible;
        button.textContent = text('Relationssuche ergänzen', 'Add relationship search');
        hint.textContent = !owned || !recovery
            ? text('Für dieses importierte oder ältere Ergebnis fehlt eine gespeicherte Ausführungsquelle. Beziehungen können damit nicht sicher ergänzt werden.',
                'This imported or older result has no retained execution source. Relations cannot be safely added to this evidence.')
            : !sameText
                ? text('Die Anforderung wurde geändert. Zum Ergänzen muss der ursprüngliche Text wiederhergestellt werden.',
                    'The requirement has changed. Restore its original text before adding relations.')
                : !complete
                    ? text('Zuerst die laufende oder offene Knotenbewertung abschließen.', 'Complete the running or open node assessment first.')
                    : !compatible
                        ? text('Bewertungen oder Begründungen wurden bearbeitet oder ergänzt. Für die Relationssuche wird eine passende gespeicherte Analyse benötigt.',
                            'Assessments or explanations have been edited or extended. Relationship search requires a matching retained analysis.')
                    : text('Ergänzt nur Beziehungen mit dem gespeicherten Anbieter und Katalog. Bestehende Bewertungen und offene Knoten bleiben erhalten.',
                        'Adds relations using the saved provider and catalogue. Existing assessments and unassessed nodes are retained.');
    }
    button.addEventListener('click', function () {
        render();
        if (button.disabled) return;
        window.TaxonomyAnalysisRecovery?.extendRelations();
        render();
    });
    ['taxonomy:analysis-recovery-updated', 'taxonomy:view-rendered', 'taxonomy:analysis-evidence-imported',
        'taxonomy:analysis-draft-restored', 'taxonomy:analysis-draft-reset', 'taxonomy:analysis-invalidated']
        .forEach(function (name) { document.addEventListener(name, render); });
    document.getElementById('businessText')?.addEventListener('input', render);
    document.addEventListener('change', render);
    document.addEventListener('click', function () { Promise.resolve().then(render); });
    window.TaxonomyRelationsExtension = { render: render };
    render();
}());
