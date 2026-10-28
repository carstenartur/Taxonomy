/* Navigation and next steps over retained evidence; never starts synthesis or adopts changes. */
(function () {
    'use strict';
    var root = document.getElementById('analysisNextSteps');
    if (!root) return;
    var S = window.TaxonomyState;
    var parameters = new URLSearchParams(window.location.search);
    var busy = false;
    function el(id) { return document.getElementById(id); }
    function t(key) { return window.TaxonomyI18n.t('analysis.guide.' + key); }
    function url(path) { return window.TaxonomyI18n.resolveUrl?.(path) || path; }
    function scoped(path) {
        var query = new URLSearchParams();
        ['repositoryId', 'workspaceScopeKey', 'branch'].forEach(function (key) {
            if (parameters.has(key)) query.set(key, parameters.get(key));
        });
        var session = window.TaxonomyAnalysisSession?.state?.();
        if (session?.ready && Object.prototype.hasOwnProperty.call(session, 'workspaceId')) {
            query.set('workspaceScopeKey', session.workspaceId || '__shared__');
        }
        return url(path) + (query.size ? '?' + query : '');
    }
    function requirementUrl() {
        var project = parameters.get('projectId'), requirement = parameters.get('requirementId');
        if (!/^\d+$/.test(project || '') || !/^\d+$/.test(requirement || '')) return null;
        var target = scoped('/projects/' + project + '/requirements/' + requirement);
        if (parameters.has('snapshot')) target += (target.includes('?') ? '&' : '?')
            + 'snapshot=' + encodeURIComponent(parameters.get('snapshot'));
        return target + '#reformulationOffers';
    }
    function condition() {
        var text = el('businessText')?.value || '';
        var recovery = S.analysisRecovery;
        var session = window.TaxonomyAnalysisSession?.state?.();
        var context = window.__TaxonomyAnalysisSessionContext;
        var meaningful = context?.meaningful && context?.currentPayload
            ? context.meaningful(context.currentPayload())
            : Boolean(text.trim() || S.currentScores || S.currentArchView || S.currentRelationSearchReport);
        var running = ['IN_PROGRESS', 'RUNNING', 'QUEUED'].includes(S.lastAnalysisStatus)
            || Boolean(window.__TaxonomyAnalysisSessionContext?.analysisRunning?.());
        return {running: running, text: text, ready: Boolean(session?.ready),
            shared: session?.workspaceId === null,
            unpersisted: session?.workspaceId === null && meaningful,
            stale: S.lastAnalyzedText != null && text !== S.lastAnalyzedText,
            partial: ['PARTIAL', 'ERROR', 'CANCELLED'].includes(S.lastAnalysisStatus)
                || recovery?.state === 'COMPLETED_WITH_GAPS' || (recovery?.openQuestions?.length || 0) > 0,
            recovery: Boolean(recovery && window.TaxonomyAnalysisRecovery?.open)};
    }
    function setText(node, value) { if (node.textContent !== value) node.textContent = value; }
    function render() {
        var c = condition();
        var key = c.running ? 'running' : c.unpersisted ? 'workspace' : c.stale ? 'stale' : !c.text.trim() ? 'empty'
            : !c.ready ? 'unavailable' : c.partial ? 'partial' : S.lastAnalysisStatus ? 'complete' : 'empty';
        setText(el('analysisNextStepText'), t(key));
        var saved = requirementUrl();
        setText(el('analysisReformulationHint'), t(saved ? 'saved' : 'unsaved'));
        ['analysisReformulationAction', 'projectReformulationAction'].forEach(function (id) {
            var control = el(id); if (!control) return;
            control.href = saved || '#analysisNextSteps';
            control.setAttribute('aria-disabled', String(busy || c.running || c.stale || c.unpersisted || !c.ready || !c.text.trim()));
            control.title = t(c.running ? 'running' : c.unpersisted ? 'workspace' : c.stale ? 'stale' : !c.ready ? 'unavailable' : saved ? 'saved' : 'unsaved');
        });
        ['analysisLensAction', 'architectureLensAction'].forEach(function (id) {
            var control = el(id); if (!control) return;
            control.href = scoped('/architecture/editor') + '#editorLensTitle';
            control.setAttribute('aria-disabled', String(busy || c.running || c.unpersisted || !c.ready));
            control.title = t(c.unpersisted ? 'workspace' : 'lens.hint');
        });
        el('analysisClarifyAction').hidden = !c.partial || c.running || c.stale;
        el('analysisClarifyAction').disabled = busy || !c.ready;
        el('analysisReanalyzeAction').hidden = !c.stale || c.running;
        el('analysisReanalyzeAction').disabled = busy || !c.ready || !c.text.trim();
    }
    async function navigate(event, target) {
        // Modified link clicks retain normal browser behavior; they do not discard this page.
        if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        if (busy) return;
        busy = true; render();
        el('analysisGuideError').hidden = true;
        try {
            var c = condition();
            if (c.unpersisted || (!c.shared && await window.TaxonomyAnalysisSession.saveNow() !== true)) {
                throw new Error(t(c.unpersisted ? 'workspace' : 'save.failed'));
            }
            window.location.assign(target);
        } catch (failure) {
            el('analysisGuideError').textContent = failure.message || t('save.failed');
            el('analysisGuideError').hidden = false;
        } finally { busy = false; render(); }
    }
    ['analysisReformulationAction', 'projectReformulationAction'].forEach(function (id) {
        el(id)?.addEventListener('click', function (event) {
            render();
            if (this.getAttribute('aria-disabled') === 'true') { event.preventDefault(); return; }
            var target = requirementUrl();
            if (target) { navigate(event, target); return; }
            event.preventDefault();
            window.TaxonomyAnalysisSession.addAsRequirement({preserveDraft: true});
        });
    });
    ['analysisLensAction', 'architectureLensAction'].forEach(function (id) {
        el(id)?.addEventListener('click', function (event) {
            render();
            if (this.getAttribute('aria-disabled') === 'true') { event.preventDefault(); return; }
            navigate(event, this.href);
        });
    });
    el('analysisClarifyAction').addEventListener('click', function () {
        var c = condition(); if (busy || !c.ready || c.running || c.stale) return;
        if (c.recovery) { window.TaxonomyAnalysisRecovery.open(); return; }
        setText(el('analysisNextStepText'), t('report'));
        var report = el('relationSearchReportPanel');
        var target = report && !report.hidden ? report : el('statusArea');
        target?.scrollIntoView({block: 'nearest'});
        if (target) { target.tabIndex = -1; target.focus({preventScroll: true}); }
    });
    el('analysisReanalyzeAction').addEventListener('click', function () {
        var c = condition(); if (busy || !c.ready || c.running || !c.text.trim()) return;
        el('analyzeBtn')?.click();
    });
    el('architectureNextStepsAction')?.addEventListener('click', function (event) {
        event.preventDefault(); window.navigateToPage?.('analyze');
        root.scrollIntoView({block: 'nearest'});
        el('analysisNextStepsHeading').tabIndex = -1; el('analysisNextStepsHeading').focus({preventScroll: true});
    });
    ['taxonomy:view-rendered', 'taxonomy:analysis-progress', 'taxonomy:analysis-running-changed',
        'taxonomy:analysis-invalidated', 'taxonomy:analysis-cancelled', 'taxonomy:analysis-draft-restored', 'taxonomy:page-activated'].forEach(function (name) {
        document.addEventListener(name, render);
    });
    el('businessText')?.addEventListener('input', render);
    var status = el('statusArea');
    if (status) new MutationObserver(render).observe(status, {childList:true, subtree:true});
    function watchSessionReady() {
        if (window.TaxonomyAnalysisSessionReady) window.TaxonomyAnalysisSessionReady.then(render);
        else document.querySelector('script[data-taxonomy-analysis-session]')?.addEventListener('load', watchSessionReady, {once:true});
    }
    function ready() { render(); watchSessionReady(); }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready, {once:true});
    else ready();
    window.TaxonomyI18n.ready?.().then(render);
}());
