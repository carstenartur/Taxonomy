/* Revision-bound investigation state. Transport and semantic commands are injected by the editor. */
window.ArchitectureFocusSession = function (options) {
    'use strict';
    var schedule = options.schedule || setTimeout, cancel = options.cancel || clearTimeout;
    var context = null, key = '', epoch = 0, available = false, enabled = false, paused = false, locked = false;
    var budget = 16, used = 0, modelCalls = 0, useAi = true, active = null;
    var states = Object.create(null), errors = Object.create(null), queue = [], dwell = new Map(), suggestions = [], reviewed = null;
    function scopeIdentity(c) { return c ? [c.repositoryId, c.workspaceScopeKey, c.branch, c.actor, c.writeMode].join('\u001f') : ''; }
    function identity(c) { return c ? scopeIdentity(c) + '\u001f' + c.commit + '\u001f' + c.revision : ''; }
    function changed() { if (options.changed) options.changed(snapshot()); }
    function snapshot() {
        return { enabled: enabled, available: available, paused: paused, locked: locked, budget: budget, used: used,
            modelCalls: modelCalls, useAi: useAi, active: active && active.id, queued: queue.length,
            states: Object.assign(Object.create(null), states), errors: Object.assign(Object.create(null), errors),
            suggestions: suggestions.filter(function (s) { return !s.dismissed; }), reviewed: reviewed };
    }
    function clearDwell() { dwell.forEach(cancel); dwell.clear(); }
    function canQueue(id) { return id && (!states[id] || states[id] === 'stale'); }
    function enqueue(id) {
        if (!enabled || !available || paused || locked || !canQueue(id) || used + queue.length >= budget) return;
        states[id] = 'queued'; queue.push(id); changed(); pump();
    }
    function pump() {
        if (!enabled || !available || paused || locked || active || used >= budget || !queue.length) return;
        var request = { id: queue.shift(), epoch: epoch, context: context };
        active = request; used++; states[request.id] = 'examining'; changed();
        Promise.resolve().then(function () {
            return options.request({ context: request.context, sourceId: request.id, candidateLimit: 8, useAi: useAi,
                language: options.language ? options.language() : 'en' });
        }).then(function (result) {
            // A request may finish after navigation or a semantic edit; its evidence belongs only to its captured revision.
            modelCalls += Number.isSafeInteger(result.modelCalls) && result.modelCalls > 0 ? result.modelCalls : 0;
            if (request.epoch !== epoch) return;
            if (identity(result.context) !== key || result.sourceId !== request.id
                    || !Array.isArray(result.suggestions) || !['ASSESSED', 'LOCAL_CANDIDATES', 'UNASSESSED', 'NO_CANDIDATES', 'FAILED'].includes(result.outcome)) {
                throw new Error('Invalid focus response');
            }
            states[request.id] = result.outcome === 'FAILED' ? 'failed'
                : result.outcome === 'NO_CANDIDATES' ? 'noCandidates'
                : result.outcome === 'LOCAL_CANDIDATES' || result.outcome === 'UNASSESSED' ? 'unassessed'
                : result.suggestions.length ? 'suggestions' : 'examined';
            result.suggestions.slice(0, 8).forEach(function (value) {
                var id = key + '\u001f' + value.id;
                var previous = suggestions.find(function (s) { return s.id === id; });
                if (previous && !previous.stale) return;
                if (previous) suggestions = suggestions.filter(function (s) { return s.id !== id; });
                suggestions.push(Object.assign({}, value, { id: id, context: request.context, method: result.method,
                    outcome: result.outcome, sourceHash: result.sourceHash, stale: false }));
                if (!reviewed) reviewed = id;
            });
        }).catch(function (error) {
            if (request.epoch !== epoch) return;
            states[request.id] = 'failed'; errors[request.id] = error.responseBody && error.responseBody.detail || error.message;
        }).finally(function () { active = null; changed(); pump(); });
    }
    return {
        snapshot: snapshot,
        setContext: function (value, writable) {
            var nextKey = identity(value);
            if (nextKey !== key) {
                if (scopeIdentity(value) !== scopeIdentity(context)) { suggestions = []; states = Object.create(null); reviewed = null; }
                epoch++; key = nextKey; context = value && Object.freeze(Object.assign({}, value)); queue = []; clearDwell();
                Object.keys(states).forEach(function (id) { states[id] = 'stale'; });
                suggestions.forEach(function (s) { s.stale = true; });
                errors = Object.create(null);
            }
            available = Boolean(writable);
            if (!available) { enabled = false; clearDwell(); queue = []; }
            changed(); pump();
        },
        setEnabled: function (value) { enabled = Boolean(value && available); if (!enabled) clearDwell(); changed(); pump(); },
        setPaused: function (value) { paused = Boolean(value); if (paused) clearDwell(); changed(); pump(); },
        setLocked: function (value) { locked = Boolean(value); if (locked) clearDwell(); changed(); pump(); },
        setBudget: function (value) { budget = Math.max(1, Math.min(128, Math.floor(Number(value) || 16))); changed(); pump(); },
        setUseAi: function (value) { useAi = Boolean(value); changed(); },
        observe: function (ids) {
            var present = new Set(ids);
            dwell.forEach(function (timer, id) { if (!present.has(id)) { cancel(timer); dwell.delete(id); } });
            if (!enabled || !available || paused || locked) return;
            ids.forEach(function (id) {
                if (!canQueue(id) || dwell.has(id)) return;
                dwell.set(id, schedule(function () { dwell.delete(id); enqueue(id); }, 350));
            });
        },
        inspect: function (id, retry) {
            if (retry && states[id] === 'failed') delete states[id];
            if (dwell.has(id)) { cancel(dwell.get(id)); dwell.delete(id); }
            enqueue(id);
        },
        review: function (id) { reviewed = id; changed(); },
        dismiss: function (id) {
            var item = suggestions.find(function (s) { return s.id === id; });
            if (item) item.dismissed = true;
            if (reviewed === id) reviewed = (suggestions.find(function (s) { return !s.dismissed; }) || {}).id || null;
            changed();
        },
        accepted: function (id, result) {
            var item = suggestions.find(function (s) { return s.id === id; });
            if (item) { item.accepted = result; item.stale = true; reviewed = id; }
            changed();
        },
        undone: function (id) { var item = suggestions.find(function (s) { return s.id === id; }); if (item) item.undone = true; changed(); }
    };
};

