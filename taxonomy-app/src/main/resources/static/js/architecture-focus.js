/* Revision-bound investigation state. Transport and semantic commands are injected by the editor. */
window.ArchitectureFocusSession = function (options) {
    'use strict';
    var schedule = options.schedule || setTimeout, cancel = options.cancel || clearTimeout;
    var context = null, key = '', epoch = 0, available = false, enabled = false, paused = false, locked = false;
    var sourceMode = options.sourceMode === true;
    var budget = 16, used = 0, modelCalls = 0, useAi = false, active = null;
    var states = Object.create(null), errors = Object.create(null), queue = [], dwell = new Map(), suggestions = [], reviewed = null;
    var investigations = new Map();
    function workspaceId(c) {
        return c.workspaceId !== undefined ? (c.workspaceId ?? '')
            : c.workspaceScopeKey === '__shared__' ? '' : c.workspaceScopeKey;
    }
    function scopeIdentity(c) {
        if (!c) return '';
        var pins = [c.repositoryId, c.workspaceScopeKey, c.branch, workspaceId(c)];
        return (sourceMode ? pins.concat(c.kind, c.snapshotId) : pins.concat(c.actor, c.writeMode)).join('\u001f');
    }
    function identity(c) { return c ? scopeIdentity(c) + '\u001f' + (sourceMode
        ? [c.version, c.projectId, c.requirementId, c.requirementVersionId].join('\u001f')
        : c.commit + '\u001f' + c.revision) : ''; }
    function captureContext(value) {
        if (!value) return null;
        var captured = Object.assign({}, value);
        // Working editor contexts use a transport-only workspace pin outside the Java Context DTO.
        if (!sourceMode && value.workspaceId !== undefined)
            Object.defineProperty(captured, 'workspaceId', { value: value.workspaceId, enumerable: false });
        return Object.freeze(captured);
    }
    function matchesResponse(value, captured) {
        if (sourceMode) return identity(value) === identity(captured);
        return value && ['repositoryId', 'workspaceScopeKey', 'branch', 'actor', 'writeMode', 'commit', 'revision']
            .every(function (pin) { return value[pin] === captured[pin]; });
    }
    function changed() { if (options.changed) options.changed(snapshot()); }
    function snapshot() {
        return { enabled: enabled, available: available, paused: paused, locked: locked, budget: budget, used: used,
            modelCalls: modelCalls, useAi: useAi, active: active && active.id, queued: queue.length,
            states: Object.assign(Object.create(null), states), errors: Object.assign(Object.create(null), errors),
            suggestions: suggestions.filter(function (s) { return !s.dismissed; }), reviewed: reviewed };
    }
    function clearDwell() { dwell.forEach(cancel); dwell.clear(); }
    function canQueue(id) { return id && (!states[id] || states[id] === 'stale'); }
    function canInspect(id, retry, explicit) {
        return Boolean((explicit || enabled) && available && !paused && !locked && id && used + queue.length < budget
            && (canQueue(id) || retry && (states[id] === 'failed' || states[id] === 'unassessed' && useAi)));
    }
    function enqueue(id, retry, explicit) {
        if (!canInspect(id, retry, explicit)) return;
        queue.push({ id: id, retry: Boolean(retry), explicit: Boolean(explicit), previousState: states[id], previousError: errors[id] });
        if (retry) delete errors[id];
        states[id] = 'queued'; changed(); pump();
    }
    function restoreAttempt(attempt) {
        if (attempt.previousState) states[attempt.id] = attempt.previousState;
        else delete states[attempt.id];
        if (attempt.previousError) errors[attempt.id] = attempt.previousError;
    }
    function retainSuggestions(request, result) {
        var returned = result.suggestions.slice(0, 8);
        if (result.outcome === 'ASSESSED') {
            var returnedIds = new Set(returned.map(function (value) { return key + '\u001f' + value.id; }));
            suggestions = suggestions.flatMap(function (item) {
                if (item.dismissed || item.accepted || item.command || item.stale || item.outcome === 'ASSESSED' || returnedIds.has(item.id)) return [item];
                var sources = investigations.get(item.id);
                if (!sources || !sources.has(request.id)) return [item];
                var obsolete = sources.get(request.id);
                sources.delete(request.id);
                if (!sources.size) { investigations.delete(item.id); return []; }
                return [item === obsolete ? sources.values().next().value : item];
            });
        }
        returned.forEach(function (value) {
            var id = key + '\u001f' + value.id;
            var index = suggestions.findIndex(function (item) { return item.id === id; });
            var previous = suggestions[index];
            // An investigation can refresh evidence without rewriting a reviewer's decision or command.
            if (previous && (previous.dismissed || previous.accepted || previous.command)) return;
            var retained = Object.assign({}, value, { id: id, investigationElementId: request.id, method: result.method,
                outcome: result.outcome, sourceHash: result.sourceHash, stale: false });
            retained[sourceMode ? 'source' : 'context'] = request.context;
            if (!investigations.has(id)) investigations.set(id, new Map());
            var sources = investigations.get(id);
            var priorEvidence = sources.get(request.id);
            if (!priorEvidence || priorEvidence.outcome !== 'ASSESSED' || result.outcome === 'ASSESSED') sources.set(request.id, retained);
            if (!previous) { suggestions.push(retained); return; }
            var upgraded = result.outcome === 'ASSESSED' && previous.outcome !== 'ASSESSED';
            var refreshed = request.retry && (result.outcome === 'ASSESSED' || previous.outcome !== 'ASSESSED');
            if (previous.stale || upgraded || refreshed) suggestions[index] = retained;
        });
        if (!suggestions.some(function (s) { return s.id === reviewed && !s.dismissed; }))
            reviewed = (suggestions.find(function (s) { return !s.dismissed; }) || {}).id || null;
    }
    function pump() {
        if (!available || paused || locked || active || used >= budget || !queue.length) return;
        var request = queue.shift();
        request.epoch = epoch;
        request.context = context;
        request.controller = sourceMode ? new AbortController() : null;
        active = request; used++; states[request.id] = 'examining'; changed();
        Promise.resolve().then(function () {
            if (request.epoch !== epoch) return null;
            var body = { sourceId: request.id, candidateLimit: 8, useAi: useAi,
                language: options.language ? options.language() : 'en' };
            body[sourceMode ? 'source' : 'context'] = request.context;
            return options.request(body, {
                signal: request.controller?.signal,
                isCurrent: function () { return request.epoch === epoch && active === request; },
                onPending: options.onPending
            });
        }).then(function (result) {
            // A request may finish after navigation or a semantic edit; its evidence belongs only to its captured revision.
            if (request.epoch !== epoch) return;
            if (!result || !matchesResponse(result[sourceMode ? 'source' : 'context'], request.context) || result.sourceId !== request.id
                    || sourceMode && !/^[a-f0-9]{64}$/i.test(result.sourceHash || '')
                    || !Array.isArray(result.suggestions) || !['ASSESSED', 'LOCAL_CANDIDATES', 'UNASSESSED', 'NO_CANDIDATES', 'FAILED'].includes(result.outcome)) {
                throw Object.assign(new Error('Invalid focus response'), sourceMode ? { responseBody: { code: 'INVALID_RESPONSE' } } : {});
            }
            modelCalls += Number.isSafeInteger(result.modelCalls) && result.modelCalls > 0 ? result.modelCalls : 0;
            states[request.id] = result.outcome === 'FAILED' ? 'failed'
                : result.outcome === 'NO_CANDIDATES' ? 'noCandidates'
                : result.outcome === 'LOCAL_CANDIDATES' || result.outcome === 'UNASSESSED' ? 'unassessed'
                : result.suggestions.length ? 'suggestions' : 'examined';
            retainSuggestions(request, result);
        }).catch(function (error) {
            if (request.epoch !== epoch) return;
            states[request.id] = 'failed'; errors[request.id] = options.errorMessage ? options.errorMessage(error)
                : error.responseBody && error.responseBody.detail || error.message;
        }).finally(function () { if (active === request) active = null; changed(); pump(); });
    }
    return {
        snapshot: snapshot,
        setContext: function (value, writable) {
            var nextKey = identity(value);
            if (nextKey !== key) {
                if (sourceMode && active) { active.controller.abort(); active = null; }
                if (scopeIdentity(value) !== scopeIdentity(context)) { suggestions = []; states = Object.create(null); reviewed = null; }
                epoch++; key = nextKey; context = captureContext(value); queue = []; clearDwell(); investigations.clear();
                Object.keys(states).forEach(function (id) { states[id] = 'stale'; });
                suggestions.forEach(function (s) { s.stale = true; });
                errors = Object.create(null);
            } else if (available && !writable) {
                if (active && active.epoch === epoch) {
                    restoreAttempt(active);
                    if (active.controller) { active.controller.abort(); active = null; }
                }
                epoch++;
                queue.forEach(restoreAttempt);
                queue = [];
            }
            available = Boolean(writable);
            if (!available) { enabled = false; clearDwell(); queue = []; }
            changed(); pump();
        },
        setEnabled: function (value) {
            enabled = Boolean(value && available);
            if (!enabled) {
                clearDwell();
                queue.forEach(function (attempt) { if (!attempt.explicit) restoreAttempt(attempt); });
                queue = queue.filter(function (attempt) { return attempt.explicit; });
            }
            changed(); pump();
        },
        setPaused: function (value) { paused = Boolean(value); if (paused) clearDwell(); changed(); pump(); },
        setLocked: function (value) { locked = Boolean(value); if (locked) clearDwell(); changed(); pump(); },
        setBudget: function (value) { budget = Math.max(1, Math.min(128, Math.floor(Number(value) || 16))); changed(); pump(); },
        setUseAi: function (value) { useAi = Boolean(value); changed(); },
        canInspect: canInspect,
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
            if (dwell.has(id)) { cancel(dwell.get(id)); dwell.delete(id); }
            enqueue(id, retry, true);
        },
        review: function (id) { reviewed = id; changed(); },
        dismiss: function (id) {
            var item = suggestions.find(function (s) { return s.id === id; });
            if (item) item.dismissed = true;
            if (reviewed === id) reviewed = (suggestions.find(function (s) { return !s.dismissed; }) || {}).id || null;
            changed();
        },
        accepted: function (id, result, items) {
            var item = suggestions.find(function (s) { return s.id === id; });
            if (item) { item.accepted = result; item.acceptedItems = items; item.stale = true; reviewed = id; }
            changed();
        },
        undone: function (id) { var item = suggestions.find(function (s) { return s.id === id; }); if (item) item.undone = true; changed(); }
    };
};

