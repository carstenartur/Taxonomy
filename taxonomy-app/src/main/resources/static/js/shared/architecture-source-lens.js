/* A read-only lens on the displayed durable evidence. No editor revision or command authority. */
window.TaxonomyArchitectureSourceLens = (function () {
    'use strict';
    var scopeKeys = ['repositoryId', 'workspaceScopeKey', 'branch'];
    var snapshotKeys = ['projectId', 'requirementId', 'requirementVersionId', 'snapshotId'];
    function sourceKey(value) {
        return value ? JSON.stringify([value.kind].concat(scopeKeys.map(function (key) { return value[key]; }),
            snapshotKeys.map(function (key) { return value[key]; }))) : '';
    }
    function problem(code) { return Object.assign(new Error(code), { responseBody: { code: code } }); }
    function create(options) {
        var root = options.root, workspace = options.workspace, prefix = options.idPrefix || 'architectureSourceLens';
        var current = null, pinned = null, generation = 0, selected = null, active = false, pending = null, attachedDiagram = null;
        var evidence = null, adopting = false;
        var t = options.t || function () { return window.TaxonomyI18n.t.apply(null, arguments); };
        function el(suffix) { return root.querySelector('#' + prefix + suffix); }
        function problemMessage(error) {
            var body = error.responseBody || {}, key = 'sourceLens.problem.' + (body.code || 'SOURCE_UNAVAILABLE');
            var translated = t(key);
            return translated !== key ? translated : body.detail || error.message;
        }
        function report(error) {
            el('Error').textContent = problemMessage(error);
            el('Error').hidden = false;
        }
        function diagram() { return workspace.getDiagram && workspace.getDiagram(); }
        function captureEvidence() {
            var data = workspace.getData && workspace.getData();
            var runtime = current?.kind === 'analysis' ? window.__TaxonomyAnalysisSessionContext?.runtime : null;
            // Presentation fence only: these values never authorize or enter an API request.
            return { data: data, serialized: JSON.stringify(data), analysisGeneration: runtime?.analysisGeneration,
                reference: JSON.stringify(runtime?.analysisReference || null) };
        }
        function sameEvidence(captured) {
            if (!captured) return false;
            try {
                var now = captureEvidence();
                return now.data === captured.data && now.serialized === captured.serialized
                    && now.analysisGeneration === captured.analysisGeneration && now.reference === captured.reference;
            } catch (_) { return false; }
        }
        function pinIdentity(value) { return value && sourceKey(value) + '\u001f' + value.version; }
        function isCurrent(value) {
            if (!active || !pinned || pinIdentity(value) !== pinIdentity(pinned)
                    || sourceKey(workspace.getSource()) !== sourceKey(current) || !sameEvidence(evidence)) return false;
            if (pinned.kind !== 'ANALYSIS_DRAFT') return true;
            var saved = window.TaxonomyAnalysisSession?.state?.();
            return Boolean(saved?.ready && !saved.conflict && !saved.restoring
                && saved.workspaceId === pinned.workspaceScopeKey && saved.version === pinned.version);
        }
        function invalidate() {
            generation++; pinned = null; evidence = null; pending = null;
            panel.setSource(null, false); panel.setLocked(false); report(problem('CONTEXT_CHANGED'));
        }
        function requireCurrent(value, ticket) {
            if (ticket !== generation || !active) throw problem('CONTEXT_CHANGED');
            if (!isCurrent(value)) { invalidate(); throw problem('CONTEXT_CHANGED'); }
        }
        function canReview(item) {
            if (!item || item.stale || !pinned || pinIdentity(item.source) !== pinIdentity(pinned)) return false;
            if (!isCurrent(item.source)) { invalidate(); return false; }
            return true;
        }
        function readyForInteraction() {
            if (!pinned) return false;
            if (!isCurrent(pinned)) { invalidate(); return false; }
            return true;
        }
        function observe(ids) { if (readyForInteraction()) panel.observe(ids); }
        function review(id) { if (readyForInteraction()) panel.review(id); }
        function present(state) {
            if (pinned && !isCurrent(pinned)) { invalidate(); return; }
            var target = active && pinned ? diagram() : attachedDiagram;
            if (active && pinned && target !== attachedDiagram) {
                if (attachedDiagram && attachedDiagram.setLensCallbacks) attachedDiagram.setLensCallbacks(null, null);
                attachedDiagram = target;
                if (target && target.setLensCallbacks) target.setLensCallbacks(observe, review);
            }
            if (target && target.presentFocus) target.presentFocus(active && pinned ? state : { enabled: false, states: {}, suggestions: [] });
            if ((!active || !pinned) && attachedDiagram) {
                if (attachedDiagram.setLensCallbacks) attachedDiagram.setLensCallbacks(null, null);
                attachedDiagram = null;
            }
        }
        function pins(source) {
            if (!source || !['analysis', 'snapshot'].includes(source.kind)) throw problem('SOURCE_UNAVAILABLE');
            var result = { kind: source.kind === 'analysis' ? 'ANALYSIS_DRAFT' : 'ANALYSIS_SNAPSHOT' };
            scopeKeys.forEach(function (key) {
                if (typeof source[key] !== 'string' || !source[key]) throw problem('SOURCE_UNAVAILABLE');
                result[key] = source[key];
            });
            if (source.kind === 'snapshot') {
                snapshotKeys.forEach(function (key) {
                    var value = source[key];
                    if (key !== 'snapshotId') value = Number(value);
                    if (key === 'snapshotId' ? typeof value !== 'string' || !value : !Number.isSafeInteger(value) || value < 1)
                        throw problem('SOURCE_UNAVAILABLE');
                    result[key] = value;
                });
            }
            return result;
        }
        async function draftVersion(captured, ticket) {
            var session = window.TaxonomyAnalysisSession;
            if (!session || !session.state || !session.saveNow) throw problem('SOURCE_UNAVAILABLE');
            var before = session.state();
            if (!before.ready || before.conflict || before.restoring || before.workspaceId !== captured.workspaceScopeKey) throw problem('SOURCE_UNAVAILABLE');
            if (await session.saveNow() !== true) throw problem('SOURCE_UNAVAILABLE');
            if (!active || ticket !== generation || sourceKey(workspace.getSource()) !== sourceKey(current)) throw problem('CONTEXT_CHANGED');
            var saved = session.state();
            if (!saved.ready || saved.conflict || saved.restoring || saved.workspaceId !== captured.workspaceScopeKey
                    || !Number.isSafeInteger(saved.version) || saved.version < 0) throw problem('SOURCE_UNAVAILABLE');
            return saved.version;
        }
        async function request(body, transport) {
            var ticket = generation;
            requireCurrent(body.source, ticket);
            if (body.source.kind === 'ANALYSIS_DRAFT') {
                var version = await draftVersion(body.source, ticket);
                if (version !== body.source.version) { invalidate(); throw problem('CONTEXT_CHANGED'); }
            }
            requireCurrent(body.source, ticket);
            return window.ArchitectureSourceFocusApi.suggest(body, transport).then(function (result) {
                requireCurrent(body.source, ticket);
                return result;
            });
        }
        async function adopt(item) {
            if (adopting || !canReview(item)) return;
            var ticket = generation, reviewed = item.source;
            adopting = true; panel.setLocked(true);
            try {
                requireCurrent(reviewed, ticket);
                if (reviewed.kind === 'ANALYSIS_DRAFT') {
                    await draftVersion(reviewed, ticket); requireCurrent(reviewed, ticket);
                    window.TaxonomyAnalysisSession.addAsRequirement({ preserveDraft: true }); return;
                }
                var values = { adoption: 'preview', element: selected };
                Object.keys(reviewed).filter(function (key) { return key !== 'kind'; }).forEach(function (key) {
                    values['source' + key.charAt(0).toUpperCase() + key.slice(1)] = reviewed[key];
                });
                await workspace.open('working', values);
            } catch (error) { if (ticket === generation) report(error); }
            finally { adopting = false; if (ticket === generation) panel.setLocked(false); }
        }
        var panel = window.ArchitectureFocusPanel({ root: root, idPrefix: prefix, t: t,
            capabilities: { readOnly: true, adopt: true }, request: request, present: present, adopt: adopt, canReview: canReview, errorMessage: problemMessage,
            language: function () { return window.TaxonomyI18n.getLocale() === 'de' ? 'de' : 'en'; },
            locate: function (id, item) { if (canReview(item)) workspace.select(id); } });
        function activate(source, force) {
            if (active && !force && sourceKey(source) === sourceKey(current)) return refresh();
            generation++; var ticket = generation;
            current = source && Object.freeze(Object.assign({}, source)); pinned = null; evidence = null;
            active = Boolean(source && ['analysis', 'snapshot'].includes(source.kind)); root.hidden = !active;
            panel.setSource(null, false); panel.setLocked(false); el('Error').hidden = true;
            if (!active) return Promise.resolve(false);
            pending = Promise.resolve().then(async function () {
                var value = pins(current);
                var observed = captureEvidence();
                if (value.kind === 'ANALYSIS_DRAFT') value.version = await draftVersion(value, ticket);
                if (!active || ticket !== generation) return false;
                if (!sameEvidence(observed)) throw problem('CONTEXT_CHANGED');
                pinned = Object.freeze(value); evidence = observed; panel.setSource(pinned, true); panel.select(selected);
                el('Source').textContent = t('sourceLens.source', t('sourceLens.kind.' + value.kind), value.kind === 'ANALYSIS_DRAFT' ? value.version : value.snapshotId)
                    + ' · ' + scopeKeys.map(function (key) { return value[key]; }).join(' / ');
                return true;
            }).catch(function (error) { if (active && ticket === generation) report(error); return false; });
            return pending;
        }
        function suspend() {
            generation++; active = false; current = null; pinned = null; evidence = null; pending = null;
            panel.setSource(null, false); root.hidden = true; present({ enabled: false, states: {}, suggestions: [] });
        }
        function refresh() {
            if (!active) return Promise.resolve(false);
            if (pinned && !isCurrent(pinned)) { invalidate(); return Promise.resolve(false); }
            panel.refresh(); return pending || Promise.resolve(true);
        }
        el('Refresh')?.addEventListener('click', function () { activate(workspace.getSource(), true); });
        ['taxonomy:analysis-draft-saved', 'taxonomy:analysis-draft-restored', 'taxonomy:analysis-draft-reset',
            'taxonomy:analysis-draft-save-failed', 'taxonomy:analysis-invalidated'].forEach(function (name) {
            document.addEventListener(name, function () { if (pinned?.kind === 'ANALYSIS_DRAFT' && !isCurrent(pinned)) invalidate(); });
        });
        return {
            activate: activate, suspend: suspend, refresh: refresh,
            open: function () { if (!active) return; root.hidden = false; el('Title').scrollIntoView({ block: 'nearest' }); el('Title').focus({ preventScroll: true }); },
            select: function (id) { selected = id || null; panel.select(selected); },
            observe: observe, review: review
        };
    }
    return Object.freeze({ create: create });
}());
