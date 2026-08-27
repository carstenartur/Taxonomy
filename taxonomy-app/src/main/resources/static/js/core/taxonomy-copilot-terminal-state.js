/* taxonomy-copilot-terminal-state.js – require authoritative completion before Copilot continues */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C) throw new Error('Analysis session core must load before Copilot terminal guard');
    if (window.setInterval.__taxonomyCopilotTerminalGuard === true) return;

    var delegatedSetInterval = window.setInterval.bind(window);
    var delegatedClearInterval = window.clearInterval.bind(window);

    function analysisRunning() {
        var button = document.getElementById('analyzeBtn');
        var spinner = document.getElementById('analyzeSpinner');
        return Boolean(button && button.disabled && spinner
            && !spinner.classList.contains('d-none'));
    }

    function copilotRunning() {
        var button = document.getElementById('copilotBtn');
        var spinner = document.getElementById('copilotSpinner');
        return Boolean(button && button.disabled && spinner
            && !spinner.classList.contains('d-none'));
    }

    function hasScores(scores) {
        return Boolean(scores && typeof scores === 'object'
            && Object.keys(scores).length > 0);
    }

    function hasCurrentScores() {
        return hasScores(window._taxonomyCurrentScores);
    }

    function hasKnownNonAuthoritativeStatus() {
        var status = String(C.S.lastAnalysisStatus || '').toUpperCase();
        return Boolean(status && status !== 'SUCCESS' && status !== 'IMPORTED');
    }

    function resetCopilotControls() {
        var button = document.getElementById('copilotBtn');
        var spinner = document.getElementById('copilotSpinner');
        if (button) button.disabled = false;
        if (spinner) spinner.classList.add('d-none');
    }

    function showIncompleteAnalysis() {
        var panel = document.getElementById('copilotPanel');
        if (panel) panel.style.display = '';
        var content = document.getElementById('copilotContent');
        if (!content) return;
        var alert = document.createElement('div');
        alert.className = 'alert alert-danger py-1 px-2 small mb-0';
        alert.textContent = C.language() === 'de'
            ? 'Die Hauptanalyse ist fehlgeschlagen oder wurde nicht erfolgreich vollständig '
                + 'abgeschlossen. Die konkrete Ursache steht im Analysestatus. Beheben Sie sie '
                + 'und starten Sie den Copilot erneut.'
            : 'The main analysis failed or did not complete successfully. The concrete cause is '
                + 'shown in the analysis status. Resolve it and run Copilot again.';
        content.replaceChildren(alert);
    }

    function terminalSetInterval(callback, delay) {
        // The transport guard already suppresses legacy poll ticks while analysis
        // is busy and handles ERROR. This final boundary also rejects PARTIAL,
        // UNKNOWN or missing terminal state even when score keys already exist.
        var tracked = copilotRunning() && analysisRunning();
        var id = delegatedSetInterval(function () {
            if (tracked && !analysisRunning()
                    && C.S.lastAnalysisStatus !== 'SUCCESS') {
                delegatedClearInterval(id);
                resetCopilotControls();
                showIncompleteAnalysis();
                return;
            }
            callback();
        }, delay);
        return id;
    }

    document.addEventListener('click', function (event) {
        var target = event.target && typeof event.target.closest === 'function'
            ? event.target.closest('#copilotBtn') : null;
        if (!target || !hasCurrentScores() || !hasKnownNonAuthoritativeStatus()) return;

        // A previous partial/failed analysis may leave score keys behind. Do not
        // let the legacy hasScores() shortcut treat them as a completed manual,
        // imported or successful score set on a later Copilot invocation.
        event.preventDefault();
        event.stopImmediatePropagation();
        resetCopilotControls();
        showIncompleteAnalysis();
    }, true);

    document.addEventListener('click', function (event) {
        var target = event.target && typeof event.target.closest === 'function'
            ? event.target.closest('#manualApplyBtn') : null;
        if (!target || !hasScores(C.S.currentScores)) return;

        // This bubbling listener runs after the legacy button handler has applied
        // the expert-entered scores. Make their authority explicit even if a prior
        // AI attempt left PARTIAL or ERROR in the shared state.
        C.S.lastAnalysisProvider = 'MANUAL';
        C.S.lastAnalysisStatus = 'SUCCESS';
    });

    Object.defineProperty(terminalSetInterval, '__taxonomyCopilotTerminalGuard', {
        configurable: false,
        enumerable: false,
        value: true,
        writable: false
    });
    window.setInterval = terminalSetInterval;
}());