/* Pinned review and accessible controls. All source/model strings enter the DOM as text. */
window.ArchitectureFocusPanel = function (options) {
    'use strict';
    var session, selected = null, lastReview = null, reviewFocus = null;
    var readOnly = options.capabilities && options.capabilities.readOnly === true;
    var checked = new Set();
    var lensPrefix = options.idPrefix || 'editorLens';
    var hoverControls = Array.from(document.querySelectorAll('[data-architecture-lens-hover="' + lensPrefix + '"]'));
    function el(id) {
        var scopedId = id.replace(/^editorLens/, lensPrefix);
        return options.root ? options.root.querySelector('#' + scopedId) : document.getElementById(scopedId);
    }
    function t() { return options.t.apply(null, arguments); }
    function node(tag, text, className) { var item = document.createElement(tag); item.textContent = text || ''; if (className) item.className = className; return item; }
    function action(label, callback, disabled, focusKey) {
        var item = node('button', label); item.type = 'button'; item.disabled = Boolean(disabled); item.onclick = callback;
        if (focusKey) item.dataset.focusAction = focusKey;
        return item;
    }
    function otherEnd(item) {
        var origin = selected === item.sourceId || selected === item.targetId ? selected : item.investigationElementId;
        return item.targetId === origin ? item.sourceId : item.targetId;
    }
    function canAccept(item) { return !item.stale && !item.accepted; }
    function reviewSuggestion(id) {
        var item = session.snapshot().suggestions.find(function (value) { return value.id === id; });
        if (!item || readOnly && options.canReview && !options.canReview(item)) return false;
        session.review(id);
        return true;
    }
    function render(state) {
        hoverControls.forEach(function (control) {
            control.checked = state.enabled;
            control.disabled = !state.available || state.locked;
        });
        el('editorLensPause').disabled = !state.available || state.locked;
        el('editorLensPause').textContent = t(state.paused ? 'editor.lens.resume' : 'editor.lens.pause');
        el('editorLensInspect').disabled = !session.canInspect(selected, true, true);
        el('editorLensUseAi').disabled = state.locked;
        el('editorLensUseAi').checked = state.useAi;
        var activity = el('editorLensActivity');
        if (activity) activity.textContent = t('editor.lens.summary', state.used, state.budget, state.queued, state.modelCalls);
        var statusKey = !state.available ? (readOnly ? 'sourceLens.unavailable' : 'editor.lens.unavailable')
            : state.paused ? 'editor.lens.paused' : state.active ? 'editor.lens.running'
            : state.used >= state.budget ? 'editor.lens.budgetReached'
            : state.states[selected] ? 'editor.lens.state.' + state.states[selected]
            : selected ? '' : 'relationshipReview.select';
        el('editorLensSummary').textContent = statusKey ? t(statusKey) : '';
        var statuses = el('editorLensStates');
        var focusedRetry = statuses.contains(document.activeElement) ? document.activeElement.dataset.retryElement : null;
        var restoreRetry = null;
        statuses.replaceChildren();
        Object.keys(state.states).slice(-16).forEach(function (id) {
            var status = state.states[id]; var row = node('li', id + ' · ' + t('editor.lens.state.' + status), 'editor-lens-state ' + status);
            if (state.errors[id]) row.append(node('span', ' — ' + state.errors[id]));
            if (status === 'failed') {
                var retry = action(t('editor.lens.retry'), function () { session.inspect(id, true); }, !session.canInspect(id, true, true));
                retry.dataset.retryElement = id;
                if (id === focusedRetry && !retry.disabled) restoreRetry = retry;
                row.append(retry);
            }
            statuses.append(row);
        });
        if (focusedRetry) {
            var retryFocus = restoreRetry || ['editorLensInspect', 'editorLensPause', 'editorLensBudget']
                .map(el).find(function (control) { return !control.disabled; });
            if (retryFocus) retryFocus.focus({ preventScroll: true });
        }
        var list = el('editorLensSuggestions');
        var activeProposal = list.contains(document.activeElement) ? document.activeElement : null;
        var focusedProposal = activeProposal?.dataset.proposalId;
        var controlTag = activeProposal?.tagName;
        list.replaceChildren();
        var visible = state.suggestions.slice(-32);
        var names = state.suggestions.length ? options.elementNames?.() : null;
        function name(item, id) {
            return names?.get(id) || item.evidence?.find(function (e) { return e.elementId === id && e.field === 'title'; })?.quote || id;
        }
        function label(item) { return window.ArchitectureFocusPanel.relationLabel(item, name, t); }
        var marked = visible.filter(function (item) { return canAccept(item) && checked.has(item.id); });
        checked = new Set(marked.map(function (item) { return item.id; }));
        visible.forEach(function (item) {
            var row = node('li');
            var select = action(label(item), function () { reviewSuggestion(item.id); });
            select.className = 'relationship-review-candidate';
            select.setAttribute('aria-pressed', String(state.reviewed === item.id));
            select.dataset.proposalId = item.id;
            if (!readOnly && options.acceptSelection && canAccept(item)) {
                var checkbox = node('input'); checkbox.type = 'checkbox'; checkbox.checked = checked.has(item.id);
                checkbox.disabled = state.locked || !state.available;
                checkbox.dataset.proposalId = item.id;
                checkbox.setAttribute('aria-label', t('relationshipReview.mark', label(item)));
                checkbox.onchange = function () {
                    if (this.checked) checked.add(item.id); else checked.delete(item.id);
                    render(session.snapshot());
                };
                row.className = 'relationship-review-selectable';
                row.append(checkbox);
            }
            row.append(select, node('small', t(item.accepted ? (item.undone ? 'editor.lens.undone' : 'editor.lens.accepted') : item.stale ? 'editor.lens.stale'
                : item.outcome === 'ASSESSED' ? 'relationshipReview.assessed' : 'relationshipReview.local')));
            list.append(row);
        });
        if (focusedProposal) {
            var restore = Array.from(list.querySelectorAll(controlTag)).find(function (control) { return control.dataset.proposalId === focusedProposal; });
            if (restore) restore.focus({ preventScroll: true });
        }
        var batch = el('editorLensBatch');
        if (batch) {
            batch.hidden = !marked.length;
            batch.disabled = state.locked || !state.available;
            batch.textContent = t(marked.length === 1 ? 'editor.lens.accept' : 'relationshipReview.previewSelected', marked.length);
            batch.onclick = function () { options.acceptSelection(marked); };
        }
        var count = el('editorLensCount');
        count.hidden = state.suggestions.length === 0;
        count.textContent = state.suggestions.length > 32
            ? t('editor.lens.count', state.suggestions.length)
            : t('relationshipReview.count', state.suggestions.length);
        var chosen = state.suggestions.find(function (s) { return s.id === state.reviewed; });
        var review = el('editorLensReview');
        var reviewWasFocused = review.contains(document.activeElement);
        if (reviewWasFocused) reviewFocus = document.activeElement.dataset.focusAction || null;
        // Preserve text selection and keyboard focus while queue updates arrive.
        var reviewKey = chosen && JSON.stringify([chosen.id, label(chosen), otherEnd(chosen), chosen.stale, Boolean(chosen.accepted), chosen.undone,
            state.locked, state.available, chosen.rationale, chosen.evidence, chosen.method, chosen.outcome,
            chosen.sourceHash, chosen.properties]);
        if (reviewKey !== lastReview) {
            lastReview = reviewKey; review.replaceChildren();
            if (!chosen) review.append(node('p', t('editor.lens.empty')));
            else {
                review.append(node('h3', label(chosen)));
                review.append(node('p', t(chosen.stale && !chosen.accepted ? 'editor.lens.stale' : chosen.outcome === 'ASSESSED' ? 'editor.lens.hypothesis' : 'editor.lens.local'), 'editor-lens-notice'));
                review.append(node('p', chosen.rationale, 'editor-lens-rationale'));
                if (chosen.evidence?.length) {
                    var proof = node('ul', '', 'relationship-review-evidence');
                    chosen.evidence.forEach(function (evidence) {
                        var fieldKey = 'relationshipReview.field.' + evidence.field;
                        var label = t(fieldKey);
                        var row = node('li', evidence.elementId + ' · ' + (label === fieldKey ? evidence.field : label));
                        row.append(node('blockquote', evidence.quote)); proof.append(row);
                    });
                    review.append(proof);
                }
                var actions = node('div', '', 'relationship-review-toolbar');
                if (!readOnly && chosen.accepted) {
                    actions.append(node('span', t(chosen.undone ? 'editor.lens.undone' : 'editor.lens.accepted')));
                    var undoCount = chosen.acceptedItems?.length || 1;
                    actions.append(action(undoCount === 1 ? t('editor.undo') : t('relationshipReview.undoSelected', undoCount),
                        function () { options.undo(chosen); }, chosen.undone || state.locked || !options.canUndo(chosen), 'undo'));
                } else {
                    if (!readOnly) {
                        var apply = action(t('editor.lens.accept'), function () { options.accept(chosen); }, chosen.stale || state.locked || !state.available, 'accept');
                        apply.className = 'relationship-review-primary';
                        actions.append(apply);
                    }
                    actions.append(action(t('editor.lens.dismiss'), function () { session.dismiss(chosen.id); }, state.locked, 'dismiss'));
                }
                var other = otherEnd(chosen);
                actions.append(action(t('relationshipReview.locate', name(chosen, other)), function () { options.locate(other, chosen); }, state.locked, 'locate'));
                review.append(actions);
                var provenance = node('details', '', 'relationship-review-provenance');
                provenance.append(node('summary', t('relationshipReview.provenance')));
                provenance.append(node('p', readOnly
                    ? t('sourceLens.method', chosen.method, chosen.source.kind === 'ANALYSIS_SNAPSHOT'
                        ? chosen.source.snapshotId : chosen.source.version)
                    : t('editor.lens.method', chosen.method, chosen.context.revision)));
                if (readOnly) provenance.append(node('p', t('sourceLens.sourceHash', chosen.sourceHash)));
                review.append(provenance);
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
        options.present({ enabled: state.enabled || Object.keys(state.states).length > 0, hover: state.enabled, states: state.states, suggestions: state.suggestions.filter(function (s) { return !s.stale && !s.accepted; }),
            reviewed: state.reviewed, label: function (status) { return t('editor.lens.state.' + status); } });
    }
    session = window.ArchitectureFocusSession({ request: options.request, language: options.language, changed: render,
        sourceMode: readOnly, errorMessage: options.errorMessage,
        onPending: window.AnalysisAdapterApi?.pendingButton(el('editorLensInspect')) });
    hoverControls.forEach(function (control) {
        control.onchange = function () { session.setEnabled(this.checked); };
    });
    el('editorLensPause').onclick = function () { session.setPaused(!session.snapshot().paused); };
    el('editorLensBudget').onchange = function () { session.setBudget(this.value); this.value = session.snapshot().budget; };
    el('editorLensUseAi').onchange = function () { session.setUseAi(this.checked); };
    el('editorLensInspect').onclick = function () { session.inspect(selected, true); };
    return {
        setContext: session.setContext, setLocked: session.setLocked, observe: session.observe, review: reviewSuggestion,
        setSource: session.setContext,
        accepted: session.accepted, undone: session.undone,
        select: function (id) { selected = id; render(session.snapshot()); },
        refresh: function () { lastReview = null; render(session.snapshot()); }
    };
};

window.ArchitectureFocusPanel.relationLabel = function (item, name, t) {
    var key = 'mermaid.relation.' + item.relationType.toLowerCase().replaceAll('_', '.');
    var translated = t(key);
    var relation = !translated || translated === key ? item.relationType : translated;
    return name(item, item.sourceId) + ' — ' + relation + ' → ' + name(item, item.targetId);
};
