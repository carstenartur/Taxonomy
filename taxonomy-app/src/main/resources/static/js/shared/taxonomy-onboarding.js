/* taxonomy-onboarding.js – Welcome overlay, task hierarchy and progressive disclosure */

(function () {
    'use strict';
    var t = TaxonomyI18n.t;

    var STORAGE_KEY = 'taxonomy_onboarded';
    var reviewAcknowledged = false;
    var TASK_STAGES = [
        {
            id: 'taskStageDescribe',
            labelKey: 'analysis.task.stage.describe.label',
            descriptionKey: 'analysis.task.stage.describe.description'
        },
        {
            id: 'taskStageAnalyze',
            labelKey: 'analysis.task.stage.analyze.label',
            descriptionKey: 'analysis.task.stage.analyze.description'
        },
        {
            id: 'taskStageReview',
            labelKey: 'analysis.task.stage.review.label',
            descriptionKey: 'analysis.task.stage.review.description'
        },
        {
            id: 'taskStageContinue',
            labelKey: 'analysis.task.stage.continue.label',
            descriptionKey: 'analysis.task.stage.continue.description'
        }
    ];

    function initWelcomeOverlay() {
        if (localStorage.getItem(STORAGE_KEY)) {
            return;
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
        overlay.addEventListener('click', function (event) {
            if (event.target === overlay) {
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

    function reset() {
        localStorage.removeItem(STORAGE_KEY);
    }

    function createTaskProgress() {
        var analyzePane = document.getElementById('tab-analyze');
        var firstCard = analyzePane && analyzePane.querySelector('.card');
        if (!analyzePane || !firstCard || document.getElementById('analysisTaskProgress')) {
            return;
        }

        var progress = document.createElement('section');
        progress.id = 'analysisTaskProgress';
        progress.className = 'analysis-task-progress card shadow-sm mb-3';
        progress.setAttribute('aria-label', t('analysis.task.progress.label'));

        var list = document.createElement('ol');
        list.className = 'analysis-task-stages';
        TASK_STAGES.forEach(function (stage, index) {
            var item = document.createElement('li');
            item.id = stage.id;
            item.className = 'analysis-task-stage';
            item.dataset.state = index === 0 ? 'current' : 'upcoming';
            if (index === 0) {
                item.setAttribute('aria-current', 'step');
            }

            var number = document.createElement('span');
            number.className = 'analysis-task-number';
            number.textContent = String(index + 1);
            number.setAttribute('aria-hidden', 'true');

            var copy = document.createElement('span');
            copy.className = 'analysis-task-copy';
            var label = document.createElement('strong');
            label.textContent = t(stage.labelKey);
            var description = document.createElement('small');
            description.textContent = t(stage.descriptionKey);
            copy.append(label, description);
            item.append(number, copy);
            list.appendChild(item);
        });

        var next = document.createElement('div');
        next.className = 'analysis-next-action';
        var nextAction = document.createElement('button');
        nextAction.id = 'taskNextAction';
        nextAction.type = 'button';
        nextAction.className = 'btn btn-sm btn-outline-primary';
        nextAction.disabled = true;
        nextAction.textContent = t('analysis.task.next.enter');
        nextAction.addEventListener('click', performNextAction);
        next.appendChild(nextAction);

        progress.append(list, next);
        firstCard.parentElement.insertBefore(progress, firstCard);
    }

    function createOperationalContext() {
        var navigation = document.getElementById('mainNavTabs');
        if (!navigation || document.getElementById('operationalContextDetails')) {
            return;
        }

        var details = document.createElement('details');
        details.id = 'operationalContextDetails';
        details.className = 'operational-context-details';
        var summary = document.createElement('summary');
        var title = document.createElement('strong');
        title.className = 'operational-context-title';
        title.textContent = t('analysis.task.operational.summary');
        var hint = document.createElement('span');
        hint.className = 'operational-context-hint';
        hint.textContent = t('analysis.task.operational.hint');
        summary.append(title, hint);
        var body = document.createElement('div');
        body.className = 'operational-context-content';
        details.append(summary, body);

        var navigationShell = navigation.closest('.bg-dark');
        navigationShell.parentElement.insertBefore(details, navigationShell.nextSibling);

        [
            document.getElementById('aiStatusBadge'),
            document.getElementById('embeddingStatusBadge'),
            document.getElementById('workspaceUserBadge'),
            document.getElementById('gitStatusBar'),
            document.getElementById('contextBar')
        ].filter(Boolean).forEach(function (surface) {
            body.appendChild(surface);
        });

        var languageSelector = document.getElementById('langSelector');
        if (languageSelector) {
            languageSelector.classList.add('ms-auto');
        }
        monitorOperationalState(details, body);
    }

    function monitorOperationalState(details, body) {
        function refresh() {
            var text = body.textContent || '';
            // Optional capabilities can be unavailable by configuration. Keep that neutral state
            // collapsed; explicit warnings, conflicts, failures and danger styling remain actionable.
            var actionable = /error|failed|conflict|warning|degraded|fehler|fehlgeschlagen|konflikt|warnung|beeinträchtigt/i.test(text);
            var danger = Boolean(body.querySelector(
                '.bg-danger, .text-danger, .alert-danger, .git-status-error, [data-state="error"]'));
            details.classList.toggle('has-actionable-status', actionable || danger);
            if (actionable || danger) {
                details.open = true;
            }
        }
        new MutationObserver(refresh).observe(body, {
            attributes: true,
            attributeFilter: ['class', 'style'],
            childList: true,
            subtree: true,
            characterData: true
        });
        refresh();
    }

    function createSecondaryToolsDisclosure() {
        var analyzePane = document.getElementById('tab-analyze');
        if (!analyzePane || document.getElementById('analysisSecondaryTools')) {
            return;
        }

        var details = document.createElement('details');
        details.id = 'analysisSecondaryTools';
        details.className = 'analysis-secondary-tools mt-3';
        var summary = document.createElement('summary');
        summary.className = 'fw-semibold';
        summary.textContent = t('analysis.task.secondary.summary');
        var body = document.createElement('div');
        body.className = 'analysis-secondary-tools-content';
        details.append(summary, body);

        [
            'searchPanel',
            'analysisLog',
            'llmCommLog',
            'gapAnalysisPanel',
            'patternDetectionPanel',
            'recommendationPanel',
            'documentImportPanel',
            'provenancePanel'
        ].forEach(function (id) {
            var tool = document.getElementById(id);
            if (tool) {
                body.appendChild(tool);
            }
        });
        analyzePane.appendChild(details);

        var legend = Array.from(analyzePane.querySelectorAll('.card')).find(function (card) {
            return card.querySelector('.legend-box');
        });
        if (legend) {
            var legendDetails = document.createElement('details');
            legendDetails.id = 'analysisScoreLegend';
            legendDetails.className = 'score-legend-details mb-3';
            var legendSummary = document.createElement('summary');
            legendSummary.textContent = t('analysis.task.legend.summary');
            legend.parentElement.insertBefore(legendDetails, legend);
            legendDetails.append(legendSummary, legend);
        }
    }

    function setTaskState(activeIndex, state) {
        TASK_STAGES.forEach(function (stage, index) {
            var item = document.getElementById(stage.id);
            if (!item) {
                return;
            }
            item.removeAttribute('aria-current');
            if (index < activeIndex) {
                item.dataset.state = 'complete';
            } else if (index === activeIndex) {
                item.dataset.state = state || 'current';
                item.setAttribute('aria-current', 'step');
            } else {
                item.dataset.state = 'upcoming';
            }
        });
    }

    function syncTaskProgress() {
        var input = document.getElementById('businessText');
        var analyzeButton = document.getElementById('analyzeBtn');
        var spinner = document.getElementById('analyzeSpinner');
        var status = document.getElementById('statusArea');
        var nextAction = document.getElementById('taskNextAction');
        if (!input || !analyzeButton || !nextAction) {
            return;
        }

        var text = input.value.trim();
        var scores = window.TaxonomyState && window.TaxonomyState.currentScores;
        var hasScores = scores && Object.keys(scores).length > 0;
        var stale = input.classList.contains('stale-results');
        var running = analyzeButton.disabled || (spinner && !spinner.classList.contains('d-none'));
        var statusText = status ? status.textContent || '' : '';
        var error = /error|failed|unavailable|503|fehler|fehlgeschlagen|nicht verfügbar/i.test(statusText);

        nextAction.className = 'btn btn-sm btn-outline-primary';
        nextAction.disabled = false;
        if (!text) {
            reviewAcknowledged = false;
            setTaskState(0, 'current');
            nextAction.textContent = t('analysis.task.next.enter');
            nextAction.disabled = true;
            nextAction.dataset.action = 'focus-input';
        } else if (running) {
            reviewAcknowledged = false;
            setTaskState(1, 'current');
            nextAction.textContent = t('analysis.task.next.running');
            nextAction.disabled = true;
            nextAction.dataset.action = 'running';
        } else if (error) {
            reviewAcknowledged = false;
            setTaskState(1, 'error');
            nextAction.textContent = t('analysis.task.next.retry');
            nextAction.className = 'btn btn-sm btn-danger';
            nextAction.dataset.action = 'analyze';
        } else if (stale) {
            reviewAcknowledged = false;
            setTaskState(1, 'current');
            nextAction.textContent = t('analysis.task.next.update');
            nextAction.dataset.action = 'analyze';
        } else if (hasScores && reviewAcknowledged) {
            setTaskState(3, 'current');
            nextAction.textContent = t('analysis.task.next.continue');
            nextAction.dataset.action = 'open-architecture';
        } else if (hasScores) {
            setTaskState(2, 'current');
            nextAction.textContent = t('analysis.task.next.results');
            nextAction.dataset.action = 'review-results';
        } else {
            reviewAcknowledged = false;
            setTaskState(1, 'current');
            nextAction.textContent = t('analysis.task.next.run');
            nextAction.dataset.action = 'analyze';
        }
    }

    function performNextAction() {
        var button = document.getElementById('taskNextAction');
        var action = button && button.dataset.action;
        if (action === 'analyze') {
            document.getElementById('analyzeBtn')?.click();
        } else if (action === 'review-results') {
            var firstMatch = document.querySelector('.tax-node .tax-pct');
            if (firstMatch) {
                firstMatch.closest('.tax-node')?.scrollIntoView({ block: 'center' });
                firstMatch.closest('.tax-node')?.focus({ preventScroll: true });
                reviewAcknowledged = true;
                syncTaskProgress();
            }
        } else if (action === 'open-architecture') {
            if (typeof window.navigateToPage === 'function') {
                window.navigateToPage('architecture');
            } else {
                document.querySelector('#mainNavTabs [data-page="architecture"]')?.click();
            }
        } else {
            document.getElementById('businessText')?.focus();
        }
    }

    function monitorTaskProgress() {
        var input = document.getElementById('businessText');
        var analyzeButton = document.getElementById('analyzeBtn');
        var status = document.getElementById('statusArea');
        var tree = document.getElementById('taxonomyTree');
        if (input) {
            input.addEventListener('input', function () {
                reviewAcknowledged = false;
                syncTaskProgress();
            });
        }
        if (analyzeButton) {
            analyzeButton.addEventListener('click', function () {
                reviewAcknowledged = false;
                setTaskState(1, 'current');
                requestAnimationFrame(syncTaskProgress);
            });
        }
        [status, tree, analyzeButton].filter(Boolean).forEach(function (surface) {
            new MutationObserver(syncTaskProgress).observe(surface, {
                attributes: true,
                childList: true,
                subtree: true,
                characterData: true
            });
        });
        syncTaskProgress();
    }

    function installExpertShortcuts() {
        var analyzeLink = document.querySelector('#mainNavTabs [data-page="analyze"]');
        var operationalSummary = document.querySelector('#operationalContextDetails > summary');
        if (analyzeLink) {
            analyzeLink.title = t('analysis.task.shortcut.analyze');
        }
        if (operationalSummary) {
            operationalSummary.title = t('analysis.task.shortcut.operational');
        }
        document.addEventListener('keydown', function (event) {
            if (!event.altKey || !event.shiftKey) {
                return;
            }
            if (event.key.toLowerCase() === 'a') {
                event.preventDefault();
                analyzeLink?.click();
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

    function initTaskHierarchy() {
        createTaskProgress();
        createOperationalContext();
        createSecondaryToolsDisclosure();
        monitorTaskProgress();
        installExpertShortcuts();
    }

    function init() {
        initWelcomeOverlay();
        initTaskHierarchy();
    }

    function scheduleInit() {
        TaxonomyI18n.ready().then(init);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', scheduleInit);
    } else {
        scheduleInit();
    }

    window.TaxonomyOnboarding = {
        init: scheduleInit,
        dismiss: dismiss,
        reset: reset,
        syncTaskProgress: syncTaskProgress
    };
})();
