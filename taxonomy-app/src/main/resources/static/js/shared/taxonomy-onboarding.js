/* taxonomy-onboarding.js – Welcome overlay, task focus & progressive disclosure */

(function () {
    'use strict';
    var t = TaxonomyI18n.t;

    var STORAGE_KEY = 'taxonomy_onboarded';

    function text(en, de) {
        return document.documentElement.lang.toLowerCase().startsWith('de') ? de : en;
    }

    /**
     * Show the welcome overlay if the user has not dismissed it before.
     */
    function init() {
        if (localStorage.getItem(STORAGE_KEY)) {
            return; // User already dismissed the overlay
        }

        var overlay = document.createElement('div');
        overlay.className = 'onboarding-overlay';
        overlay.id = 'onboardingOverlay';
        overlay.innerHTML =
            '<div class="onboarding-card">' +
            '  <h2>' + t('onboarding.title') + '</h2>' +
            '  <p>' + t('onboarding.intro') + '</p>' +
            '  <div class="steps">' +
            '    <div class="step-item"><span class="step-number">1</span><span>' + t('onboarding.step1') + '</span></div>' +
            '    <div class="step-item"><span class="step-number">2</span><span>' + t('onboarding.step2') + '</span></div>' +
            '    <div class="step-item"><span class="step-number">3</span><span>' + t('onboarding.step3') + '</span></div>' +
            '  </div>' +
            '  <button id="onboardingDismiss" class="btn btn-primary">' + t('onboarding.dismiss') + '</button>' +
            '</div>';

        document.body.appendChild(overlay);

        var dismissBtn = document.getElementById('onboardingDismiss');
        if (dismissBtn) {
            dismissBtn.addEventListener('click', dismiss);
        }

        // Also dismiss on clicking the overlay backdrop
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) {
                dismiss();
            }
        });
    }

    function dismiss() {
        localStorage.setItem(STORAGE_KEY, '1');
        var overlay = document.getElementById('onboardingOverlay');
        if (overlay) {
            overlay.style.opacity = '0';
            overlay.style.transition = 'opacity 0.3s ease';
            setTimeout(function () { overlay.remove(); }, 300);
        }
    }

    /**
     * Reset onboarding (for testing or admin use).
     */
    function reset() {
        localStorage.removeItem(STORAGE_KEY);
    }

    function createOperationalContext() {
        if (document.getElementById('operationalContextDetails')) return;

        var navigation = document.getElementById('mainNavTabs');
        var navigationBar = navigation ? navigation.closest('.bg-dark') : null;
        var mainContent = document.getElementById('mainContent');
        if (!navigationBar || !mainContent) return;

        var details = document.createElement('details');
        details.id = 'operationalContextDetails';
        details.className = 'operational-context-details';
        details.innerHTML =
            '<summary>' +
            '  <span class="operational-context-title">' +
                text('Operational context', 'Betriebszustand') +
            '  </span>' +
            '  <span id="operationalContextSummary" class="badge bg-secondary">' +
                text('Loading', 'Wird geladen') +
            '  </span>' +
            '</summary>' +
            '<div id="operationalContextContent" class="operational-context-content" role="region" aria-label="' +
                text('Repository, model and workspace details', 'Repository-, Modell- und Arbeitsbereichsdetails') +
            '"></div>';
        navigationBar.insertAdjacentElement('afterend', details);

        var content = document.getElementById('operationalContextContent');
        ['aiStatusBadge', 'embeddingStatusBadge', 'workspaceUserBadge',
            'gitStatusBar', 'contextBar'].forEach(function (id) {
            var element = document.getElementById(id);
            if (element) content.appendChild(element);
        });

        var languageSelector = document.getElementById('langSelector');
        if (languageSelector) languageSelector.classList.add('ms-auto');

        function updateSummary() {
            var summary = document.getElementById('operationalContextSummary');
            if (!summary) return;
            var visibleElements = Array.from(content.children).filter(function (element) {
                return !element.classList.contains('d-none') &&
                    getComputedStyle(element).display !== 'none';
            });
            var combined = visibleElements.map(function (element) {
                return element.textContent || '';
            }).join(' ').toLowerCase();
            var danger = /error|failed|unavailable|conflict|diverged|fehler|nicht verfügbar|konflikt/.test(combined)
                || visibleElements.some(function (element) {
                    return element.classList.contains('bg-danger') ||
                        Boolean(element.querySelector('.bg-danger,.alert-danger,.text-danger'));
                });
            var warning = /warning|unknown|stale|behind|ahead|warnung|unbekannt|veraltet/.test(combined)
                || visibleElements.some(function (element) {
                    return element.classList.contains('bg-warning') ||
                        Boolean(element.querySelector('.bg-warning,.alert-warning,.text-warning'));
                });

            summary.className = 'badge ' + (danger ? 'bg-danger' : warning ?
                'bg-warning text-dark' : visibleElements.length ? 'bg-success' : 'bg-secondary');
            summary.textContent = danger ? text('Action required', 'Eingreifen erforderlich') :
                warning ? text('Check details', 'Details prüfen') :
                visibleElements.length ? text('Ready', 'Bereit') : text('Loading', 'Wird geladen');
            if (danger) details.open = true;
        }

        new MutationObserver(updateSummary).observe(content, {
            attributes: true, childList: true, subtree: true, characterData: true
        });
        updateSummary();
    }

    function createSecondaryToolsDisclosure() {
        if (document.getElementById('analysisSecondaryTools')) return;
        var analyzeTab = document.getElementById('tab-analyze');
        if (!analyzeTab) return;

        var details = document.createElement('details');
        details.id = 'analysisSecondaryTools';
        details.className = 'analysis-secondary-tools mt-3';
        details.innerHTML =
            '<summary class="fw-semibold">' +
                text('Additional analysis tools', 'Weitere Analysewerkzeuge') +
            '</summary>' +
            '<div id="analysisSecondaryToolsContent" class="analysis-secondary-tools-content"></div>';
        analyzeTab.appendChild(details);
        var content = document.getElementById('analysisSecondaryToolsContent');
        ['searchPanel', 'analysisLog', 'llmCommLog', 'gapAnalysisPanel',
            'patternDetectionPanel', 'recommendationPanel', 'documentImportPanel']
            .forEach(function (id) {
                var element = document.getElementById(id);
                if (element) content.appendChild(element);
            });

        var legend = document.querySelector('#tab-analyze .legend-box');
        var legendCard = legend ? legend.closest('.card') : null;
        if (legendCard && !document.getElementById('scoreLegendDetails')) {
            var legendDetails = document.createElement('details');
            legendDetails.id = 'scoreLegendDetails';
            legendDetails.className = 'score-legend-details mb-3';
            legendDetails.innerHTML = '<summary class="fw-semibold">' +
                text('How scores are shown', 'Darstellung der Bewertungen') + '</summary>';
            legendCard.parentNode.insertBefore(legendDetails, legendCard);
            legendCard.classList.remove('mb-3');
            legendCard.classList.add('mt-2');
            legendDetails.appendChild(legendCard);
        }
    }

    function createTaskProgress() {
        if (document.getElementById('analysisTaskProgress')) return;
        var analyzeTab = document.getElementById('tab-analyze');
        var firstCard = analyzeTab ? analyzeTab.querySelector('.card') : null;
        if (!analyzeTab || !firstCard) return;

        var progress = document.createElement('section');
        progress.id = 'analysisTaskProgress';
        progress.className = 'analysis-task-progress mb-3';
        progress.setAttribute('aria-label', text('Analysis progress', 'Fortschritt der Analyse'));
        progress.innerHTML =
            '<ol class="analysis-task-stages">' +
            '  <li id="taskStageDescribe" data-state="current"><span>1</span>' +
                text('Describe', 'Beschreiben') + '</li>' +
            '  <li id="taskStageAnalyze" data-state="pending"><span>2</span>' +
                text('Analyze', 'Analysieren') + '</li>' +
            '  <li id="taskStageReview" data-state="pending"><span>3</span>' +
                text('Review', 'Prüfen') + '</li>' +
            '  <li id="taskStageNext" data-state="pending"><span>4</span>' +
                text('Continue', 'Weiterarbeiten') + '</li>' +
            '</ol>' +
            '<div class="analysis-next-action">' +
            '  <span id="taskGuidance" class="small"></span>' +
            '  <button id="taskNextAction" type="button" class="btn btn-sm btn-primary"></button>' +
            '</div>';
        firstCard.parentNode.insertBefore(progress, firstCard);

        var businessText = document.getElementById('businessText');
        var analyzeButton = document.getElementById('analyzeBtn');
        var statusArea = document.getElementById('statusArea');
        var taxonomyTree = document.getElementById('taxonomyTree');
        var nextAction = document.getElementById('taskNextAction');
        var guidance = document.getElementById('taskGuidance');
        var action = 'describe';

        function setStage(current, state) {
            ['describe', 'analyze', 'review', 'next'].forEach(function (name) {
                var element = document.getElementById('taskStage' +
                    name.charAt(0).toUpperCase() + name.slice(1));
                if (!element) return;
                var order = ['describe', 'analyze', 'review', 'next'];
                var elementIndex = order.indexOf(name);
                var currentIndex = order.indexOf(current);
                element.dataset.state = name === current ? state || 'current' :
                    elementIndex < currentIndex ? 'complete' : 'pending';
                if (name === current) element.setAttribute('aria-current', 'step');
                else element.removeAttribute('aria-current');
            });
        }

        function configure(nextActionName, buttonText, guidanceText, disabled) {
            action = nextActionName;
            nextAction.textContent = buttonText;
            nextAction.disabled = Boolean(disabled);
            guidance.textContent = guidanceText;
        }

        function hasScores() {
            var scores = window.TaxonomyState && window.TaxonomyState.currentScores;
            return Boolean(scores && Object.keys(scores).length &&
                document.querySelector('#taxonomyTree .tax-pct'));
        }

        function sync() {
            var value = businessText ? businessText.value.trim() : '';
            var status = statusArea ? (statusArea.textContent || '').toLowerCase() : '';
            var error = /error|failed|unavailable|503|fehler|fehlgeschlagen|nicht verfügbar/.test(status);
            var stale = businessText && businessText.classList.contains('stale-results');
            var running = analyzeButton && (analyzeButton.disabled ||
                !document.getElementById('analyzeSpinner')?.classList.contains('d-none'));

            if (error) {
                setStage('analyze', 'error');
                configure('analyze', text('Retry analysis', 'Analyse erneut starten'),
                    text('Review the message, then retry the primary action.',
                        'Hinweis prüfen und anschließend die Hauptaktion erneut starten.'), false);
            } else if (running) {
                setStage('analyze', 'current');
                configure('none', text('Analysis running…', 'Analyse läuft…'),
                    text('The result state and next action will appear here.',
                        'Ergebniszustand und nächste Aktion erscheinen hier.'), true);
            } else if (hasScores() && !stale) {
                setStage('review', 'current');
                configure('review', text('Review highest matches', 'Beste Treffer prüfen'),
                    text('The analysis is complete. Inspect the strongest matches next.',
                        'Die Analyse ist abgeschlossen. Prüfen Sie nun die stärksten Treffer.'), false);
            } else if (value) {
                setStage('analyze', 'current');
                configure('analyze', text('Run analysis', 'Analyse starten'),
                    text('The requirement is ready for the primary action.',
                        'Die Anforderung ist bereit für die Hauptaktion.'), false);
            } else {
                setStage('describe', 'current');
                configure('describe', text('Enter requirement', 'Anforderung eingeben'),
                    text('Start with the requirement or capability to evaluate.',
                        'Beginnen Sie mit der zu bewertenden Anforderung oder Fähigkeit.'), false);
            }
        }

        nextAction.addEventListener('click', function () {
            if (action === 'describe') {
                businessText?.focus();
            } else if (action === 'analyze') {
                analyzeButton?.focus();
                analyzeButton?.click();
            } else if (action === 'review') {
                var firstScore = document.querySelector('#taxonomyTree .tax-pct');
                firstScore?.scrollIntoView({ behavior: 'smooth', block: 'center' });
                setStage('next', 'current');
                configure('architecture', text('Open architecture', 'Architektur öffnen'),
                    text('Continue with relations and architecture decisions.',
                        'Fahren Sie mit Beziehungen und Architekturentscheidungen fort.'), false);
            } else if (action === 'architecture') {
                document.querySelector('#mainNavTabs [data-page="architecture"]')?.click();
            }
        });

        businessText?.addEventListener('input', sync);
        analyzeButton?.addEventListener('click', function () {
            setStage('analyze', 'current');
            configure('none', text('Analysis running…', 'Analyse läuft…'),
                text('The result state and next action will appear here.',
                    'Ergebniszustand und nächste Aktion erscheinen hier.'), true);
            setTimeout(sync, 0);
        });
        if (statusArea) new MutationObserver(sync).observe(statusArea,
            { attributes: true, childList: true, subtree: true, characterData: true });
        if (taxonomyTree) new MutationObserver(sync).observe(taxonomyTree,
            { attributes: true, childList: true, subtree: true });
        sync();
    }

    function installKeyboardShortcuts() {
        document.addEventListener('keydown', function (event) {
            if (!event.altKey || !event.shiftKey) return;
            if (event.key.toLowerCase() === 'a') {
                event.preventDefault();
                document.querySelector('#mainNavTabs [data-page="analyze"]')?.click();
                document.getElementById('businessText')?.focus();
            } else if (event.key.toLowerCase() === 'o') {
                event.preventDefault();
                var details = document.getElementById('operationalContextDetails');
                if (details) {
                    details.open = !details.open;
                    details.querySelector('summary')?.focus();
                }
            }
        });
    }

    function initTaskFocus() {
        createOperationalContext();
        createTaskProgress();
        createSecondaryToolsDisclosure();
        installKeyboardShortcuts();
    }

    // Auto-init on DOMContentLoaded
    document.addEventListener('DOMContentLoaded', function () {
        init();
        initTaskFocus();
    });

    // Public API
    window.TaxonomyOnboarding = {
        init: init,
        dismiss: dismiss,
        reset: reset,
        initTaskFocus: initTaskFocus
    };
})();
