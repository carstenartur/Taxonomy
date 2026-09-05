/* Complete Copilot session preflight, task routing and ergonomic controls. */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C) throw new Error('Analysis session core must load before Copilot session UI');
    var runtime = C.runtime || (C.runtime = {});
    var INITIALIZATION_RETRY_LIMIT = 30;
    var initializationRetryCount = 0;
    var initializationRetryScheduled = false;
    var initializationAfterI18nScheduled = false;
    var taskObserverInstalled = false;
    var overlayObserverInstalled = false;
    var onboardingTaskProgressPatched = false;
    var previousCopilotBusy = false;
    var resultFreshnessObserverInstalled = false;
    var resultEvidence = new WeakMap();
    var taskRewriteFrame = null;
    var overlayRepositionFrame = null;

    function scoresPresent() {
        var scores = window._taxonomyCurrentScores;
        return Boolean(scores && typeof scores === 'object' && Object.keys(scores).length);
    }

    function copilotRunning() {
        var button = document.getElementById('copilotBtn');
        var spinner = document.getElementById('copilotSpinner');
        return Boolean(button && button.disabled && spinner
            && !spinner.classList.contains('d-none'));
    }

    function text(key, german, english) {
        var i18n = window.TaxonomyI18n;
        if (i18n && typeof i18n.t === 'function') {
            var translated = i18n.t(key);
            if (translated && translated !== key) return translated;
        }
        return C.language() === 'de' ? german : english;
    }

    function dispatchChange(element) {
        if (element && typeof element.dispatchEvent === 'function'
                && typeof Event === 'function') {
            element.dispatchEvent(new Event('change', { bubbles: true }));
        }
    }

    function prepareCompleteMode() {
        var interactive = document.getElementById('interactiveMode');
        if (interactive && interactive.checked) {
            interactive.checked = false;
            dispatchChange(interactive);
        }
        var architecture = document.getElementById('includeArchitectureView');
        if (architecture && !architecture.checked) {
            architecture.checked = true;
            dispatchChange(architecture);
        }
    }

    function discardStaleResults() {
        if (typeof C.isStale !== 'function' || !C.isStale()) return true;
        if (typeof C.invalidate !== 'function') return false;
        C.invalidate({ keepText: true, silent: true, reason: 'copilot-reanalysis' });
        return !scoresPresent();
    }

    function showUnavailable() {
        var panel = document.getElementById('copilotPanel');
        var content = document.getElementById('copilotContent');
        if (panel) panel.style.display = '';
        if (!content) return;
        var alert = document.createElement('div');
        alert.className = 'alert alert-warning py-1 px-2 small mb-0';
        alert.textContent = text('analysis.copilot.unavailable',
            'Der Copilot kann die veralteten Ergebnisse noch nicht sicher ersetzen.',
            'Copilot cannot safely replace the stale results yet.');
        content.replaceChildren(alert);
    }

    function installCopilotPreflight() {
        if (runtime.copilotSessionPreflightInstalled) return;
        runtime.copilotSessionPreflightInstalled = true;
        document.addEventListener('click', function (event) {
            var target = event.target && typeof event.target.closest === 'function'
                ? event.target.closest('#copilotBtn') : null;
            if (!target || runtime.draftDecisionPending || runtime.conflict
                    || runtime.invalidating) return;
            if (discardStaleResults()) {
                prepareCompleteMode();
                return;
            }
            event.preventDefault();
            event.stopImmediatePropagation();
            showUnavailable();
        }, true);
    }

    function loadStyles() {
        if (!document.head || document.querySelector(
                'link[data-taxonomy-analysis-workflow]')) return;
        var link = document.createElement('link');
        link.rel = 'stylesheet';
        link.dataset.taxonomyAnalysisWorkflow = 'true';
        var source = '/css/taxonomy-analysis-workflow.css';
        link.href = window.TaxonomyI18n
                && typeof window.TaxonomyI18n.resolveUrl === 'function'
            ? window.TaxonomyI18n.resolveUrl(source) : source;
        document.head.appendChild(link);
    }

    function syncCancelVisibility() {
        var cancel = document.getElementById('cancelAnalysisBtn');
        if (!cancel) return;
        var visible = !cancel.disabled && cancel.getAttribute('aria-disabled') !== 'true';
        cancel.classList.toggle('d-none', !visible);
        cancel.setAttribute('aria-hidden', visible ? 'false' : 'true');
        cancel.tabIndex = visible ? 0 : -1;
    }

    function enhanceControls() {
        var provider = document.getElementById('providerSelect');
        var analyze = document.getElementById('analyzeBtn');
        var copilot = document.getElementById('copilotBtn');
        var cancel = document.getElementById('cancelAnalysisBtn');
        if (!provider || !analyze || !copilot || !cancel) return false;
        var row = provider.parentElement;
        if (!row) return false;
        var interactive = document.getElementById('interactiveMode');
        var architecture = document.getElementById('includeArchitectureView');
        var groups = [interactive, architecture].map(function (control) {
            return control && control.closest('.form-check');
        }).filter(Boolean);
        row.classList.add('analysis-command-grid');
        row.setAttribute('role', 'group');
        row.setAttribute('aria-label', text('analysis.actions.label',
            'Analyseaktionen', 'Analysis actions'));
        provider.classList.add('analysis-provider-select');
        copilot.classList.add('analysis-primary-action');
        analyze.classList.add('analysis-secondary-action');
        cancel.classList.add('analysis-cancel-action');
        row.replaceChildren(provider, copilot, analyze, cancel);

        if (!copilot.querySelector('.analysis-action-hint')) {
            var label = Array.from(copilot.children).find(function (child) {
                return child.id !== 'copilotSpinner';
            });
            if (label) {
                var copy = document.createElement('span');
                copy.className = 'analysis-action-copy';
                label.replaceWith(copy);
                copy.appendChild(label);
                var hint = document.createElement('small');
                hint.className = 'analysis-action-hint';
                hint.textContent = text('analysis.copilot.primary.hint',
                    'Bewertung, Lücken, Muster und Empfehlung vollständig ausführen',
                    'Complete scoring, gaps, patterns and recommendation');
                copy.appendChild(hint);
            }
        }

        var options = document.getElementById('analysisModeOptions');
        if (!options && groups.length) {
            options = document.createElement('div');
            options.id = 'analysisModeOptions';
            options.className = 'analysis-mode-options';
            row.parentElement.insertBefore(options, row.nextSibling);
        }
        groups.forEach(function (group) {
            group.classList.remove('ms-2', 'mt-2');
            options.appendChild(group);
        });
        syncCancelVisibility();
        if (cancel.dataset.workflowVisibilityObserved !== 'true'
                && typeof MutationObserver === 'function') {
            cancel.dataset.workflowVisibilityObserved = 'true';
            new MutationObserver(syncCancelVisibility).observe(cancel, {
                attributes: true,
                attributeFilter: ['disabled', 'aria-disabled', 'class']
            });
        }
        return true;
    }

    function setRunningStage() {
        ['taskStageDescribe', 'taskStageAnalyze', 'taskStageReview', 'taskStageContinue']
            .forEach(function (id, index) {
                var item = document.getElementById(id);
                if (!item) return;
                item.removeAttribute('aria-current');
                item.dataset.state = index === 0 ? 'complete'
                    : index === 1 ? 'current' : 'upcoming';
                if (index === 1) item.setAttribute('aria-current', 'step');
            });
    }

    function rewriteTaskAction() {
        var next = document.getElementById('taskNextAction');
        if (!next) return;
        var busy = copilotRunning();
        if (busy) {
            setRunningStage();
            if (!next.disabled) next.disabled = true;
            if (next.dataset.action !== 'running') next.dataset.action = 'running';
            var runningText = text('analysis.task.next.copilotRunning',
                'Copilot läuft…', 'Copilot running…');
            if (next.textContent !== runningText) next.textContent = runningText;
        } else {
            if (previousCopilotBusy && window.TaxonomyOnboarding
                    && typeof window.TaxonomyOnboarding.syncTaskProgress === 'function') {
                window.TaxonomyOnboarding.syncTaskProgress();
            }
            if (next.dataset.action === 'analyze') {
                next.dataset.action = 'copilot';
                var input = document.getElementById('businessText');
                var status = document.getElementById('statusArea');
                var stale = Boolean(input && input.classList.contains('stale-results'));
                var error = /error|failed|unavailable|503|fehler|fehlgeschlagen|nicht verfügbar/i
                    .test(status ? status.textContent || '' : '');
                next.textContent = stale
                    ? text('analysis.task.next.update',
                        'Änderung mit Copilot neu verarbeiten',
                        'Run Copilot again for the change')
                    : error
                        ? text('analysis.task.next.retry',
                            'Fehler prüfen und Copilot erneut starten',
                            'Review the error and retry Copilot')
                        : text('analysis.task.next.run',
                            'Copilot starten', 'Run Copilot');
            }
        }
        previousCopilotBusy = busy;
    }

    function scheduleTaskRewrite() {
        if (taskRewriteFrame !== null) return;
        taskRewriteFrame = window.requestAnimationFrame(function () {
            taskRewriteFrame = null;
            rewriteTaskAction();
        });
    }

    function patchOnboardingTaskProgress() {
        var onboarding = window.TaxonomyOnboarding;
        if (!onboarding || typeof onboarding.syncTaskProgress !== 'function') {
            return false;
        }
        var current = onboarding.syncTaskProgress;
        if (current.__taxonomyCopilotTaskRoutingPatched === true) {
            onboardingTaskProgressPatched = true;
            return true;
        }
        function syncTaskProgressWithCopilotRouting() {
            var result = current.apply(this, arguments);
            scheduleTaskRewrite();
            return result;
        }
        syncTaskProgressWithCopilotRouting.__taxonomyCopilotTaskRoutingPatched = true;
        onboarding.syncTaskProgress = syncTaskProgressWithCopilotRouting;
        onboardingTaskProgressPatched = true;
        return true;
    }

    function installTaskRouting() {
        if (taskObserverInstalled) return;
        var next = document.getElementById('taskNextAction');
        var copilot = document.getElementById('copilotBtn');
        var spinner = document.getElementById('copilotSpinner');
        if (!next || !copilot || !spinner) return;
        taskObserverInstalled = true;
        document.addEventListener('click', function (event) {
            var target = event.target && typeof event.target.closest === 'function'
                ? event.target.closest(
                    '#taskNextAction[data-action="copilot"], '
                    + '#taskNextAction[data-action="analyze"]') : null;
            if (!target || target.disabled) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            copilot.click();
        }, true);
        if (typeof MutationObserver === 'function') {
            var observer = new MutationObserver(scheduleTaskRewrite);
            observer.observe(copilot, { attributes: true, attributeFilter: ['disabled', 'aria-busy'] });
            observer.observe(spinner, { attributes: true, attributeFilter: ['class'] });
            var status = document.getElementById('statusArea');
            if (status) observer.observe(status, { childList: true, subtree: true, characterData: true });
        }
        var input = document.getElementById('businessText');
        if (input) input.addEventListener('input', scheduleTaskRewrite);
        rewriteTaskAction();
    }

    function visible(element) {
        if (!element || !element.isConnected || element.hidden) return false;
        var style = getComputedStyle(element);
        return style.display !== 'none' && style.visibility !== 'hidden'
            && element.getClientRects().length > 0;
    }

    function overlap(left, right, margin) {
        var spacing = margin || 0;
        return left.left < right.right + spacing && left.right > right.left - spacing
            && left.top < right.bottom + spacing && left.bottom > right.top - spacing;
    }

    function unobscured(element) {
        if (!visible(element)) return false;
        var rect = element.getBoundingClientRect();
        var inset = Math.min(6, rect.width / 4, rect.height / 4);
        return [
            [rect.left + rect.width / 2, rect.top + rect.height / 2],
            [rect.left + inset, rect.top + inset],
            [rect.right - inset, rect.top + inset],
            [rect.left + inset, rect.bottom - inset],
            [rect.right - inset, rect.bottom - inset]
        ].every(function (point) {
            if (point[0] < 0 || point[0] >= window.innerWidth
                    || point[1] < 0 || point[1] >= window.innerHeight) return false;
            var top = document.elementFromPoint(point[0], point[1]);
            return Boolean(top && (top === element || element.contains(top)));
        });
    }

    function repositionOverlay() {
        var lane = document.getElementById('taxonomyOverlayLane');
        if (!lane || !Array.from(lane.children).some(visible)) return;
        var targets = [document.activeElement, document.getElementById('copilotBtn'),
            document.getElementById('analyzeBtn'), document.getElementById('cancelAnalysisBtn'),
            document.getElementById('taskNextAction')].filter(function (target) {
            return typeof HTMLElement !== 'undefined' && target instanceof HTMLElement
                && target !== document.body && target !== document.documentElement
                && !lane.contains(target) && visible(target);
        });
        var positions = ['bottom-end', 'bottom-start', 'top-end', 'top-start'];
        for (var index = 0; index < positions.length; index++) {
            lane.dataset.position = positions[index];
            var laneRect = lane.getBoundingClientRect();
            if (!targets.some(function (target) {
                return overlap(laneRect, target.getBoundingClientRect(), 8);
            })) break;
        }
    }

    function scheduleOverlayReposition() {
        if (overlayRepositionFrame !== null) return;
        overlayRepositionFrame = window.requestAnimationFrame(function () {
            overlayRepositionFrame = null;
            repositionOverlay();
        });
    }

    function installOverlayHardening() {
        if (overlayObserverInstalled) return;
        var lane = document.getElementById('taxonomyOverlayLane');
        if (!lane) return;
        overlayObserverInstalled = true;
        if (window.TaxonomyOnboarding) {
            window.TaxonomyOnboarding.isElementUnobscured = unobscured;
        }
        if (typeof MutationObserver === 'function') {
            new MutationObserver(scheduleOverlayReposition)
                .observe(lane, { childList: true, subtree: true });
        }
        document.addEventListener('focusin', scheduleOverlayReposition);
        window.addEventListener('resize', scheduleOverlayReposition);
        window.addEventListener('scroll', scheduleOverlayReposition, { passive: true });
        scheduleOverlayReposition();
    }

    function syncCopilotResultFreshness() {
        var content = document.getElementById('copilotContent');
        var input = document.getElementById('businessText');
        var header = content && content.firstElementChild;
        var title = header && header.querySelector('strong');
        if (!header || !title) return;

        var evidence = resultEvidence.get(header);
        if (!evidence) {
            // Only the completed legacy summary has a direct success alert with a title.
            // Running/failed operation cards and their actions must remain untouched.
            if (!header.classList.contains('alert-success')) return;
            evidence = {
                sourceText: C.S && typeof C.S.lastAnalyzedText === 'string'
                    ? C.S.lastAnalyzedText : null,
                title: title.textContent
            };
            resultEvidence.set(header, evidence);
        }
        var stale = evidence.sourceText === null || !input
            || input.value !== evidence.sourceText
            || (typeof C.isStale === 'function' && C.isStale());
        var state = stale ? 'stale' : 'current';
        var message = stale ? text('browse.stale.warning',
            'Der Anforderungstext wurde geändert — die bisherigen Ergebnisse sind nicht mehr gültig.',
            'The requirement text has changed — the previous results are no longer valid.')
            : evidence.title;
        if (title.textContent !== message) title.textContent = message;
        if (header.classList.contains('alert-success') === stale) {
            header.classList.toggle('alert-success', !stale);
        }
        if (header.classList.contains('alert-warning') !== stale) {
            header.classList.toggle('alert-warning', stale);
        }
        if (header.dataset.copilotResultState !== state) {
            header.dataset.copilotResultState = state;
        }
        header.setAttribute('role', 'status');
        header.setAttribute('aria-live', 'polite');
    }

    function installResultFreshness() {
        if (resultFreshnessObserverInstalled) return;
        var input = document.getElementById('businessText');
        var content = document.getElementById('copilotContent');
        if (!input || !content) return;
        resultFreshnessObserverInstalled = true;
        input.addEventListener('input', syncCopilotResultFreshness);
        input.addEventListener('change', syncCopilotResultFreshness);
        if (typeof MutationObserver === 'function') {
            var observer = new MutationObserver(syncCopilotResultFreshness);
            observer.observe(input, { attributes: true, attributeFilter: ['class'] });
            observer.observe(content, { childList: true, subtree: true });
        }
        ['taxonomy:analysis-draft-restored', 'taxonomy:analysis-invalidated',
            'taxonomy:view-rendered'].forEach(function (eventName) {
            document.addEventListener(eventName, syncCopilotResultFreshness);
        });
        syncCopilotResultFreshness();
    }

    function analysisSurfacePresent() {
        return Boolean(document.getElementById('tab-analyze')
            || document.getElementById('businessText')
            || document.getElementById('copilotBtn'));
    }

    function initializationComplete() {
        return taskObserverInstalled && overlayObserverInstalled
            && onboardingTaskProgressPatched;
    }

    function scheduleInitializationAfterI18n() {
        if (initializationComplete() || !analysisSurfacePresent()
                || initializationAfterI18nScheduled) {
            return;
        }
        var i18n = window.TaxonomyI18n;
        if (!i18n || typeof i18n.ready !== 'function') return;
        initializationAfterI18nScheduled = true;
        i18n.ready().then(function () {
            if (typeof window.requestAnimationFrame === 'function') {
                window.requestAnimationFrame(initializeUi);
            } else {
                initializeUi();
            }
        });
    }

    function scheduleInitializationRetry() {
        if (initializationComplete() || !analysisSurfacePresent()
                || initializationRetryScheduled) {
            return;
        }
        if (initializationRetryCount >= INITIALIZATION_RETRY_LIMIT) {
            scheduleInitializationAfterI18n();
            return;
        }
        if (typeof window.setTimeout !== 'function') return;
        initializationRetryScheduled = true;
        initializationRetryCount += 1;
        window.setTimeout(function () {
            initializationRetryScheduled = false;
            initializeUi();
        }, 100);
    }

    function initializeUi() {
        loadStyles();
        enhanceControls();
        patchOnboardingTaskProgress();
        installTaskRouting();
        installOverlayHardening();
        installResultFreshness();
        scheduleInitializationRetry();
    }

    installCopilotPreflight();
    Object.assign(C, {
        prepareCompleteCopilotMode: prepareCompleteMode,
        discardStaleCopilotResults: discardStaleResults
    });
    if (!document.readyState || document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initializeUi, { once: true });
        if (typeof window.addEventListener === 'function') {
            window.addEventListener('load', initializeUi, { once: true });
        }
    } else {
        initializeUi();
        if (document.readyState !== 'complete'
                && typeof window.addEventListener === 'function') {
            window.addEventListener('load', initializeUi, { once: true });
        }
    }
}());