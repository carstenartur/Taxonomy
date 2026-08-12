/**
 * Git-authoritative proposal review adapter.
 *
 * The existing proposal browser remains responsible for listing and rendering
 * proposals. This adapter replaces its review actions with exact Git commands,
 * advances the returned branch ETag after every commit, and sequences bulk
 * decisions so that no two commands race on the same branch head.
 */
(function () {
    'use strict';

    var t = TaxonomyI18n.t;
    var commandSequence = 0;
    var busy = false;
    var undoState = null;
    var undoTimeout = null;
    var UNDO_TOAST_DURATION_MS = 8000;
    var UNDO_FADE_DURATION_MS = 300;

    function init() {
        installGlobalReviewHandlers();
        document.addEventListener('click', captureProposalCommand, true);
    }

    /**
     * Proposal rows use inline calls to these two public functions. Installing
     * the replacements on DOM readiness wins regardless of whether this
     * dynamically loaded adapter arrived before or after taxonomy-browse.js.
     */
    function installGlobalReviewHandlers() {
        window._proposalAccept = function (id) {
            reviewProposals([id], 'ACCEPT');
        };
        window._proposalReject = function (id) {
            reviewProposals([id], 'REJECT');
        };
    }

    /** Capture dynamically rendered bulk and undo controls before legacy handlers. */
    function captureProposalCommand(event) {
        var target = event.target;
        if (!target || typeof target.closest !== 'function') return;

        var bulkAccept = target.closest('#bulkAcceptBtn');
        if (bulkAccept) {
            event.preventDefault();
            event.stopImmediatePropagation();
            reviewProposals(selectedProposalIds(), 'ACCEPT');
            return;
        }

        var bulkReject = target.closest('#bulkRejectBtn');
        if (bulkReject) {
            event.preventDefault();
            event.stopImmediatePropagation();
            reviewProposals(selectedProposalIds(), 'REJECT');
            return;
        }

        var undoButton = target.closest(
            '#undoToast[data-git-authoritative="true"] #undoBtn');
        if (undoButton && undoState) {
            event.preventDefault();
            event.stopImmediatePropagation();
            var ids = undoState.ids.slice();
            dismissUndoToast();
            reviewProposals(ids, 'REVERT');
        }
    }

    function reviewProposals(ids, action) {
        var proposalIds = normalizeIds(ids);
        if (proposalIds.length === 0) return;
        if (busy) {
            showStatus('info', message(
                'browse.proposals.git.busy',
                'A proposal review is already in progress.'));
            return;
        }

        busy = true;
        setBusy(true);
        updateProgress(0, proposalIds.length, action);

        var completed = [];
        var authority;
        refreshAuthority()
            .then(function (currentAuthority) {
                authority = currentAuthority;
                return runSequentially(
                    proposalIds, action, authority, completed);
            })
            .then(function () {
                showSuccess(action, completed.length);
                refreshProposalBrowser();
                if (action !== 'REVERT' && completed.length > 0) {
                    showUndoToast(completed, action);
                }
            })
            .catch(function (error) {
                handleFailure(
                    error, action, proposalIds.length, completed);
                refreshProposalBrowser();
                if (action !== 'REVERT' && completed.length > 0) {
                    showUndoToast(completed, action);
                }
            })
            .finally(function () {
                busy = false;
                setBusy(false);
            });
    }

    /** Promise chaining is intentional: each response ETag guards the next commit. */
    function runSequentially(ids, action, authority, completed) {
        var sequence = Promise.resolve();
        ids.forEach(function (id, index) {
            sequence = sequence
                .then(function () {
                    updateProgress(index, ids.length, action);
                    return sendReviewCommand(id, action, authority);
                })
                .then(function () {
                    completed.push(id);
                    updateProgress(index + 1, ids.length, action);
                });
        });
        return sequence;
    }

    /**
     * Read the exact selected repository/branch head through the lightweight
     * relation count boundary. A 409 may still carry a valid Git ETag: the
     * subsequent command performs a full rebuild and can repair that projection.
     */
    function refreshAuthority() {
        return fetch('/api/relations/count', { cache: 'no-store' })
            .then(function (response) {
                var state = response.headers.get(
                    'X-Taxonomy-Relation-Projection-State');
                var etag = response.headers.get('ETag');

                if (response.status === 404 && state === 'BRANCH_MISSING') {
                    return {
                        etag: null,
                        branchMissing: true,
                        projectionState: state
                    };
                }
                if ((response.ok || response.status === 409) && etag) {
                    return {
                        etag: etag,
                        branchMissing: false,
                        projectionState: state
                    };
                }
                throw commandError(
                    'AUTHORITY',
                    'Could not read the current Git branch head (HTTP '
                        + response.status + ').');
            });
    }

    function sendReviewCommand(id, action, authority) {
        return fetch(commandUrl(id, action), {
            method: 'POST',
            headers: commandHeaders(id, action, authority)
        }).then(function (response) {
            var nextEtag = response.headers.get('ETag');
            if (nextEtag) {
                authority.etag = nextEtag;
                authority.branchMissing = false;
            }
            return readJson(response).then(function (body) {
                if (response.status === 202) {
                    var phase = body && body.pendingPhase
                        ? body.pendingPhase
                        : 'recovery';
                    throw commandError(
                        'PENDING',
                        'Git accepted proposal ' + id
                            + ', but ' + phase + ' is pending recovery.',
                        id,
                        body);
                }
                if (response.status === 412) {
                    throw commandError(
                        'CONFLICT',
                        'The branch changed while proposals were being reviewed.',
                        id,
                        body);
                }
                if (!response.ok) {
                    throw commandError(
                        'HTTP',
                        'Proposal ' + id + ' failed with HTTP '
                            + response.status + '.',
                        id,
                        body);
                }
                return body;
            });
        });
    }

    function commandHeaders(id, action, authority) {
        var headers = {
            'Idempotency-Key': 'proposal-review:'
                + action.toLowerCase() + ':' + id + ':'
                + Date.now() + ':' + (++commandSequence)
        };
        if (authority.branchMissing) {
            headers['If-None-Match'] = '*';
        } else if (authority.etag) {
            headers['If-Match'] = authority.etag;
        } else {
            throw commandError(
                'AUTHORITY',
                'No authoritative Git branch ETag is available.',
                id);
        }
        return headers;
    }

    function commandUrl(id, action) {
        return '/api/architecture/proposals/'
            + encodeURIComponent(id) + '/'
            + action.toLowerCase();
    }

    function readJson(response) {
        var contentType = response.headers.get('Content-Type') || '';
        if (contentType.indexOf('application/json') < 0) {
            return Promise.resolve(null);
        }
        return response.json().catch(function () { return null; });
    }

    function commandError(kind, text, proposalId, body) {
        var error = new Error(text);
        error.kind = kind;
        error.proposalId = proposalId || null;
        error.body = body || null;
        return error;
    }

    function handleFailure(error, action, total, completed) {
        var committed = completed.length;
        if (error.kind === 'PENDING') {
            showStatus('warning', error.message);
            return;
        }
        if (error.kind === 'CONFLICT') {
            showStatus('warning', error.message + ' The proposal list was reloaded.');
            return;
        }
        if (committed > 0) {
            showStatus('warning', committed + ' of ' + total
                + ' proposal decisions were committed before processing stopped. '
                + error.message);
            return;
        }

        var failureKey;
        if (action === 'ACCEPT') {
            failureKey = total === 1
                ? 'browse.proposals.accept.failed'
                : 'browse.proposals.bulk.failed';
        } else if (action === 'REJECT') {
            failureKey = total === 1
                ? 'browse.proposals.reject.failed'
                : 'browse.proposals.bulk.failed';
        } else {
            failureKey = 'browse.proposals.undo.failed';
        }
        showStatus('danger', t(failureKey, error.message));
    }

    function showSuccess(action, count) {
        if (action === 'ACCEPT') {
            showStatus('success', count === 1
                ? t('browse.proposals.accept.success')
                : t('browse.proposals.accepted', count));
        } else if (action === 'REJECT') {
            showStatus('success', count === 1
                ? t('browse.proposals.reject.success')
                : t('browse.proposals.rejected', count));
        } else {
            showStatus('info', t('browse.proposals.reverted', count));
        }
    }

    function selectedProposalIds() {
        var ids = [];
        document.querySelectorAll('.proposal-select:checked')
            .forEach(function (checkbox) {
                ids.push(checkbox.getAttribute('data-id'));
            });
        return normalizeIds(ids);
    }

    function normalizeIds(ids) {
        var unique = new Set();
        (ids || []).forEach(function (value) {
            var id = Number(value);
            if (Number.isSafeInteger(id) && id > 0) {
                unique.add(id);
            }
        });
        return Array.from(unique);
    }

    function setBusy(active) {
        var container = document.getElementById('proposalsTableContainer');
        if (container) {
            container.setAttribute('aria-busy', active ? 'true' : 'false');
        }
        document.querySelectorAll(
            '.proposal-table .btn-accept, .proposal-table .btn-reject, '
                + '#bulkAcceptBtn, #bulkRejectBtn')
            .forEach(function (button) {
                if (active) {
                    button.setAttribute(
                        'data-git-review-disabled',
                        button.disabled ? 'true' : 'false');
                    button.disabled = true;
                } else if (button.hasAttribute('data-git-review-disabled')) {
                    button.disabled = button.getAttribute(
                        'data-git-review-disabled') === 'true';
                    button.removeAttribute('data-git-review-disabled');
                }
            });
    }

    function updateProgress(completed, total, action) {
        var count = document.getElementById('bulkCount');
        if (count) {
            count.textContent = completed + '/' + total;
            return;
        }
        var label = action === 'REVERT' ? 'Reverting' : 'Reviewing';
        showStatus('info', label + ' proposal ' + (completed + 1)
            + ' of ' + total + '…');
    }

    /** Reuse the active filter's existing browser renderer and badge update. */
    function refreshProposalBrowser() {
        var filterIds = [
            'filterPending',
            'filterAll',
            'filterAccepted',
            'filterRejected'
        ];
        var active = null;
        filterIds.some(function (id) {
            var button = document.getElementById(id);
            if (button && button.getAttribute('aria-pressed') === 'true') {
                active = button;
                return true;
            }
            return false;
        });
        if (!active) active = document.getElementById('filterPending');
        if (active) active.click();
        if (window.TaxonomyQuality) {
            window.TaxonomyQuality.loadQualityDashboard();
        }
    }

    function showUndoToast(ids, action) {
        dismissUndoToast();
        undoState = {
            ids: ids.slice(),
            action: action
        };

        var toast = document.createElement('div');
        toast.className = 'undo-toast';
        toast.id = 'undoToast';
        toast.setAttribute('data-git-authoritative', 'true');
        toast.setAttribute('role', 'status');
        toast.setAttribute('aria-live', 'polite');

        var label = document.createElement('span');
        label.textContent = action === 'ACCEPT'
            ? t('browse.proposals.undo.accepted', ids.length)
            : t('browse.proposals.undo.rejected', ids.length);
        toast.appendChild(label);

        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'undo-btn';
        button.id = 'undoBtn';
        button.textContent = t('browse.proposals.undo.btn');
        toast.appendChild(button);
        document.body.appendChild(toast);

        undoTimeout = window.setTimeout(function () {
            if (!toast.parentNode) return;
            toast.style.opacity = '0';
            toast.style.transition = 'opacity '
                + UNDO_FADE_DURATION_MS + 'ms ease';
            window.setTimeout(dismissUndoToast, UNDO_FADE_DURATION_MS);
        }, UNDO_TOAST_DURATION_MS);
    }

    function dismissUndoToast() {
        var toast = document.getElementById('undoToast');
        if (toast && toast.getAttribute('data-git-authoritative') === 'true') {
            toast.remove();
        }
        if (undoTimeout) {
            window.clearTimeout(undoTimeout);
            undoTimeout = null;
        }
        undoState = null;
    }

    function showStatus(type, text) {
        if (window.TaxonomyBrowse && window.TaxonomyBrowse.showStatus) {
            window.TaxonomyBrowse.showStatus(type, text);
        }
    }

    function message(key, fallback) {
        var translated = t(key);
        return translated && translated !== key ? translated : fallback;
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
