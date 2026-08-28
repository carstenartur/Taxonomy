/* taxonomy-analysis-session-ui.js – complete derived-state invalidation and stale actions */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C) throw new Error('Analysis session core must load before UI lifecycle');
    var S = C.S;
    var runtime = C.runtime;
    var language = C.language;
    var text = C.text;
    var businessTextElement = C.businessTextElement;
    var hasDerivedAnalysis = C.hasDerivedAnalysis;
    var isStale = C.isStale;
    var statusArea = C.statusArea;
    var showActionAlert = C.showActionAlert;
    var guardStaleActions = C.guardStaleActions;
    var resetPanel = C.resetPanel;
    function queueSave() { return C.queueSave.apply(null, arguments); }
    function openRequirementDialog() { return C.openRequirementDialog.apply(null, arguments); }
    function openNewProjectDialog() { return C.openNewProjectDialog.apply(null, arguments); }
    function saveAsRequirementVersion() { return C.saveAsRequirementVersion.apply(null, arguments); }

    function clearDerivedUi() {
        if (window.TaxonomyScoring) {
            if (typeof window.TaxonomyScoring.cancelStreamingAnalysis === 'function') {
                window.TaxonomyScoring.cancelStreamingAnalysis();
            }
            if (typeof window.TaxonomyScoring.clearAnalysisLog === 'function') {
                window.TaxonomyScoring.clearAnalysisLog();
            }
            if (typeof window.TaxonomyScoring.renderArchitectureView === 'function') {
                window.TaxonomyScoring.renderArchitectureView(null);
            }
            if (typeof window.TaxonomyScoring.renderSuggestedRelations === 'function') {
                window.TaxonomyScoring.renderSuggestedRelations(null);
            }
        }

        var analysisLog = document.getElementById('analysisLog');
        if (analysisLog) {
            analysisLog.style.display = 'none';
            analysisLog.open = false;
        }
        var analysisLogContent = document.getElementById('analysisLogContent');
        if (analysisLogContent) analysisLogContent.replaceChildren();

        resetPanel('llmCommLogContent', language() === 'de'
            ? 'Noch keine LLM-Aufrufe. Starten Sie eine Analyse, um die Kommunikation zu sehen.'
            : 'No LLM calls yet. Run an analysis to see LLM communication details.');

        var architecturePanel = document.getElementById('architectureViewPanel');
        if (architecturePanel) architecturePanel.style.display = 'none';
        var architectureContent = document.getElementById('architectureViewContent');
        if (architectureContent) architectureContent.replaceChildren();
        var architecturePlaceholder = document.getElementById('architecturePlaceholder');
        if (architecturePlaceholder) architecturePlaceholder.style.display = '';

        var suggestedPanel = document.getElementById('suggestedRelationsPanel');
        if (suggestedPanel) suggestedPanel.style.display = 'none';
        var suggestedBadge = document.getElementById('suggestedRelationsBadge');
        if (suggestedBadge) suggestedBadge.textContent = '0';
        resetPanel('suggestedRelationsContent', language() === 'de'
            ? 'Noch keine vorgeschlagenen Beziehungen.'
            : 'No suggested relationships yet.');

        var copilotPanel = document.getElementById('copilotPanel');
        if (copilotPanel) copilotPanel.style.display = 'none';
        resetPanel('copilotContent', language() === 'de'
            ? 'Starten Sie den Copilot für eine vollständige Architekturanalyse.'
            : 'Run Copilot to start a full architecture analysis.');

        [
            ['gapAnalysisPanel', 'gapAnalysisContent', language() === 'de'
                ? 'Führen Sie eine Gap-Analyse aus, um fehlende Beziehungen und unvollständige Muster zu erkennen.'
                : 'Run gap analysis to identify missing relations and incomplete patterns.'],
            ['patternDetectionPanel', 'patternDetectionContent', language() === 'de'
                ? 'Erkennen Sie Architekturmuster in den bewerteten Knoten.'
                : 'Detect architecture patterns across scored nodes.'],
            ['recommendationPanel', 'recommendationContent', language() === 'de'
                ? 'Erzeugen Sie nach einer Analyse eine Architekturempfehlung.'
                : 'Generate an architecture recommendation after an analysis.']
        ].forEach(function (entry) {
            var panel = document.getElementById(entry[0]);
            if (panel) panel.open = false;
            resetPanel(entry[1], entry[2]);
        });

        var impact = document.getElementById('requirementImpactResults');
        if (impact) {
            impact.style.display = 'none';
            impact.replaceChildren();
        }

        ['analyzeViewContext', 'exportViewContext', 'graphViewContext'].forEach(function (id) {
            var element = document.getElementById(id);
            if (!element) return;
            element.replaceChildren();
            element.classList.add('d-none');
        });

        resetPanel('provenanceContent', language() === 'de'
            ? 'Provenienzinformationen werden nach einer Analyse angezeigt.'
            : 'Requirement provenance information will be displayed after analysis.');

        var summaryButton = document.getElementById('viewSummary');
        if (summaryButton) summaryButton.style.display = 'none';
        var manualApply = document.getElementById('manualApplyBtn');
        if (manualApply) manualApply.remove();

        var exportGroup = document.getElementById('exportGroup');
        if (exportGroup) exportGroup.style.display = 'none';
        var exportHint = document.getElementById('exportHint');
        if (exportHint) exportHint.classList.remove('d-none');

        if (window.TaxonomyBrowse && Array.isArray(S.taxonomyData) && S.taxonomyData.length) {
            if (S.currentView === 'summary'
                    && typeof window.TaxonomyBrowse.switchView === 'function') {
                window.TaxonomyBrowse.switchView('list');
            } else if (typeof window.TaxonomyBrowse.renderView === 'function') {
                window.TaxonomyBrowse.renderView(S.taxonomyData, null);
            }
            if (typeof window.TaxonomyBrowse.updateExportGroupVisibility === 'function') {
                window.TaxonomyBrowse.updateExportGroupVisibility();
            }
        }
    }

    function invalidate(options) {
        options = options || {};
        var input = businessTextElement();
        var preservedText = input ? input.value : '';

        runtime.invalidating = true;
        S.currentScores = null;
        S.currentReasons = {};
        S.currentDiscrepancies = [];
        S.currentProductCoverageGaps = [];
        S.currentArchView = null;
        S.storedBusinessText = null;
        S.evaluatedNodes = new Set();
        S.lastAnalyzedText = null;
        S.pendingProposalNodeCode = null;
        window._taxonomyCurrentScores = null;
        window._currentProvisionalRelations = [];

        if (input) {
            input.value = options.keepText === false ? '' : preservedText;
            input.classList.remove('stale-results');
        }

        clearDerivedUi();
        guardStaleActions(false);
        runtime.conflict = false;
        runtime.invalidating = false;

        document.dispatchEvent(new CustomEvent('taxonomy:analysis-invalidated', {
            detail: {
                reason: options.reason || 'user',
                keptRequirementText: options.keepText !== false
            }
        }));

        if (!options.silent) {
            showActionAlert('info', text('discarded'), '', [], 'discarded');
        }
        queueSave(0);
    }

    function discardTextChange() {
        var input = businessTextElement();
        if (!input || S.lastAnalyzedText === null) return;
        input.value = S.lastAnalyzedText;
        input.classList.remove('stale-results');
        guardStaleActions(false);
        if (statusArea()) {
            statusArea().replaceChildren();
            delete statusArea().dataset.analysisSessionMessage;
        }
        queueSave(0);
        input.focus();
    }

    function projectContextFromUrl() {
        var parameters = new URLSearchParams(window.location.search);
        var projectId = parameters.get('projectId');
        var requirementId = parameters.get('requirementId');
        return projectId && requirementId
            ? { projectId: projectId, requirementId: requirementId }
            : null;
    }

    function analysisRunning() {
        var button = document.getElementById('analyzeBtn');
        var spinner = document.getElementById('analyzeSpinner');
        return Boolean(button && button.disabled && spinner
            && !spinner.classList.contains('d-none'));
    }

    function showStaleActions() {
        if (!isStale() || runtime.conflict || analysisRunning()) return;
        var area = statusArea();
        if (area && area.dataset.analysisSessionMessage === 'stale'
                && area.querySelector('[data-analysis-session-action="discard-analysis"]')) {
            guardStaleActions(true);
            return;
        }

        var actions = [
            {
                id: 'discard-edit',
                label: text('discardEdit'),
                className: 'btn btn-sm btn-outline-secondary',
                handler: discardTextChange
            },
            {
                id: 'discard-analysis',
                label: text('discardAnalysis'),
                className: 'btn btn-sm btn-warning',
                handler: function () {
                    invalidate({ keepText: true, reason: 'requirement-text-changed' });
                }
            },
            {
                id: 'add-requirement',
                label: text('addRequirement'),
                className: 'btn btn-sm btn-outline-primary',
                handler: function () {
                    openRequirementDialog(businessTextElement().value);
                }
            },
            {
                id: 'new-project',
                label: text('newProject'),
                className: 'btn btn-sm btn-danger',
                handler: function () {
                    openNewProjectDialog({ seedRequirementText: null });
                }
            }
        ];

        var context = projectContextFromUrl();
        if (context) {
            actions.splice(2, 0, {
                id: 'save-version',
                label: text('saveVersion'),
                className: 'btn btn-sm btn-primary',
                handler: function () {
                    saveAsRequirementVersion(context, businessTextElement().value);
                }
            });
        }

        showActionAlert('warning', text('staleTitle'), text('staleBody'), actions, 'stale');
        guardStaleActions(true);
    }

    function queueStaleActions() {
        window.clearTimeout(runtime.staleTimer);
        runtime.staleTimer = window.setTimeout(function () {
            if (isStale()) {
                guardStaleActions(true);
                if (!analysisRunning()) showStaleActions();
            } else {
                guardStaleActions(false);
            }
        }, 340);
    }

    Object.assign(C, {
        clearDerivedUi: clearDerivedUi,
        invalidate: invalidate,
        discardTextChange: discardTextChange,
        projectContextFromUrl: projectContextFromUrl,
        analysisRunning: analysisRunning,
        showStaleActions: showStaleActions,
        queueStaleActions: queueStaleActions
    });
}());
