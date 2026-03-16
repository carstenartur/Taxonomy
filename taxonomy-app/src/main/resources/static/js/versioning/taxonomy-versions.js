/**
 * Versions Tab — History timeline, Undo, Restore, Save Version.
 *
 * <p>Provides a user-friendly interface over the JGit-backed DSL versioning
 * system. Instead of exposing raw Git concepts, this module uses terms like
 * "version", "undo", "restore", and "save" to make the history accessible.
 */
window.TaxonomyVersions = (function () {
    'use strict';

    // ── Constants ─────────────────────────────────────────────────

    var MAX_COMMIT_MESSAGE_DISPLAY = 50;
    var DEFAULT_AUTHOR = 'user';

    // ── Selectors (resolved lazily) ─────────────────────────────────

    function el(id) { return document.getElementById(id); }

    // ── State ───────────────────────────────────────────────────────

    var currentBranch = 'draft';

    // ── Initialization ──────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        // Branch selector
        var branchSelect = el('versionsBranchSelect');
        if (branchSelect) {
            loadBranches(branchSelect);
            branchSelect.addEventListener('change', function () {
                currentBranch = this.value;
                loadTimeline();
            });
        }

        // Undo button
        var undoBtn = el('versionsUndoBtn');
        if (undoBtn) {
            undoBtn.addEventListener('click', undoLast);
        }

        // Refresh button
        var refreshBtn = el('versionsRefreshBtn');
        if (refreshBtn) {
            refreshBtn.addEventListener('click', function () { loadTimeline(); });
        }

        // Save version button
        var saveBtn = el('versionsSaveBtn');
        if (saveBtn) {
            saveBtn.addEventListener('click', saveVersion);
        }

        // Sub-tab navigation
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
        // Auto-refresh variants when switching to that sub-tab
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

    // ── Timeline ────────────────────────────────────────────────────

    function loadTimeline() {
        var container = el('versionsTimeline');
        if (!container) return;

        // Refresh branch list too
        var branchSelect = el('versionsBranchSelect');
        if (branchSelect) loadBranches(branchSelect);

        container.innerHTML = '<div class="text-muted small">Loading version history…</div>';

        fetch('/api/dsl/history?branch=' + encodeURIComponent(currentBranch))
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (data) {
                var commits = data.commits || [];
                if (commits.length === 0) {
                    container.innerHTML = '<div class="text-muted small p-2">No versions found on this branch.</div>';
                    updateUndoInfo(null);
                    return;
                }

                // Update undo info with latest commit
                updateUndoInfo(commits[0]);

                var html = '<div class="timeline">';
                commits.forEach(function (commit, idx) {
                    html += renderTimelineEntry(commit, idx === 0);
                });
                html += '</div>';
                container.innerHTML = html;

                // Attach event handlers
                container.querySelectorAll('[data-action]').forEach(function (btn) {
                    btn.addEventListener('click', handleTimelineAction);
                });
            })
            .catch(function (err) {
                container.innerHTML = '<div class="text-danger small p-2">Failed to load history: ' + escapeHtml(err.message) + '</div>';
            });
    }

    function renderTimelineEntry(commit, isLatest) {
        var ts = commit.timestamp ? formatTimestamp(commit.timestamp) : '';
        var sha = commit.commitId ? commit.commitId.substring(0, 7) : '';
        var author = commit.author || 'unknown';
        var message = commit.message || 'No message';

        var dotClass = isLatest ? 'timeline-dot-current' : 'timeline-dot';

        return '<div class="timeline-entry mb-3 ps-4 position-relative">' +
            '<span class="' + dotClass + ' position-absolute" style="left:0;top:6px;width:10px;height:10px;border-radius:50%;display:inline-block;"></span>' +
            '<div class="d-flex justify-content-between align-items-start">' +
            '<div>' +
            '<strong class="small">' + escapeHtml(message) + '</strong>' +
            '<div class="text-muted" style="font-size:0.75rem;">' +
            ts + ' &mdash; ' + escapeHtml(author) +
            ' <code class="ms-1">' + sha + '</code>' +
            '</div>' +
            '</div>' +
            '<div class="d-flex gap-1">' +
            '<button class="btn btn-outline-secondary btn-sm py-0 px-1" style="font-size:0.7rem;" ' +
            'data-action="view" data-commit="' + escapeAttr(commit.commitId) + '" title="View DSL at this version">' +
            '&#128065; View</button>' +
            '<button class="btn btn-outline-info btn-sm py-0 px-1" style="font-size:0.7rem;" ' +
            'data-action="compare" data-commit="' + escapeAttr(commit.commitId) + '" title="Compare with current">' +
            '&#128269; Compare</button>' +
            '<button class="btn btn-outline-warning btn-sm py-0 px-1" style="font-size:0.7rem;" ' +
            'data-action="restore" data-commit="' + escapeAttr(commit.commitId) + '" title="Restore this version">' +
            '&#8617; Restore</button>' +
            '<button class="btn btn-outline-danger btn-sm py-0 px-1" style="font-size:0.7rem;" ' +
            'data-action="revert" data-commit="' + escapeAttr(commit.commitId) + '" title="Revert this commit">' +
            '&#10060; Revert</button>' +
            '<button class="btn btn-outline-success btn-sm py-0 px-1" style="font-size:0.7rem;" ' +
            'data-action="variant" data-commit="' + escapeAttr(commit.commitId) + '" data-branch="' + escapeAttr(currentBranch) + '" title="Create variant from this version">' +
            '&#43; Variant</button>' +
            '</div>' +
            '</div>' +
            '</div>';
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
                showModal('Error', '<div class="text-danger">' + escapeHtml(err.message) + '</div>');
            });
    }

    function compareVersion(commitId) {
        // Get current HEAD for comparison
        fetch('/api/git/state?branch=' + encodeURIComponent(currentBranch))
            .then(function (r) { return r.json(); })
            .then(function (state) {
                var headCommit = state.headCommit;
                if (!headCommit || headCommit === commitId) {
                    showModal('Compare', '<div class="text-muted">This is the current version — nothing to compare.</div>');
                    return;
                }
                return fetch('/api/dsl/diff/' + encodeURIComponent(commitId) + '/' + encodeURIComponent(headCommit));
            })
            .then(function (r) { return r ? r.json() : null; })
            .then(function (diff) {
                if (!diff) return;
                var html = '<div class="small">';
                html += '<p><strong>Total changes:</strong> ' + (diff.totalChanges || 0) + '</p>';
                if (diff.added && diff.added.length > 0) {
                    html += '<h6 class="text-success">Added (' + diff.added.length + ')</h6>';
                    html += '<ul>' + diff.added.map(function (a) { return '<li>' + escapeHtml(a.code || a.id || JSON.stringify(a)) + '</li>'; }).join('') + '</ul>';
                }
                if (diff.removed && diff.removed.length > 0) {
                    html += '<h6 class="text-danger">Removed (' + diff.removed.length + ')</h6>';
                    html += '<ul>' + diff.removed.map(function (r) { return '<li>' + escapeHtml(r.code || r.id || JSON.stringify(r)) + '</li>'; }).join('') + '</ul>';
                }
                if (diff.changed && diff.changed.length > 0) {
                    html += '<h6 class="text-warning">Changed (' + diff.changed.length + ')</h6>';
                    html += '<ul>' + diff.changed.map(function (c) { return '<li>' + escapeHtml(c.code || c.id || JSON.stringify(c)) + '</li>'; }).join('') + '</ul>';
                }
                if ((diff.totalChanges || 0) === 0) {
                    html += '<p class="text-muted">No differences found.</p>';
                }
                html += '</div>';
                showModal('Compare: ' + commitId.substring(0, 7) + ' → HEAD', html);
            })
            .catch(function (err) {
                showModal('Error', '<div class="text-danger">' + escapeHtml(err.message) + '</div>');
            });
    }

    function restoreVersion(commitId) {
        if (!confirm('Restore the DSL content from version ' + commitId.substring(0, 7) + '?\n\nThis creates a new commit with the old content.')) {
            return;
        }
        fetch('/api/dsl/restore?commitId=' + encodeURIComponent(commitId) +
            '&branch=' + encodeURIComponent(currentBranch), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.error) {
                    alert('Restore failed: ' + data.error);
                } else {
                    loadTimeline();
                    refreshGitStatus();
                }
            })
            .catch(function (err) { alert('Restore failed: ' + err.message); });
    }

    function revertVersion(commitId) {
        if (!confirm('Revert commit ' + commitId.substring(0, 7) + '?\n\nThis creates a new commit that undoes the changes.')) {
            return;
        }
        fetch('/api/dsl/revert?commitId=' + encodeURIComponent(commitId) +
            '&branch=' + encodeURIComponent(currentBranch), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.error) {
                    alert('Revert failed: ' + data.error);
                } else {
                    loadTimeline();
                    refreshGitStatus();
                }
            })
            .catch(function (err) { alert('Revert failed: ' + err.message); });
    }

    // ── Create variant from version ────────────────────────────────

    function createVariantFromVersion(commitId, branch) {
        // First, open this version as a read-only context, then offer to create variant
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
        if (!confirm('Undo the last change on branch "' + currentBranch + '"?\n\nThis removes the last commit from the branch history.')) {
            return;
        }
        fetch('/api/dsl/undo?branch=' + encodeURIComponent(currentBranch), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.error) {
                    alert('Undo failed: ' + data.error);
                } else {
                    loadTimeline();
                    refreshGitStatus();
                }
            })
            .catch(function (err) { alert('Undo failed: ' + err.message); });
    }

    function updateUndoInfo(latestCommit) {
        var info = el('versionsUndoInfo');
        if (!info) return;
        if (latestCommit) {
            info.textContent = 'Last: "' + (latestCommit.message || '').substring(0, MAX_COMMIT_MESSAGE_DISPLAY) + '"';
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
                statusEl.textContent = 'Please enter a title.';
                statusEl.className = 'ms-2 small text-danger';
            }
            return;
        }

        var message = title + (desc ? '\n\n' + desc : '');

        // First, get the current DSL text
        fetch('/api/dsl/git/head?branch=' + encodeURIComponent(currentBranch))
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (!data.dslText) throw new Error('No DSL content on this branch');
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
                        statusEl.textContent = 'Save failed: ' + (result.errors || [result.error]).join(', ');
                        statusEl.className = 'ms-2 small text-danger';
                    }
                } else {
                    if (statusEl) {
                        statusEl.textContent = 'Version saved! (' + (result.commitId || '').substring(0, 7) + ')';
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
                    statusEl.textContent = 'Save failed: ' + err.message;
                    statusEl.className = 'ms-2 small text-danger';
                }
            });
    }

    // ── Modal helper ────────────────────────────────────────────────

    function showModal(title, bodyHtml) {
        // Use a simple dynamic modal
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
            '<button type="button" class="btn btn-secondary btn-sm" data-bs-dismiss="modal">Close</button>' +
            '</div></div></div></div>';

        document.body.insertAdjacentHTML('beforeend', modalHtml);
        var modalEl = document.getElementById('versionsModal');
        var modal = new bootstrap.Modal(modalEl);
        modal.show();
        modalEl.addEventListener('hidden.bs.modal', function () { modalEl.remove(); });
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
            if (isToday) {
                return 'Today, ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
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

    // ── Public API ──────────────────────────────────────────────────

    return {
        loadTimeline: loadTimeline,
        undoLast: undoLast,
        saveVersion: saveVersion,
        restoreVersion: restoreVersion
    };
}());
