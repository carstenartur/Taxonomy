/* taxonomy-copilot-terminal-state.js – complete-analysis routing and authoritative Copilot completion */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C) throw new Error('Analysis session core must load before Copilot authority');
    var runtime = C.runtime || (C.runtime = {});

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

    function elementAriaDisabled(element) {
        return Boolean(element && typeof element.getAttribute === 'function'
            && element.getAttribute('aria-disabled') === 'true');
    }

    function resetCopilotControls() {
        var button = document.getElementById('copilotBtn');
        var spinner = document.getElementById('copilotSpinner');
        if (button) button.disabled = false;
        if (spinner) spinner.classList.add('d-none');
    }

    function showCopilotMessage(kind, german, english) {
        var panel = document.getElementById('copilotPanel');
        if (panel) panel.style.display = '';
        var content = document.getElementById('copilotContent');
        if (!content) return;
        var alert = document.createElement('div');
        alert.className = 'alert alert-' + kind + ' py-1 px-2 small mb-0';
        alert.textContent = C.language() === 'de' ? german : english;
        content.replaceChildren(alert);
    }

    function showIncompleteAnalysis() {
        showCopilotMessage(
            'danger',
            'Die Hauptanalyse ist fehlgeschlagen oder wurde nicht erfolgreich vollständig '
                + 'abgeschlossen. Die konkrete Ursache steht im Analysestatus. Beheben Sie sie '
                + 'und starten Sie den Copilot erneut.',
            'The main analysis failed or did not complete successfully. The concrete cause is '
                + 'shown in the analysis status. Resolve it and run Copilot again.'
        );
    }

    function showCopilotManualProviderFailure() {
        showCopilotMessage(
            'warning',
            'Für eine noch nicht bewertete Anforderung benötigt der Copilot einen KI-Anbieter. '
                + 'Wählen Sie einen konfigurierten Anbieter oder den Serverstandard; eine bereits '
                + 'abgeschlossene manuelle Bewertung kann der Copilot dagegen weiterverwenden.',
            'Copilot requires an AI provider when the requirement has not been scored yet. '
                + 'Select a configured provider or the server default; Copilot can reuse an '
                + 'already completed manual scoring result.'
        );
    }

    function showCopilotUnavailableFailure() {
        showCopilotMessage(
            'warning',
            'Der Copilot kann noch nicht gestartet werden. Warten Sie, bis die Taxonomie und der '
                + 'Analyseknopf verfügbar sind, oder beenden Sie zuerst die laufende Analyse.',
            'Copilot cannot start yet. Wait until the taxonomy and Analyze action are available, '
                + 'or finish the analysis that is already running.'
        );
    }

    function installCompleteAnalysisRouting() {
        if (runtime.completeAnalysisRoutingInstalled) return;
        runtime.completeAnalysisRoutingInstalled = true;

        document.addEventListener('click', function (event) {
            var closest = event.target && typeof event.target.closest === 'function'
                ? event.target.closest.bind(event.target) : null;
            if (!closest) return;

            var copilotTarget = closest('#copilotBtn');
            if (copilotTarget) {
                if (runtime.draftDecisionPending || runtime.conflict || runtime.invalidating) return;
                if (analysisRunning()) {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                    showCopilotUnavailableFailure();
                    return;
                }
                var existingScores = hasCurrentScores();
                var analyzeAction = document.getElementById('analyzeBtn');
                if (!existingScores && (!analyzeAction || analyzeAction.disabled
                        || elementAriaDisabled(analyzeAction))) {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                    showCopilotUnavailableFailure();
                    return;
                }
                var selectedProvider = document.getElementById('providerSelect');
                if (!existingScores && selectedProvider
                        && selectedProvider.value === 'MANUAL') {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                    showCopilotManualProviderFailure();
                }
                return;
            }

            var target = closest('#analyzeBtn');
            if (!target || target.disabled || elementAriaDisabled(target)
                    || runtime.draftDecisionPending || runtime.conflict
                    || runtime.invalidating) return;

            var provider = document.getElementById('providerSelect');
            if (provider && provider.value === 'MANUAL') {
                if (!copilotRunning()) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                resetCopilotControls();
                showCopilotManualProviderFailure();
                return;
            }

            var interactive = document.getElementById('interactiveMode');
            var completeRequested = copilotRunning()
                || !interactive || interactive.checked === false;
            if (!completeRequested) return;

            var scoring = window.TaxonomyScoring;
            if (!scoring || typeof scoring.runAnalysis !== 'function') return;
            event.preventDefault();
            event.stopImmediatePropagation();
            scoring.runAnalysis();
        }, true);
    }

    function installTerminalIntervalGuard() {
        if (window.setInterval.__taxonomyCopilotTerminalGuard === true) return;
        var delegatedSetInterval = window.setInterval.bind(window);
        var delegatedClearInterval = window.clearInterval.bind(window);

        function terminalSetInterval(callback, delay) {
            var tracked = copilotRunning() && analysisRunning();
            var id = delegatedSetInterval(function () {
                if (tracked && analysisRunning()) return;
                if (tracked && C.S.lastAnalysisStatus !== 'SUCCESS') {
                    delegatedClearInterval(id);
                    resetCopilotControls();
                    showIncompleteAnalysis();
                    return;
                }
                callback();
            }, delay);
            return id;
        }

        Object.defineProperty(terminalSetInterval, '__taxonomyCopilotTerminalGuard', {
            configurable: false,
            enumerable: false,
            value: true,
            writable: false
        });
        window.setInterval = terminalSetInterval;
    }

    installCompleteAnalysisRouting();
    installTerminalIntervalGuard();

    document.addEventListener('click', function (event) {
        var target = event.target && typeof event.target.closest === 'function'
            ? event.target.closest('#copilotBtn') : null;
        if (!target || !hasCurrentScores() || !hasKnownNonAuthoritativeStatus()) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        resetCopilotControls();
        showIncompleteAnalysis();
    }, true);

    document.addEventListener('click', function (event) {
        var target = event.target && typeof event.target.closest === 'function'
            ? event.target.closest('#manualApplyBtn') : null;
        if (!target || !hasScores(C.S.currentScores)) return;
        C.S.lastAnalysisProvider = 'MANUAL';
        C.S.lastAnalysisStatus = 'SUCCESS';
    });

    Object.assign(C, {
        analysisRunning: analysisRunning,
        copilotRunning: copilotRunning,
        hasCurrentScores: hasCurrentScores,
        installCompleteAnalysisRouting: installCompleteAnalysisRouting
    });
}());
