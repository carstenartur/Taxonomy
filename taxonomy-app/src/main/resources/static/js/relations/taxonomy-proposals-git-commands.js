/**
 * Git-authoritative command adapter for the existing proposal review queue.
 *
 * The legacy browser remains responsible for rendering. This adapter captures
 * its accept/reject/bulk buttons in the capture phase, reads one lightweight
 * strong branch ETag immediately before the user action, and executes every
 * selected proposal in order. Each successful response advances the expected
 * head for the next command; stale heads and pending projection recovery stop
 * the sequence without hiding already authoritative commits.
 */
(function () {
    'use strict';

    var initialized = false;
    var busy = false;
    var commandSequence = 0;
    var undoTimeout = null;
    var UNDO_TOAST_DURATION_MS = 8000;
    var UNDO_FADE_DURATION_MS = 300;

    function init() {
        if (initialized) return;
        initialized = true;
        document.addEventListener('click', captureProposalCommand, true);

        // Preserve the established inline entry points while making direct
        // keyboard/test invocation use the same Git-first implementation.
        window._proposalAccept = function (id) {
            reviewMany([id], 'ACCEPT', true);
        };
        window._proposalReject = function (id) {
            reviewMany([id], 'REJECT', true);
        };
    }

    function ProposalsApi() {
        if (!window.TaxonomyProposalsApi) {
            throw new Error('Proposal review API client is not available.');
        }
        return window.TaxonomyProposalsApi;
    }

    function captureProposalCommand(event) {
        if (!event.target || typeof event.target.closest !== 'function') return;

        var bulkAccept = event.target.closest('#bulkAcceptBtn');
        if (bulkAccept) {
            intercept(event);
            reviewMany(selectedProposalIds(), 'ACCEPT', true);
            return;
        }

        var bulkReject = event.target.closest('#bulkRejectBtn');
        if (bulkReject) {
            intercept(event);
            reviewMany(selectedProposalIds(), 'REJECT', true);
            return;
        }

        var accept = event.target.closest('.proposal-table .btn-accept');
        if (accept) {
            intercept(event);
            var acceptId = proposalId(accept);
            if (acceptId !== null) reviewMany([acceptId], 'ACCEPT', true);
            return;
        }

        var reject = event.target.closest('.proposal-table .btn-reject');
        if (reject) {
            intercept(event);
            var rejectId = proposalId(reject);
            if (rejectId !== null) reviewMany([rejectId], 'REJECT', true);
        }
    }

    function intercept(event) {
        event.preventDefault();
        event.stopImmediatePropagation();
    }

    function proposalId(button) {
        var row = button.closest('tr');
        var selector = row ? row.querySelector('.proposal-select[data-id]') : null;
        if (selector) {
            var parsed = parseInt(selector.getAttribute('data-id'), 10);
            return Number.isFinite(parsed) ? parsed : null;
        }

        // Defensive fallback for views without a selection checkbox.
        var label = button.getAttribute('aria-label') || '';
        var match = label.match(/(\d+)\s*$/);
        return match ? parseInt(match[1], 10) : null;
    }

    function selectedProposalIds() {
        var ids = [];
        document.querySelectorAll('.proposal-select:checked').forEach(function (checkbox) {
            var id = parseInt(checkbox.getAttribute('data-id'), 10);
            if (Number.isFinite(id)) ids.push(id);
        });
        return ids;
    }

    function reviewMany(ids, action, offerUndo) {
        ids = uniqueIds(ids);
        if (busy || ids.length === 0) return;

        busy = true;
        setProposalBusy(true);
        var operationKey = commandKey('proposal-' + action.toLowerCase());
        var state = {
            etag: null,
            projectedIds: [],
            failedIds: [],
            pending: null,
            stopped: false
        };

        readHead()
            .then(function (etag) {
                state.etag = etag;
                return processNext(ids, action, operationKey, state, 0);
            })
            .then(function () {
                finishReview(state, action, offerUndo);
            })
            .catch(function (error) {
                failReview(error, state);
            });
    }

    function processNext(ids, action, operationKey, state, index) {
        if (state.stopped || index >= ids.length) {
            return Promise.resolve(state);
        }

        var proposalId = ids[index];
        var headers = {
            'If-Match': state.etag,
            'Idempotency-Key': operationKey + ':' + index + ':' + proposalId
        };
        return ProposalsApi().review(proposalId, action, headers)
            .then(function (response) {
                return classifyReviewResponse(response, state.etag, proposalId);
            })
            .then(function (outcome) {
                if (outcome.etag) state.etag = outcome.etag;
                if (outcome.kind === 'PROJECTED') {
                    state.projectedIds.push(proposalId);
                } else if (outcome.kind === 'FAILED') {
                    state.failedIds.push(proposalId);
                } else if (outcome.kind === 'PENDING_RECOVERY') {
                    state.pending = outcome;
                    state.stopped = true;
                }
                return processNext(ids, action, operationKey, state, index + 1);
            })
            .catch(function (error) {
                error.reviewState = state;
                throw error;
            });
    }

    function readHead() {
        return ProposalsApi().readHead()
            .then(function (response) {
                if (response.status === 404) {
                    throw commandError(
                        'BRANCH_MISSING',
                        'The selected architecture branch does not exist.');
                }
                if (!response.ok) {
                    throw commandError(
                        'HTTP',
                        'Could not read the authoritative proposal review head (HTTP '
                            + response.status + ').');
                }
                var etag = response.headers.get('ETag');
                if (!etag) {
                    throw commandError(
                        'MISSING_ETAG',
                        'The proposal review head response did not contain a strong ETag.');
                }
                return etag;
            });
    }

    function classifyReviewResponse(response, currentEtag, proposalId) {
        var nextEtag = response.headers.get('ETag') || currentEtag;
        return readJson(response).then(function (body) {
            if (response.status === 202) {
                return {
                    kind: 'PENDING_RECOVERY',
                    proposalId: proposalId,
                    etag: nextEtag,
                    body: body
                };
            }
            if (response.status === 412) {
                throw commandError(
                    'CONFLICT',
                    'The selected architecture branch changed while proposals were being reviewed.',
                    body,
                    nextEtag);
            }
            if (response.ok) {
                return {
                    kind: 'PROJECTED',
                    proposalId: proposalId,
                    etag: nextEtag,
                    body: body
                };
            }
            if ([400, 404, 409].indexOf(response.status) >= 0) {
                return {
                    kind: 'FAILED',
                    proposalId: proposalId,
                    etag: currentEtag,
                    body: body,
                    status: response.status
                };
            }
            throw commandError(
                'HTTP',
                'Proposal ' + proposalId + ' review failed (HTTP '
                    + response.status + ').',
                body,
                nextEtag);
        });
    }

    function finishReview(state, action, offerUndo) {
        busy = false;
        setProposalBusy(false);
        refreshProposals();

        if (state.pending) {
            var pendingBody = state.pending.body || {};
            var commit = pendingBody.authoritativeCommitId
                ? ' (' + pendingBody.authoritativeCommitId.substring(0, 12) + ')'
                : '';
            showStatus(
                'warning',
                'Git accepted ' + state.projectedIds.length
                    + ' proposal review(s)' + commit
                    + ', but the readable projection still requires recovery.');
            return;
        }

        if (state.failedIds.length > 0) {
            showStatus(
                'warning',
                state.projectedIds.length + ' proposal review(s) completed; '
                    + state.failedIds.length + ' could not be applied.');
        } else {
            showStatus('success', successMessage(action, state.projectedIds.length));
        }

        if (offerUndo && state.projectedIds.length > 0) {
            showUndoToast(state.projectedIds, action);
        }
    }

    function failReview(error, state) {
        busy = false;
        setProposalBusy(false);
        refreshProposals();

        if (error.kind === 'CONFLICT') {
            showStatus(
                'warning',
                error.message + ' The proposal queue has been reloaded.');
            return;
        }
        showStatus(
            'danger',
            message(
                'browse.proposals.bulk.failed',
                'Proposal review failed: ' + error.message,
                error.message));
    }

    function showUndoToast(ids, action) {
        var existing = document.getElementById('undoToast');
        if (existing) existing.remove();
        if (undoTimeout) clearTimeout(undoTimeout);

        var label = action === 'ACCEPT'
            ? message(
                'browse.proposals.undo.accepted',
                ids.length + ' accepted proposal(s)',
                ids.length)
            : message(
                'browse.proposals.undo.rejected',
                ids.length + ' rejected proposal(s)',
                ids.length);
        var toast = document.createElement('div');
        toast.className = 'undo-toast';
        toast.id = 'undoToast';
        toast.innerHTML = '<span>' + escapeHtml(label) + '</span>'
            + '<button class="undo-btn" id="proposalGitUndoBtn">'
            + escapeHtml(message(
                'browse.proposals.undo.btn', 'Undo'))
            + '</button>';
        document.body.appendChild(toast);

        var undo = document.getElementById('proposalGitUndoBtn');
        if (undo) {
            undo.addEventListener('click', function () {
                toast.remove();
                if (undoTimeout) clearTimeout(undoTimeout);
                reviewMany(ids, 'REVERT', false);
            });
        }

        undoTimeout = setTimeout(function () {
            if (!toast.parentNode) return;
            toast.style.opacity = '0';
            toast.style.transition = 'opacity '
                + UNDO_FADE_DURATION_MS + 'ms ease';
            setTimeout(function () {
                if (toast.parentNode) toast.remove();
            }, UNDO_FADE_DURATION_MS);
        }, UNDO_TOAST_DURATION_MS);
    }

    function setProposalBusy(active) {
        var container = document.getElementById('proposalsTableContainer');
        if (container) container.setAttribute('aria-busy', String(active));
        document.querySelectorAll(
            '.proposal-table .btn-accept, .proposal-table .btn-reject, '
                + '#bulkAcceptBtn, #bulkRejectBtn')
            .forEach(function (button) {
                button.disabled = active;
            });
    }

    function refreshProposals() {
        var current = window.TaxonomyState
            ? window.TaxonomyState.currentProposalFilter : 'PENDING';
        var buttonIds = {
            PENDING: 'filterPending',
            ALL: 'filterAll',
            ACCEPTED: 'filterAccepted',
            REJECTED: 'filterRejected'
        };
        var button = document.getElementById(
            buttonIds[current] || buttonIds.PENDING);
        if (button) button.click();
    }

    function successMessage(action, count) {
        if (action === 'ACCEPT') {
            return message(
                'browse.proposals.accepted',
                count + ' proposal(s) accepted.',
                count);
        }
        if (action === 'REJECT') {
            return message(
                'browse.proposals.rejected',
                count + ' proposal(s) rejected.',
                count);
        }
        return message(
            'browse.proposals.reverted',
            count + ' proposal review(s) reverted.',
            count);
    }

    function commandKey(prefix) {
        commandSequence++;
        if (window.crypto && typeof window.crypto.randomUUID === 'function') {
            return prefix + ':' + window.crypto.randomUUID();
        }
        return prefix + ':' + Date.now() + ':' + commandSequence;
    }

    function uniqueIds(ids) {
        var seen = Object.create(null);
        return (ids || []).filter(function (id) {
            var parsed = Number(id);
            if (!Number.isSafeInteger(parsed) || parsed <= 0 || seen[parsed]) {
                return false;
            }
            seen[parsed] = true;
            return true;
        }).map(Number);
    }

    function readJson(response) {
        var contentType = response.headers.get('Content-Type') || '';
        if (contentType.indexOf('application/json') < 0) {
            return Promise.resolve(null);
        }
        return response.json().catch(function () { return null; });
    }

    function commandError(kind, text, body, etag) {
        var error = new Error(text);
        error.kind = kind;
        error.body = body || null;
        error.etag = etag || null;
        return error;
    }

    function showStatus(type, text) {
        if (window.TaxonomyBrowse
                && typeof window.TaxonomyBrowse.showStatus === 'function') {
            window.TaxonomyBrowse.showStatus(type, text);
            return;
        }
        console[type === 'danger' ? 'error' : 'warn']('[Taxonomy] ' + text);
    }

    function message(key, fallback) {
        var args = Array.prototype.slice.call(arguments, 2);
        if (!window.TaxonomyI18n || !window.TaxonomyI18n.t) {
            return fallback;
        }
        var translated = window.TaxonomyI18n.t.apply(
            null, [key].concat(args));
        return translated && translated !== key ? translated : fallback;
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