/* Pinned review and accessible controls. All source/model strings enter the DOM as text. */
window.ArchitectureFocusPanel = function (options) {
    'use strict';
    var session, selected = null, lastReview = null, reviewFocus = null;
    function el(id) { return document.getElementById(id); }
    function t() { return options.t.apply(null, arguments); }
    function node(tag, text, className) { var item = document.createElement(tag); item.textContent = text || ''; if (className) item.className = className; return item; }
    function action(label, callback, disabled, focusKey) {
        var item = node('button', label); item.type = 'button'; item.disabled = Boolean(disabled); item.onclick = callback;
        if (focusKey) item.dataset.focusAction = focusKey;
        return item;
    }
    function render(state) {
        el('editorLensEnabled').checked = state.enabled;
        el('editorLensEnabled').disabled = !state.available || state.locked;
        el('editorLensPause').disabled = !state.enabled || state.locked;
        el('editorLensPause').textContent = t(state.paused ? 'editor.lens.resume' : 'editor.lens.pause');
        el('editorLensInspect').disabled = !selected || !state.enabled || state.paused || state.locked || state.used >= state.budget;
        el('editorLensUseAi').disabled = state.locked;
        el('editorLensSummary').textContent = t('editor.lens.summary', state.used, state.budget, state.queued, state.modelCalls)
            + ' · ' + t(!state.available ? 'editor.lens.unavailable' : !state.enabled ? 'editor.lens.off' : state.paused ? 'editor.lens.paused'
                : state.active ? 'editor.lens.running' : state.used >= state.budget ? 'editor.lens.budgetReached' : 'editor.lens.ready');
        var statuses = el('editorLensStates'); statuses.replaceChildren();
        Object.keys(state.states).slice(-16).forEach(function (id) {
            var status = state.states[id]; var row = node('li', id + ' · ' + t('editor.lens.state.' + status), 'editor-lens-state ' + status);
            if (state.errors[id]) row.append(node('span', ' — ' + state.errors[id]));
            if (status === 'failed') row.append(action(t('editor.lens.retry'), function () { session.inspect(id, true); }, !state.enabled || state.locked || state.used >= state.budget));
            statuses.append(row);
        });
        var list = el('editorLensSuggestions');
        var focusedProposal = list.contains(document.activeElement) ? document.activeElement.dataset.proposalId : null;
        list.replaceChildren();
        state.suggestions.slice(-32).forEach(function (item) {
            var row = node('li'); var select = action(item.sourceId + ' → ' + item.targetId + ' · ' + item.relationType, function () { session.review(item.id); });
            select.setAttribute('aria-pressed', String(state.reviewed === item.id));
            select.dataset.proposalId = item.id;
            row.append(select, node('small', t(item.accepted ? (item.undone ? 'editor.lens.undone' : 'editor.lens.accepted') : item.stale ? 'editor.lens.stale'
                : item.outcome === 'ASSESSED' ? 'editor.lens.hypothesis' : 'editor.lens.local'))); list.append(row);
        });
        if (focusedProposal) {
            var restore = Array.from(list.querySelectorAll('button')).find(function (b) { return b.dataset.proposalId === focusedProposal; });
            if (restore) restore.focus({ preventScroll: true });
        }
        el('editorLensCount').textContent = t('editor.lens.count', state.suggestions.length);
        var chosen = state.suggestions.find(function (s) { return s.id === state.reviewed; });
        var review = el('editorLensReview');
        var reviewWasFocused = review.contains(document.activeElement);
        if (reviewWasFocused) reviewFocus = document.activeElement.dataset.focusAction || null;
        // Preserve text selection and keyboard focus while queue updates arrive.
        var reviewKey = chosen && [chosen.id, chosen.stale, Boolean(chosen.accepted), chosen.undone, state.locked, state.available].join('|');
        if (reviewKey !== lastReview) {
            lastReview = reviewKey; review.replaceChildren();
            if (!chosen) review.append(node('p', t('editor.lens.empty')));
            else {
                review.append(node('h3', chosen.sourceId + ' — ' + chosen.relationType + ' → ' + chosen.targetId));
                review.append(node('p', t(chosen.stale && !chosen.accepted ? 'editor.lens.stale' : chosen.outcome === 'ASSESSED' ? 'editor.lens.hypothesis' : 'editor.lens.local'), 'editor-lens-notice'));
                review.append(node('p', t('editor.lens.method', chosen.method, chosen.context.revision)));
                review.append(node('p', chosen.rationale, 'editor-lens-rationale'));
                var proof = node('ul'); (chosen.evidence || []).forEach(function (evidence) {
                    var row = node('li', evidence.elementId + ' · ' + evidence.field);
                    row.append(node('blockquote', evidence.quote)); proof.append(row);
                }); review.append(proof);
                var actions = node('div', '', 'editor-toolbar');
                if (chosen.accepted) {
                    actions.append(node('span', t(chosen.undone ? 'editor.lens.undone' : 'editor.lens.accepted')));
                    actions.append(action(t('editor.undo'), function () { options.undo(chosen); }, chosen.undone || state.locked || !options.canUndo(chosen), 'undo'));
                } else {
                    actions.append(action(t(chosen.command && chosen.previewed ? 'editor.lens.retryAccept' : 'editor.lens.accept'), function () { options.accept(chosen); }, chosen.stale || state.locked || !state.available, 'accept'));
                    actions.append(action(t('editor.lens.dismiss'), function () { session.dismiss(chosen.id); }, state.locked, 'dismiss'));
                }
                actions.append(action(t('editor.lens.locate'), function () { options.locate(chosen.targetId); }, state.locked, 'locate'));
                review.append(actions);
            }
        }
        if (reviewFocus && !state.locked) {
            if (reviewWasFocused || document.activeElement === document.body) {
                var wanted = reviewFocus === 'accept' && chosen && chosen.accepted ? 'undo' : reviewFocus;
                var control = Array.from(review.querySelectorAll('button')).find(function (b) { return b.dataset.focusAction === wanted && !b.disabled; });
                if (!control) control = review.querySelector('button:not(:disabled)');
                if (control) control.focus({ preventScroll: true });
            }
            reviewFocus = null;
        }
        options.present({ enabled: state.enabled, states: state.states, suggestions: state.suggestions.filter(function (s) { return !s.stale && !s.accepted; }),
            reviewed: state.reviewed, label: function (status) { return t('editor.lens.state.' + status); } });
    }
    session = window.ArchitectureFocusSession({ request: options.request, language: options.language, changed: render });
    el('editorLensEnabled').onchange = function () { session.setEnabled(this.checked); };
    el('editorLensPause').onclick = function () { session.setPaused(!session.snapshot().paused); };
    el('editorLensBudget').onchange = function () { session.setBudget(this.value); this.value = session.snapshot().budget; };
    el('editorLensUseAi').onchange = function () { session.setUseAi(this.checked); };
    el('editorLensInspect').onclick = function () { session.inspect(selected, true); };
    return {
        setContext: session.setContext, setLocked: session.setLocked, observe: session.observe, review: session.review,
        accepted: session.accepted, undone: session.undone,
        select: function (id) { selected = id; render(session.snapshot()); },
        refresh: function () { lastReview = null; render(session.snapshot()); }
    };
};
