/**
 * Versions — History, Undo, Restore, Save Version.
 *
 * <p>Provides a user-friendly interface over the JGit-backed DSL versioning
 * system. All user-visible strings are resolved via TaxonomyI18n.
 */
window.TaxonomyVersions = (function () {
    'use strict';
    var t = TaxonomyI18n.t;

    // ── Constants ─────────────────────────────────────────────────

    var MAX_COMMIT_MESSAGE_DISPLAY = 50;
    var DEFAULT_AUTHOR = 'user';

    // ── Selectors (resolved lazily) ─────────────────────────────────

    function el(id) { return document.getElementById(id); }

    // ── State ───────────────────────────────────────────────────────

    var currentBranch = 'draft';

    // ── Initialization ──────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        var branchSelect = el('versionsBranchSelect');
        if (branchSelect) {
            loadBranches(branchSelect);
            branchSelect.addEventListener('change', function () {
                currentBranch = this.value;
                loadTimeline();
            });
        }

        var undoBtn = el('versionsUndoBtn');
        if (undoBtn) {
            undoBtn.addEventListener('click', undoLast);
        }

        var refreshBtn = el('versionsRefreshBtn');
        if (refreshBtn) {
            refreshBtn.addEventListener('click', function () { loadTimeline(); });
        }

        var saveBtn = el('versionsSaveBtn');
        if (saveBtn) {
            saveBtn.addEventListener('click', saveVersion);
        }

        document.querySelectorAll('[data-versions-tab]').forEach(function (link) {
            link.addEventListener('click', function (e) {
                e.preventDefault();
                activateSubTab(this.getAttribute('data-versions-tab'));
            });
        });
    });

    // ── Sub-tab switching ───────────────────────────────────────────

    function activateSubTab(tabName) {
        document.querySelectorAll('[data-versions-tab]').forEach(function (link) {
            link.classList.toggle('active', link.getAttribute('data-versions-tab') === tabName);
        });
        document.querySelectorAll('.versions-sub-pane').forEach(function (pane) {
            var id = pane.id.replace('versions-', '');
            if (id === tabName) {
                pane.classList.remove('d-none');
            } else {
                pane.classList.add('d-none');
            }
        });
        if (tabName === 'variants' && window.TaxonomyVariants) {
            window.TaxonomyVariants.refresh();
        }
    }

    // ── Load branches ───────────────────────────────────────────────

    function loadBranches(selectEl) {
        fetch('/api/git/branches')
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var branches = data.branches || data || [];
                selectEl.innerHTML = '';
                branches.forEach(function (b) {
                    var opt = document.createElement('option');
                    opt.value = b;
                    opt.textContent = TaxonomyI18n.formatBranch(b);
                    if (b === currentBranch) opt.selected = true;
                    selectEl.appendChild(opt);
                });
                if (branches.length > 0 && !branches.includes(currentBranch)) {
                    currentBranch = branches[0];
                    selectEl.value = currentBranch;
                }
            })
            .catch(function () {});
    }

    // ── Timeline ────────────────────────────────────────────────────

    function loadTimeline(retryCount) {
        retryCount = retryCount || 0;
        var MAX_RETRIES = 2;

        var container = el('versionsTimeline');
        if (!container) return;

        if (retryCount === 0) {
            var branchSelect = el('versionsBranchSelect');
            if (branchSelect) loadBranches(branchSelect);

            container.setAttribute('data-state', 'loading');
            container.innerHTML = '<div class="text-muted small">' + escapeHtml(t('versions.history.loading')) + '</div>';
        }

        fetch('/api/dsl/history?branch=' + encodeURIComponent(currentBranch))
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (data) {
                var commits = data.commits || [];
                if (commits.length === 0) {
                    container.setAttribute('data-state', 'empty');
                    container.innerHTML = '<div class="text-muted small p-2">' + escapeHtml(t('versions.history.none')) + '</div>';
                    updateUndoInfo(null);
                    return;
                }

                updateUndoInfo(commits[0]);

                var html = '<div class="timeline">';
                commits.forEach(function (commit, idx) {
                    html += renderTimelineEntry(commit, idx === 0);
                });
                html += '</div>';
                container.innerHTML = html;
                container.setAttribute('data-state', 'loaded');

                container.querySelectorAll('[data-action]').forEach(function (btn) {
                    btn.addEventListener('click', handleTimelineAction);
                });
            })
            .catch(function (err) {
                if (retryCount < MAX_RETRIES) {
                    var delay = Math.pow(2, retryCount) * 1000;
                    setTimeout(function () { loadTimeline(retryCount + 1); }, delay);
                } else {
                    container.setAttribute('data-state', 'error');
                    container.innerHTML = '<div class="text-danger small p-2">' + escapeHtml(t('versions.history.load.failed', err.message)) + '</div>';
                }
            });
    }

    function renderTimelineEntry(commit, isLatest) {
        var ts = commit.timestamp ? formatTimestamp(commit.timestamp) : '';
        var sha = commit.commitId ? commit.commitId.substring(0, 7) : '';
        var author = commit.author || t('versions.entry.unknown.author');
        var message = commit.message || t('versions.entry.no.message');

        // Detect restore/revert commits
        var isRestore = /restore|wiederherst/i.test(message);
        var isRevert = /revert|r\u00FCckg\u00E4ngig/i.test(message);
        var dotClass = isLatest ? 'timeline-dot-current' : (isRestore || isRevert) ? 'timeline-dot-restore' : 'timeline-dot';

        var html = '<div class="timeline-entry mb-3 ps-4 position-relative">' +
            '<span class="' + dotClass + ' position-absolute" style="left:0;top:6px;width:10px;height:10px;border-radius:50%;display:inline-block;"></span>' +
            '<div class="d-flex justify-content-between align-items-start">' +
            '<div>';

        html += '<strong class="small">' + escapeHtml(message) + '</strong>';

        // Restore/revert marker
        if (isRestore) {
            html += ' <span class="restore-marker">\uD83D\uDD04 ' + escapeHtml(t('versions.entry.marker.restore')) + '</span>';
        } else if (isRevert) {
            html += ' <span class="restore-marker">\uD83D\uDD04 ' + escapeHtml(t('versions.entry.marker.revert')) + '</span>';
        }

        html += '<div class="text-muted" style="font-size:0.75rem;">' +
            ts + ' &mdash; ' + escapeHtml(author) +
            ' <code class="ms-1">' + sha + '</code>' +
            '</div>' +
            '</div>' +
            '<div class="d-flex gap-1">' +
            '<button class="btn btn-outline-secondary btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="view" data-commit="' + escapeAttr(commit.commitId) + '" title="' + escapeAttr(t('versions.entry.view.title')) + '">' +
            '\uD83D\uDC41 ' + escapeHtml(t('versions.entry.view')) + '</button>' +
            '<button class="btn btn-outline-info btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="compare" data-commit="' + escapeAttr(commit.commitId) + '" title="' + escapeAttr(t('versions.entry.compare.title')) + '">' +
            '\uD83D\uDD0D ' + escapeHtml(t('versions.entry.compare')) + '</button>' +
            '<button class="btn btn-outline-warning btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="restore" data-commit="' + escapeAttr(commit.commitId) + '" title="' + escapeAttr(t('versions.entry.restore.title')) + '">' +
            '\u21A9 ' + escapeHtml(t('versions.entry.restore')) + '</button>' +
            '<button class="btn btn-outline-danger btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="revert" data-commit="' + escapeAttr(commit.commitId) + '" title="' + escapeAttr(t('versions.entry.revert.title')) + '">' +
            '\u274C ' + escapeHtml(t('versions.entry.revert')) + '</button>' +
            '<button class="btn btn-outline-success btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="variant" data-commit="' + escapeAttr(commit.commitId) + '" data-branch="' + escapeAttr(currentBranch) + '" title="' + escapeAttr(t('versions.entry.variant.title')) + '">' +
            '\uD83C\uDF3F ' + escapeHtml(t('versions.entry.variant')) + '</button>' +
            '</div>' +
            '</div>' +
            '</div>';

        return html;
    }

    // ── Timeline actions ────────────────────────────────────────────

    function handleTimelineAction(e) {
        var btn = e.currentTarget;
        var action = btn.getAttribute('data-action');
        var commitId = btn.getAttribute('data-commit');

        switch (action) {
            case 'view':
                viewVersion(commitId);
                break;
            case 'compare':
                compareVersion(commitId);
                break;
            case 'restore':
                restoreVersion(commitId);
                break;
            case 'revert':
                revertVersion(commitId);
                break;
            case 'variant':
                createVariantFromVersion(commitId, btn.getAttribute('data-branch'));
                break;
        }
    }

    function viewVersion(commitId) {
        fetch('/api/dsl/git/commit/' + encodeURIComponent(commitId))
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.dslText) {
                    showModal('Version ' + commitId.substring(0, 7),
                        '<pre class="bg-light p-3 rounded" style="max-height:60vh;overflow:auto;font-size:0.8rem;white-space:pre-wrap;">' +
                        escapeHtml(data.dslText) + '</pre>');
                }
            })
            .catch(function (err) {
                showModal(t('versions.error'), '<div class="text-danger">' + escapeHtml(err.message) + '</div>');
            });
    }

    var comparisonRequest = 0;

    async function compareVersion(commitId) {
        var request = ++comparisonRequest;
        var branch = currentBranch;
        var modal = showModal(t('versions.compare'), '<p class="text-muted">' + escapeHtml(t('compare.loading')) + '</p>');
        modal.addEventListener('hide.bs.modal', function () {
            if (request === comparisonRequest) comparisonRequest++;
        });
        try {
            var state = await window.TaxonomyApiClient.getJson('/api/git/state?branch=' + encodeURIComponent(branch));
            if (!state.headCommit) throw new Error('No saved version');
            var diff = await window.TaxonomyApiClient.getJson('/api/dsl/diff/' + encodeURIComponent(commitId) + '/' + encodeURIComponent(state.headCommit));
            var comparison = window.TaxonomyContextCompare.fromDocumentDiff(diff,
                { branch: branch, commitId: commitId }, { branch: branch, commitId: state.headCommit });
            if (request !== comparisonRequest || !modal.isConnected) return;
            var body = modal.querySelector('.modal-body');
            body.id = 'versionComparisonResults';
            window.TaxonomyContextCompare.renderComparison(body.id, comparison);
        } catch (error) {
            if (request === comparisonRequest && modal.isConnected) {
                modal.querySelector('.modal-body').innerHTML = '<p class="text-danger">' + escapeHtml(t('compare.failed')) + '</p>';
            }
        }
    }

    /**
     * Restore with preview modal.
     */
    async function restoreVersion(commitId) {
        var request = ++comparisonRequest;
        var branch = currentBranch;
        var modal = showConfirmModal(t('versions.restore.confirm.title'),
            '<p class="text-muted" role="status">' + escapeHtml(t('compare.loading')) + '</p>',
            t('versions.restore.confirm.btn'), 'btn-warning', function () { executeRestore(commitId, branch); });
        var confirm = modal.querySelector('#versionsConfirmBtn');
        confirm.disabled = true;
        modal.addEventListener('hide.bs.modal', function () {
            if (request === comparisonRequest) comparisonRequest++;
        });
        try {
            var state = await window.TaxonomyApiClient.getJson('/api/git/state?branch=' + encodeURIComponent(branch));
            if (!state.headCommit) throw new Error('No saved version');
            // Preview the operation the user is about to perform: HEAD -> target.
            var diff = await window.TaxonomyApiClient.getJson('/api/dsl/diff/' + encodeURIComponent(state.headCommit) + '/' + encodeURIComponent(commitId));
            var comparison = window.TaxonomyContextCompare.fromDocumentDiff(diff,
                { branch: branch, commitId: state.headCommit }, { branch: branch, commitId: commitId });
            if (request !== comparisonRequest || !modal.isConnected) return;
            modal.querySelector('.modal-body').innerHTML = restorePreviewHtml(commitId, comparison);
            confirm.disabled = false;
        } catch (error) {
            // A failed preview is not permission to perform a blind restore.
            if (request === comparisonRequest && modal.isConnected) {
                modal.querySelector('.modal-body').innerHTML = '<p class="text-danger" role="alert">' + escapeHtml(t('compare.failed')) + '</p>';
            }
        }
    }

    /**
     * Render a validated, correctly directed restore preview.
     */
    function restorePreviewHtml(commitId, diff) {
        var shortSha = escapeHtml(commitId.substring(0, 7));
        var bodyHtml = '<p>' + t('versions.restore.confirm.body', shortSha) + '</p>';
        bodyHtml += '<p class="small text-muted">' + escapeHtml(t('versions.restore.confirm.detail')) + '</p>';

        if (diff) {
            var addedCount = diff.summary.elementsAdded + diff.summary.relationsAdded;
            var removedCount = diff.summary.elementsRemoved + diff.summary.relationsRemoved;
            var changedCount = diff.summary.elementsChanged + diff.summary.relationsChanged;
            var total = addedCount + removedCount + changedCount;

            if (total > 0) {
                bodyHtml += '<div class="restore-preview">';
                bodyHtml += '<strong>' + escapeHtml(t('versions.restore.preview')) + '</strong>';
                bodyHtml += '<div class="mt-1">';
                if (addedCount > 0) bodyHtml += '<span class="text-success me-2">' + escapeHtml(t('versions.restore.preview.added', addedCount)) + '</span>';
                if (removedCount > 0) bodyHtml += '<span class="text-danger me-2">' + escapeHtml(t('versions.restore.preview.removed', removedCount)) + '</span>';
                if (changedCount > 0) bodyHtml += '<span class="text-warning me-2">' + escapeHtml(t('versions.restore.preview.changed', changedCount)) + '</span>';
                bodyHtml += '</div></div>';
            } else {
                bodyHtml += '<div class="restore-preview text-muted">' + escapeHtml(t('versions.restore.preview.none')) + '</div>';
            }
        }

        return bodyHtml;
    }

    function executeRestore(commitId, branch) {
        fetch('/api/dsl/restore?commitId=' + encodeURIComponent(commitId) +
            '&branch=' + encodeURIComponent(branch), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError(t('versions.restore.failed'), data.error);
                    } else {
                        alert(t('versions.restore.failed') + ': ' + data.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess(t('versions.restore.success'),
                            t('versions.restore.success.detail', commitId.substring(0, 7)));
                    }
                    loadTimeline();
                    refreshGitStatus();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError(t('versions.restore.failed'), err.message);
                } else {
                    alert(t('versions.restore.failed') + ': ' + err.message);
                }
            });
    }

    /**
     * Revert with confirmation modal.
     */
    function revertVersion(commitId) {
        var shortSha = escapeHtml(commitId.substring(0, 7));
        var bodyHtml = '<p>' + t('versions.revert.confirm.body', shortSha) + '</p>';
        bodyHtml += '<p class="small text-muted">' + escapeHtml(t('versions.revert.confirm.detail')) + '</p>';

        showConfirmModal(t('versions.revert.confirm.title'), bodyHtml, t('versions.revert.confirm.btn'), 'btn-danger', function () {
            executeRevert(commitId);
        });
    }

    function executeRevert(commitId) {
        fetch('/api/dsl/revert?commitId=' + encodeURIComponent(commitId) +
            '&branch=' + encodeURIComponent(currentBranch), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError(t('versions.revert.failed'), data.error);
                    } else {
                        alert(t('versions.revert.failed') + ': ' + data.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess(t('versions.revert.success'),
                            t('versions.revert.success.detail', commitId.substring(0, 7)));
                    }
                    loadTimeline();
                    refreshGitStatus();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError(t('versions.revert.failed'), err.message);
                } else {
                    alert(t('versions.revert.failed') + ': ' + err.message);
                }
            });
    }

    // ── Create variant from version ──────────────────────────────────

    function createVariantFromVersion(commitId, branch) {
        fetch('/api/context/open?branch=' + encodeURIComponent(branch || currentBranch)
            + '&commitId=' + encodeURIComponent(commitId)
            + '&readOnly=false', { method: 'POST' })
            .then(function () {
                if (window.TaxonomyContextBar) {
                    window.TaxonomyContextBar.fetchAndRender('contextBar');
                    window.TaxonomyContextBar.showVariantDialog();
                }
            });
    }

    // ── Undo ────────────────────────────────────────────────────────

    function undoLast() {
        var branchName = TaxonomyI18n.formatBranch(currentBranch);
        showConfirmModal(
            t('versions.undo.confirm.title'),
            t('versions.undo.confirm.body', escapeHtml(branchName))
            + '<p class="small text-muted">' + escapeHtml(t('versions.undo.confirm.detail')) + '</p>',
            t('versions.undo.confirm.btn'),
            'btn-warning',
            function () {
                fetch('/api/dsl/undo?branch=' + encodeURIComponent(currentBranch), { method: 'POST' })
                    .then(function (r) { return r.json(); })
                    .then(function (data) {
                        if (data.error) {
                            if (window.TaxonomyOperationResult) {
                                window.TaxonomyOperationResult.showError(t('versions.undo.failed'), data.error);
                            } else {
                                alert(t('versions.undo.failed') + ': ' + data.error);
                            }
                        } else {
                            loadTimeline();
                            refreshGitStatus();
                        }
                    })
                    .catch(function (err) {
                        if (window.TaxonomyOperationResult) {
                            window.TaxonomyOperationResult.showError(t('versions.undo.failed'), err.message);
                        } else {
                            alert(t('versions.undo.failed') + ': ' + err.message);
                        }
                    });
            }
        );
    }

    function updateUndoInfo(latestCommit) {
        var info = el('versionsUndoInfo');
        if (!info) return;
        if (latestCommit) {
            info.textContent = t('versions.undo.last', (latestCommit.message || '').substring(0, MAX_COMMIT_MESSAGE_DISPLAY));
        } else {
            info.textContent = '';
        }
    }

    // ── Save version ────────────────────────────────────────────────

    function saveVersion() {
        var titleEl = el('versionTitle');
        var descEl = el('versionDescription');
        var statusEl = el('versionsSaveStatus');
        var title = titleEl ? titleEl.value.trim() : '';
        var desc = descEl ? descEl.value.trim() : '';

        if (!title) {
            if (statusEl) {
                statusEl.textContent = t('versions.save.error.title');
                statusEl.className = 'ms-2 small text-danger';
            }
            return;
        }

        var message = title + (desc ? '\n\n' + desc : '');

        fetch('/api/dsl/git/head?branch=' + encodeURIComponent(currentBranch))
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (!data.dslText) throw new Error(t('versions.save.error.no.content'));
                return fetch('/api/dsl/commit?branch=' + encodeURIComponent(currentBranch) +
                    '&author=' + encodeURIComponent(DEFAULT_AUTHOR) + '&message=' + encodeURIComponent(message), {
                    method: 'POST',
                    headers: { 'Content-Type': 'text/plain' },
                    body: data.dslText
                });
            })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.error || result.valid === false) {
                    if (statusEl) {
                        statusEl.textContent = t('versions.save.failed', (result.errors || [result.error]).join(', '));
                        statusEl.className = 'ms-2 small text-danger';
                    }
                } else {
                    if (statusEl) {
                        statusEl.textContent = t('versions.save.success', (result.commitId || '').substring(0, 7));
                        statusEl.className = 'ms-2 small text-success';
                    }
                    if (titleEl) titleEl.value = '';
                    if (descEl) descEl.value = '';
                    loadTimeline();
                    refreshGitStatus();
                }
            })
            .catch(function (err) {
                if (statusEl) {
                    statusEl.textContent = t('versions.save.failed', err.message);
                    statusEl.className = 'ms-2 small text-danger';
                }
            });
    }

    // ── Confirm modal helper ──────────────────────────────────────────

    function showConfirmModal(title, bodyHtml, confirmLabel, confirmBtnClass, onConfirm) {
        var existing = document.getElementById('versionsConfirmModal');
        if (existing) existing.remove();

        var modalHtml =
            '<div class="modal fade" id="versionsConfirmModal" tabindex="-1">' +
            '<div class="modal-dialog">' +
            '<div class="modal-content">' +
            '<div class="modal-header">' +
            '<h5 class="modal-title">' + escapeHtml(title) + '</h5>' +
            '<button type="button" class="btn-close" data-bs-dismiss="modal"></button>' +
            '</div>' +
            '<div class="modal-body">' + bodyHtml + '</div>' +
            '<div class="modal-footer">' +
            '<button type="button" class="btn btn-secondary btn-sm" data-bs-dismiss="modal">' + escapeHtml(t('dialog.cancel')) + '</button>' +
            '<button type="button" class="btn ' + confirmBtnClass + ' btn-sm" id="versionsConfirmBtn">' + escapeHtml(confirmLabel) + '</button>' +
            '</div></div></div></div>';

        document.body.insertAdjacentHTML('beforeend', modalHtml);
        var modalEl = document.getElementById('versionsConfirmModal');
        var modal = new bootstrap.Modal(modalEl);

        document.getElementById('versionsConfirmBtn').addEventListener('click', function () {
            if (this.disabled) return;
            this.disabled = true;
            modal.hide();
            if (onConfirm) onConfirm();
        });

        modal.show();
        modalEl.addEventListener('hidden.bs.modal', function () { modal.dispose(); modalEl.remove(); });
        return modalEl;
    }

    // ── Modal helper ────────────────────────────────────────────────

    function showModal(title, bodyHtml) {
        var existing = document.getElementById('versionsModal');
        if (existing) existing.remove();

        var modalHtml =
            '<div class="modal fade" id="versionsModal" tabindex="-1">' +
            '<div class="modal-dialog modal-lg">' +
            '<div class="modal-content">' +
            '<div class="modal-header">' +
            '<h5 class="modal-title">' + escapeHtml(title) + '</h5>' +
            '<button type="button" class="btn-close" data-bs-dismiss="modal"></button>' +
            '</div>' +
            '<div class="modal-body">' + bodyHtml + '</div>' +
            '<div class="modal-footer">' +
            '<button type="button" class="btn btn-secondary btn-sm" data-bs-dismiss="modal">' + escapeHtml(t('dialog.close')) + '</button>' +
            '</div></div></div></div>';

        document.body.insertAdjacentHTML('beforeend', modalHtml);
        var modalEl = document.getElementById('versionsModal');
        var modal = new bootstrap.Modal(modalEl);
        modal.show();
        modalEl.addEventListener('hidden.bs.modal', function () { modal.dispose(); modalEl.remove(); });
        return modalEl;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    function refreshGitStatus() {
        if (window.TaxonomyGitStatus && window.TaxonomyGitStatus.refresh) {
            window.TaxonomyGitStatus.refresh();
        }
    }

    function formatTimestamp(ts) {
        try {
            var d = new Date(ts);
            var now = new Date();
            var isToday = d.toDateString() === now.toDateString();
            var timeStr = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            if (isToday) {
                return t('versions.timestamp.today', timeStr);
            }
            return d.toLocaleDateString() + ' ' + timeStr;
        } catch (e) {
            return String(ts);
        }
    }

    var escapeHtml = TaxonomyUtils.escapeHtml;

    function escapeAttr(s) {
        return escapeHtml(s);
    }

    // ── Public API ──────────────────────────────────────────────────

    return {
        loadTimeline: loadTimeline,
        undoLast: undoLast,
        saveVersion: saveVersion,
        restoreVersion: restoreVersion
    };
}());
