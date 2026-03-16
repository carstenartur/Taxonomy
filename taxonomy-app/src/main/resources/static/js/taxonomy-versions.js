/**
 * Versionen — Verlauf, R\u00FCckg\u00E4ngig, Wiederherstellen, Version speichern.
 *
 * <p>Provides a user-friendly interface over the JGit-backed DSL versioning
 * system. Uses i18n-aware labels for non-developer audiences: "Version",
 * "R\u00FCckg\u00E4ngig", "Wiederherstellen", and "Speichern".
 */
window.TaxonomyVersions = (function () {
    'use strict';
    var t = TaxonomyI18n.t;

    // \u2500\u2500 Constants \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    var MAX_COMMIT_MESSAGE_DISPLAY = 50;
    var DEFAULT_AUTHOR = 'user';

    // \u2500\u2500 Selectors (resolved lazily) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    function el(id) { return document.getElementById(id); }

    // \u2500\u2500 State \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    var currentBranch = 'draft';

    // \u2500\u2500 Initialization \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

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

    // \u2500\u2500 Sub-tab switching \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

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

    // \u2500\u2500 Load branches \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    function loadBranches(selectEl) {
        fetch('/api/git/branches')
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var branches = data.branches || data || [];
                selectEl.innerHTML = '';
                branches.forEach(function (b) {
                    var opt = document.createElement('option');
                    opt.value = b;
                    opt.textContent = b;
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

    // \u2500\u2500 Timeline \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    function loadTimeline() {
        var container = el('versionsTimeline');
        if (!container) return;

        var branchSelect = el('versionsBranchSelect');
        if (branchSelect) loadBranches(branchSelect);

        container.innerHTML = '<div class="text-muted small">' + t('versions.loading') + '</div>';

        fetch('/api/dsl/history?branch=' + encodeURIComponent(currentBranch))
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (data) {
                var commits = data.commits || [];
                if (commits.length === 0) {
                    container.innerHTML = '<div class="text-muted small p-2">' + t('versions.empty') + '</div>';
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

                container.querySelectorAll('[data-action]').forEach(function (btn) {
                    btn.addEventListener('click', handleTimelineAction);
                });
            })
            .catch(function (err) {
                container.innerHTML = '<div class="text-danger small p-2">' + t('versions.load_error') + escapeHtml(err.message) + '</div>';
            });
    }

    function renderTimelineEntry(commit, isLatest) {
        var ts = commit.timestamp ? formatTimestamp(commit.timestamp) : '';
        var sha = commit.commitId ? commit.commitId.substring(0, 7) : '';
        var author = commit.author || t('versions.author.unknown');
        var message = commit.message || t('versions.message.none');

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
            html += ' <span class="restore-marker">' + t('versions.restore_marker') + '</span>';
        } else if (isRevert) {
            html += ' <span class="restore-marker">' + t('versions.revert_marker') + '</span>';
        }

        html += '<div class="text-muted" style="font-size:0.75rem;">' +
            ts + ' &mdash; ' + escapeHtml(author) +
            ' <code class="ms-1">' + sha + '</code>' +
            '</div>' +
            '</div>' +
            '<div class="d-flex gap-1">' +
            '<button class="btn btn-outline-secondary btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="view" data-commit="' + escapeAttr(commit.commitId) + '" ' + 'title="' + escapeAttr(t('versions.btn.view.title')) + '">' +
            t('versions.btn.view') + '</button>' +
            '<button class="btn btn-outline-info btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="compare" data-commit="' + escapeAttr(commit.commitId) + '" ' + 'title="' + escapeAttr(t('versions.btn.compare.title')) + '">' +
            t('versions.btn.compare') + '</button>' +
            '<button class="btn btn-outline-warning btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="restore" data-commit="' + escapeAttr(commit.commitId) + '" ' + 'title="' + escapeAttr(t('versions.btn.restore.title')) + '">' +
            t('versions.btn.restore') + '</button>' +
            '<button class="btn btn-outline-danger btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="revert" data-commit="' + escapeAttr(commit.commitId) + '" ' + 'title="' + escapeAttr(t('versions.btn.revert.title')) + '">' +
            t('versions.btn.revert') + '</button>' +
            '<button class="btn btn-outline-success btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="variant" data-commit="' + escapeAttr(commit.commitId) + '" data-branch="' + escapeAttr(currentBranch) + '" ' + 'title="' + escapeAttr(t('versions.btn.variant.title')) + '">' +
            t('versions.btn.variant') + '</button>' +
            '</div>' +
            '</div>' +
            '</div>';

        return html;
    }

    // \u2500\u2500 Timeline actions \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

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
                showModal(t('common.error'), '<div class="text-danger">' + escapeHtml(err.message) + '</div>');
            });
    }

    function compareVersion(commitId) {
        fetch('/api/git/state?branch=' + encodeURIComponent(currentBranch))
            .then(function (r) { return r.json(); })
            .then(function (state) {
                var headCommit = state.headCommit;
                if (!headCommit || headCommit === commitId) {
                    showModal(t('versions.compare.title', commitId.substring(0, 7)), '<div class="text-muted">' + t('versions.compare.same') + '</div>');
                    return;
                }
                return fetch('/api/dsl/diff/' + encodeURIComponent(commitId) + '/' + encodeURIComponent(headCommit));
            })
            .then(function (r) { return r ? r.json() : null; })
            .then(function (diff) {
                if (!diff) return;
                var html = '<div class="small">';
                html += '<div class="compare-summary-card mb-3">';
                html += '<div class="compare-title">' + t('versions.compare.title', commitId.substring(0, 7)) + '</div>';
                html += '<div class="compare-stats">';
                if (diff.added && diff.added.length > 0) {
                    html += '<span class="compare-stat text-success">' + t('versions.compare.added', diff.added.length) + '</span>';
                }
                if (diff.removed && diff.removed.length > 0) {
                    html += '<span class="compare-stat text-danger">' + t('versions.compare.removed', diff.removed.length) + '</span>';
                }
                if (diff.changed && diff.changed.length > 0) {
                    html += '<span class="compare-stat text-warning">' + t('versions.compare.changed', diff.changed.length) + '</span>';
                }
                html += '</div></div>';

                if (diff.added && diff.added.length > 0) {
                    html += '<h6 class="text-success">' + t('versions.compare.h.added', diff.added.length) + '</h6>';
                    html += '<ul>' + diff.added.map(function (a) { return '<li>' + escapeHtml(a.code || a.id || JSON.stringify(a)) + '</li>'; }).join('') + '</ul>';
                }
                if (diff.removed && diff.removed.length > 0) {
                    html += '<h6 class="text-danger">' + t('versions.compare.h.removed', diff.removed.length) + '</h6>';
                    html += '<ul>' + diff.removed.map(function (r) { return '<li>' + escapeHtml(r.code || r.id || JSON.stringify(r)) + '</li>'; }).join('') + '</ul>';
                }
                if (diff.changed && diff.changed.length > 0) {
                    html += '<h6 class="text-warning">' + t('versions.compare.h.changed', diff.changed.length) + '</h6>';
                    html += '<ul>' + diff.changed.map(function (c) { return '<li>' + escapeHtml(c.code || c.id || JSON.stringify(c)) + '</li>'; }).join('') + '</ul>';
                }
                if ((diff.totalChanges || 0) === 0) {
                    html += '<p class="text-muted">' + t('versions.compare.no_diff') + '</p>';
                }
                html += '</div>';
                showModal(t('versions.compare.title', commitId.substring(0, 7)), html);
            })
            .catch(function (err) {
                showModal(t('common.error'), '<div class="text-danger">' + escapeHtml(err.message) + '</div>');
            });
    }

    /**
     * AP 4: Restore with preview modal.
     */
    function restoreVersion(commitId) {
        // Load diff preview before confirming
        fetch('/api/git/state?branch=' + encodeURIComponent(currentBranch))
            .then(function (r) { return r.json(); })
            .then(function (state) {
                var headCommit = state.headCommit;
                if (!headCommit) {
                    executeRestore(commitId);
                    return;
                }
                return fetch('/api/dsl/diff/' + encodeURIComponent(commitId) + '/' + encodeURIComponent(headCommit))
                    .then(function (r) { return r.json(); })
                    .then(function (diff) {
                        showRestoreConfirmModal(commitId, diff);
                    });
            })
            .catch(function () {
                // Fallback without preview
                showRestoreConfirmModal(commitId, null);
            });
    }

    /**
     * Show restore confirmation modal with optional preview.
     */
    function showRestoreConfirmModal(commitId, diff) {
        var bodyHtml = '<p>' + t('versions.restore.confirm', escapeHtml(commitId.substring(0, 7))) + '</p>';
        bodyHtml += '<p class="small text-muted">' + t('versions.restore.explain') + '</p>';

        if (diff) {
            var total = (diff.totalChanges || 0);
            var addedCount = diff.added ? diff.added.length : 0;
            var removedCount = diff.removed ? diff.removed.length : 0;
            var changedCount = diff.changed ? diff.changed.length : 0;

            if (total > 0) {
                bodyHtml += '<div class="restore-preview">';
                bodyHtml += '<strong>' + t('versions.restore.preview') + '</strong>';
                bodyHtml += '<div class="mt-1">';
                if (addedCount > 0) bodyHtml += '<span class="text-success me-2">' + t('versions.compare.added', addedCount) + '</span>';
                if (removedCount > 0) bodyHtml += '<span class="text-danger me-2">' + t('versions.compare.removed', removedCount) + '</span>';
                if (changedCount > 0) bodyHtml += '<span class="text-warning me-2">' + t('versions.compare.changed', changedCount) + '</span>';
                bodyHtml += '</div></div>';
            } else {
                bodyHtml += '<div class="restore-preview text-muted">' + t('versions.restore.preview_no_diff') + '</div>';
            }
        }

        showConfirmModal(t('versions.restore.title'), bodyHtml, t('versions.restore.btn'), 'btn-warning', function () {
            executeRestore(commitId);
        });
    }

    function executeRestore(commitId) {
        fetch('/api/dsl/restore?commitId=' + encodeURIComponent(commitId) +
            '&branch=' + encodeURIComponent(currentBranch), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError(t('versions.restore.error'), data.error);
                    } else {
                        alert(t('versions.restore.error') + ': ' + data.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess(t('versions.restore.success.title'),
                            t('versions.restore.success.msg', commitId.substring(0, 7)));
                    }
                    loadTimeline();
                    refreshGitStatus();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError(t('versions.restore.error'), err.message);
                } else {
                    alert(t('versions.restore.error') + ': ' + err.message);
                }
            });
    }

    /**
     * AP 4: Revert with confirmation modal.
     */
    function revertVersion(commitId) {
        var bodyHtml = '<p>' + t('versions.revert.confirm', escapeHtml(commitId.substring(0, 7))) + '</p>';
        bodyHtml += '<p class="small text-muted">' + t('versions.revert.explain') + '</p>';

        showConfirmModal(t('versions.revert.title'), bodyHtml, t('versions.revert.btn'), 'btn-danger', function () {
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
                        window.TaxonomyOperationResult.showError(t('versions.revert.error'), data.error);
                    } else {
                        alert(t('versions.revert.error') + ': ' + data.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess(t('versions.revert.success.title'),
                            t('versions.revert.success.msg', commitId.substring(0, 7)));
                    }
                    loadTimeline();
                    refreshGitStatus();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError(t('versions.revert.error'), err.message);
                } else {
                    alert(t('versions.revert.error') + ': ' + err.message);
                }
            });
    }

    // \u2500\u2500 Create variant from version \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

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

    // \u2500\u2500 Undo \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    function undoLast() {
        var branchName = TaxonomyI18n.formatBranch(currentBranch);
        showConfirmModal(
            t('versions.undo.title'),
            '<p>' + t('versions.undo.confirm', escapeHtml(branchName)) + '</p>'
            + '<p class="small text-muted">' + t('versions.undo.explain') + '</p>',
            t('versions.undo.btn'),
            'btn-warning',
            function () {
                fetch('/api/dsl/undo?branch=' + encodeURIComponent(currentBranch), { method: 'POST' })
                    .then(function (r) { return r.json(); })
                    .then(function (data) {
                        if (data.error) {
                            if (window.TaxonomyOperationResult) {
                                window.TaxonomyOperationResult.showError(t('versions.undo.error'), data.error);
                            } else {
                                alert(t('versions.undo.error') + ': ' + data.error);
                            }
                        } else {
                            loadTimeline();
                            refreshGitStatus();
                        }
                    })
                    .catch(function (err) {
                        if (window.TaxonomyOperationResult) {
                            window.TaxonomyOperationResult.showError(t('versions.undo.error'), err.message);
                        } else {
                            alert(t('versions.undo.error') + ': ' + err.message);
                        }
                    });
            }
        );
    }

    function updateUndoInfo(latestCommit) {
        var info = el('versionsUndoInfo');
        if (!info) return;
        if (latestCommit) {
            info.textContent = t('versions.undo.info', (latestCommit.message || '').substring(0, MAX_COMMIT_MESSAGE_DISPLAY));
        } else {
            info.textContent = '';
        }
    }

    // \u2500\u2500 Save version \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    function saveVersion() {
        var titleEl = el('versionTitle');
        var descEl = el('versionDescription');
        var statusEl = el('versionsSaveStatus');
        var title = titleEl ? titleEl.value.trim() : '';
        var desc = descEl ? descEl.value.trim() : '';

        if (!title) {
            if (statusEl) {
                statusEl.textContent = t('versions.save.error_empty');
                statusEl.className = 'ms-2 small text-danger';
            }
            return;
        }

        var message = title + (desc ? '\n\n' + desc : '');

        fetch('/api/dsl/git/head?branch=' + encodeURIComponent(currentBranch))
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (!data.dslText) throw new Error(t('versions.save.error_no_dsl'));
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
                        statusEl.textContent = t('versions.save.error') + (result.errors || [result.error]).join(', ');
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
                    statusEl.textContent = t('versions.save.error') + err.message;
                    statusEl.className = 'ms-2 small text-danger';
                }
            });
    }

    // \u2500\u2500 Confirm modal helper \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

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
            '<button type="button" class="btn btn-secondary btn-sm" data-bs-dismiss="modal">' + t('common.cancel') + '</button>' +
            '<button type="button" class="btn ' + confirmBtnClass + ' btn-sm" id="versionsConfirmBtn">' + escapeHtml(confirmLabel) + '</button>' +
            '</div></div></div></div>';

        document.body.insertAdjacentHTML('beforeend', modalHtml);
        var modalEl = document.getElementById('versionsConfirmModal');
        var modal = new bootstrap.Modal(modalEl);

        document.getElementById('versionsConfirmBtn').addEventListener('click', function () {
            modal.hide();
            if (onConfirm) onConfirm();
        });

        modal.show();
        modalEl.addEventListener('hidden.bs.modal', function () { modalEl.remove(); });
    }

    // \u2500\u2500 Modal helper \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

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
            '<button type="button" class="btn btn-secondary btn-sm" data-bs-dismiss="modal">' + t('common.close') + '</button>' +
            '</div></div></div></div>';

        document.body.insertAdjacentHTML('beforeend', modalHtml);
        var modalEl = document.getElementById('versionsModal');
        var modal = new bootstrap.Modal(modalEl);
        modal.show();
        modalEl.addEventListener('hidden.bs.modal', function () { modalEl.remove(); });
    }

    // \u2500\u2500 Helpers \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

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
            if (isToday) {
                return t('versions.today') + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            }
            return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        } catch (e) {
            return String(ts);
        }
    }

    function escapeHtml(s) {
        if (!s) return '';
        return String(s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function escapeAttr(s) {
        return escapeHtml(s);
    }

    // \u2500\u2500 Public API \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    return {
        loadTimeline: loadTimeline,
        undoLast: undoLast,
        saveVersion: saveVersion,
        restoreVersion: restoreVersion
    };
}());
