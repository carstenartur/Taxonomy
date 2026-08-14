/**
 * Direct Git-authoritative browser adapter for analysis hypotheses.
 *
 * It replaces the historic proposal-conversion accept path and the UI-only
 * dismiss path after taxonomy-scoring.js has installed its compatibility
 * functions. Every command reads a strong branch ETag, supplies If-Match and an
 * idempotency key, advances the ETag sequentially for batch acceptance, and
 * distinguishes projected success, pending recovery and concurrent changes.
 */
(function () {
    'use strict';

    var initialized = false;
    var busy = false;
    var sequence = 0;

    function init() {
        if (initialized) return;
        initialized = true;
        window._acceptHypothesis = function (index) {
            reviewIndices([index], 'ACCEPT', true);
        };
        window._rejectHypothesis = function (index) {
            reviewIndices([index], 'REJECT', true);
        };
        window._acceptAllHighConfidence = function () {
            var relations = currentRelations();
            var indices = [];
            relations.forEach(function (hypothesis, index) {
                if (hypothesis.confidence >= 0.8
                        && hypothesis.hypothesisId) {
                    indices.push(index);
                }
            });
            reviewIndices(indices, 'ACCEPT', true);
        };
        window._applyForSession = applyForSession;
    }

    function Api() {
        if (!window.TaxonomyHypothesesApi) {
            throw new Error('Hypothesis review API client is not available.');
        }
        return window.TaxonomyHypothesesApi;
    }

    function reviewIndices(indices, action, offerUndo) {
        if (busy) return;
        var commands = reviewCommands(indices);
        if (commands.length === 0) {
            showStatus('warning',
                'No persisted hypothesis is available for this review action.');
            return;
        }

        busy = true;
        setBusy(commands, true);
        var operationKey = commandKey(
            'hypothesis-' + action.toLowerCase());
        var state = {
            etag: null,
            projected: [],
            failed: [],
            pending: null,
            stopped: false
        };

        readHead()
            .then(function (etag) {
                state.etag = etag;
                return processNext(
                    commands, action, operationKey, state, 0);
            })
            .then(function () {
                finish(state, action, offerUndo);
            })
            .catch(function (error) {
                fail(error, commands);
            });
    }

    function reviewCommands(indices) {
        var relations = currentRelations();
        var seen = Object.create(null);
        var commands = [];
        (indices || []).forEach(function (index) {
            var hypothesis = relations[index];
            var id = hypothesis && Number(hypothesis.hypothesisId);
            if (!hypothesis || !Number.isSafeInteger(id)
                    || id <= 0 || seen[id]) {
                return;
            }
            seen[id] = true;
            commands.push({
                index: Number(index),
                id: id,
                hypothesis: hypothesis
            });
        });
        return commands;
    }

    function processNext(commands, action, operationKey, state, offset) {
        if (state.stopped || offset >= commands.length) {
            return Promise.resolve(state);
        }
        var command = commands[offset];
        var headers = {
            'If-Match': state.etag,
            'Idempotency-Key': operationKey + ':'
                + offset + ':' + command.id
        };
        return Api().review(command.id, action, headers)
            .then(function (response) {
                return classify(response, state.etag, command);
            })
            .then(function (outcome) {
                if (outcome.etag) state.etag = outcome.etag;
                if (outcome.kind === 'PROJECTED') {
                    command.hypothesis.status = action === 'REVERT'
                        ? 'PROVISIONAL' : action + 'ED';
                    state.projected.push(command);
                } else if (outcome.kind === 'PENDING_RECOVERY') {
                    state.pending = outcome;
                    state.stopped = true;
                } else {
                    state.failed.push(outcome);
                }
                return processNext(
                    commands, action, operationKey, state, offset + 1);
            });
    }

    function readHead() {
        return Api().readHead().then(function (response) {
            if (response.status === 404) {
                throw commandError(
                    'BRANCH_MISSING',
                    'The selected architecture branch does not exist.');
            }
            if (!response.ok) {
                throw commandError(
                    'HTTP',
                    'Could not read the hypothesis review head (HTTP '
                        + response.status + ').');
            }
            var etag = response.headers.get('ETag');
            if (!etag) {
                throw commandError(
                    'MISSING_ETAG',
                    'The hypothesis review head did not contain a strong ETag.');
            }
            return etag;
        });
    }

    function classify(response, currentEtag, command) {
        var nextEtag = response.headers.get('ETag') || currentEtag;
        return readJson(response).then(function (body) {
            if (response.status === 202) {
                return {
                    kind: 'PENDING_RECOVERY',
                    command: command,
                    etag: nextEtag,
                    body: body
                };
            }
            if (response.status === 412) {
                throw commandError(
                    'CONFLICT',
                    'The selected architecture branch changed during hypothesis review.',
                    body,
                    nextEtag);
            }
            if (response.ok) {
                return {
                    kind: 'PROJECTED',
                    command: command,
                    etag: nextEtag,
                    body: body
                };
            }
            return {
                kind: 'FAILED',
                command: command,
                etag: currentEtag,
                status: response.status,
                body: body
            };
        });
    }

    function finish(state, action, offerUndo) {
        busy = false;
        setBusy(state.projected, false);

        state.projected.forEach(function (command) {
            renderCompleted(command, action, offerUndo);
        });
        state.failed.forEach(function (outcome) {
            setRowBusy(outcome.command, false);
            renderFailed(outcome.command, outcome.status);
        });

        if (state.pending) {
            setRowBusy(state.pending.command, false);
            renderPending(state.pending.command, state.pending.body);
            showStatus(
                'warning',
                state.projected.length
                    + ' hypothesis review(s) completed; the last Git commit '
                    + 'still requires projection or bookkeeping recovery.');
            return;
        }
        if (state.failed.length > 0) {
            showStatus(
                'warning',
                state.projected.length + ' hypothesis review(s) completed; '
                    + state.failed.length + ' were rejected.');
            return;
        }
        showStatus(
            'success',
            state.projected.length + ' hypothesis review(s) committed to Git.');
    }

    function fail(error, commands) {
        busy = false;
        setBusy(commands, false);
        if (error.kind === 'CONFLICT') {
            showStatus('warning', error.message
                + ' Run the analysis again to refresh the review queue.');
            return;
        }
        showStatus('danger', 'Hypothesis review failed: ' + error.message);
    }

    function applyForSession(index) {
        if (busy) return;
        var relations = currentRelations();
        var hypothesis = relations[index];
        var id = hypothesis && Number(hypothesis.hypothesisId);
        if (!hypothesis || !Number.isSafeInteger(id) || id <= 0) {
            showStatus('warning',
                'This hypothesis has no persisted review identity.');
            return;
        }
        var command = { index: index, id: id, hypothesis: hypothesis };
        setRowBusy(command, true);
        Api().applyForSession(id)
            .then(function (response) {
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status);
                }
                hypothesis.appliedInCurrentAnalysis = true;
                var row = rowFor(command);
                if (row) row.classList.add('table-info');
                replaceActions(command,
                    '<span class="badge bg-info">Session only</span>');
                showStatus('info', 'Applied for this analysis session: '
                    + hypothesis.sourceCode + ' → ' + hypothesis.targetCode);
            })
            .catch(function (error) {
                setRowBusy(command, false);
                showStatus('danger',
                    'Could not apply hypothesis for this session: '
                        + error.message);
            });
    }

    function renderCompleted(command, action, offerUndo) {
        var row = rowFor(command);
        if (row) {
            row.style.opacity = '1';
            row.classList.remove('table-danger', 'table-warning');
            row.classList.add(action === 'REJECT'
                ? 'table-danger' : 'table-success');
        }
        var label = action === 'REJECT' ? 'Rejected'
            : action === 'REVERT' ? 'Provisional' : 'Accepted';
        var css = action === 'REJECT' ? 'danger'
            : action === 'REVERT' ? 'secondary' : 'success';
        var html = '<span class="badge bg-' + css + '">'
            + escapeHtml(label) + '</span>';
        if (offerUndo && action !== 'REVERT') {
            html += ' <button type="button" class="btn btn-sm btn-link '
                + 'p-0 ms-1 hypothesis-undo">Undo</button>';
        }
        replaceActions(command, html);
        var actions = actionsFor(command);
        var undo = actions && actions.querySelector('.hypothesis-undo');
        if (undo) {
            undo.addEventListener('click', function () {
                reviewIndices([command.index], 'REVERT', false);
            });
        }
    }

    function renderPending(command, body) {
        var commit = body && body.authoritativeCommitId
            ? body.authoritativeCommitId.substring(0, 12) : '';
        replaceActions(command,
            '<span class="badge bg-warning text-dark">Recovery pending'
                + (commit ? ' ' + escapeHtml(commit) : '') + '</span>');
    }

    function renderFailed(command, status) {
        replaceActions(command,
            '<span class="badge bg-danger">Rejected (HTTP '
                + escapeHtml(status) + ')</span>');
    }

    function setBusy(commands, active) {
        (commands || []).forEach(function (command) {
            setRowBusy(command, active);
        });
    }

    function setRowBusy(command, active) {
        var row = rowFor(command);
        if (!row) return;
        row.setAttribute('aria-busy', String(active));
        row.style.opacity = active ? '0.55' : '1';
        row.querySelectorAll('button').forEach(function (button) {
            button.disabled = active;
        });
    }

    function rowFor(command) {
        return document.getElementById(
            'suggested-row-' + command.index);
    }

    function actionsFor(command) {
        var row = rowFor(command);
        return row && row.querySelector('td:last-child');
    }

    function replaceActions(command, html) {
        var actions = actionsFor(command);
        if (actions) actions.innerHTML = html;
    }

    function currentRelations() {
        return Array.isArray(window._currentProvisionalRelations)
            ? window._currentProvisionalRelations : [];
    }

    function commandKey(prefix) {
        sequence++;
        if (window.crypto && typeof window.crypto.randomUUID === 'function') {
            return prefix + ':' + window.crypto.randomUUID();
        }
        return prefix + ':' + Date.now() + ':' + sequence;
    }

    function readJson(response) {
        var contentType = response.headers.get('Content-Type') || '';
        if (contentType.indexOf('application/json') < 0) {
            return Promise.resolve(null);
        }
        return response.json().catch(function () { return null; });
    }

    function commandError(kind, message, body, etag) {
        var error = new Error(message);
        error.kind = kind;
        error.body = body || null;
        error.etag = etag || null;
        return error;
    }

    function showStatus(type, message) {
        if (window.TaxonomyBrowse
                && typeof window.TaxonomyBrowse.showStatus === 'function') {
            window.TaxonomyBrowse.showStatus(type, message);
            return;
        }
        console[type === 'danger' ? 'error' : 'warn'](
            '[Taxonomy] ' + message);
    }

    function escapeHtml(value) {
        if (window.TaxonomyUtils && window.TaxonomyUtils.escapeHtml) {
            return window.TaxonomyUtils.escapeHtml(String(value));
        }
        return String(value).replace(/[&<>"']/g, function (character) {
            return {
                '&': '&amp;',
                '<': '&lt;',
                '>': '&gt;',
                '"': '&quot;',
                "'": '&#39;'
            }[character];
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
}());
