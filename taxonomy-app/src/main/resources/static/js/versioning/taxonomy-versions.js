/**
 * Versionen — Verlauf, Rückg\u00E4ngig, Wiederherstellen, Version speichern.
 *
 * <p>Provides a user-friendly interface over the JGit-backed DSL versioning
 * system. Uses German labels for non-developer audiences: "Version",
 * "Rückg\u00E4ngig", "Wiederherstellen", and "Speichern".
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

        var branchSelect = el('versionsBranchSelect');
        if (branchSelect) loadBranches(branchSelect);

        container.innerHTML = '<div class="text-muted small">Versionsverlauf wird geladen\u2026</div>';

        fetch('/api/dsl/history?branch=' + encodeURIComponent(currentBranch))
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (data) {
                var commits = data.commits || [];
                if (commits.length === 0) {
                    container.innerHTML = '<div class="text-muted small p-2">Keine Versionen auf diesem Zweig gefunden.</div>';
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
                container.innerHTML = '<div class="text-danger small p-2">Verlauf konnte nicht geladen werden: ' + escapeHtml(err.message) + '</div>';
            });
    }

    function renderTimelineEntry(commit, isLatest) {
        var ts = commit.timestamp ? formatTimestamp(commit.timestamp) : '';
        var sha = commit.commitId ? commit.commitId.substring(0, 7) : '';
        var author = commit.author || 'unbekannt';
        var message = commit.message || 'Keine Nachricht';

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
            html += ' <span class="restore-marker">\uD83D\uDD04 Wiederherstellung</span>';
        } else if (isRevert) {
            html += ' <span class="restore-marker">\uD83D\uDD04 R\u00FCckg\u00E4ngig</span>';
        }

        html += '<div class="text-muted" style="font-size:0.75rem;">' +
            ts + ' &mdash; ' + escapeHtml(author) +
            ' <code class="ms-1">' + sha + '</code>' +
            '</div>' +
            '</div>' +
            '<div class="d-flex gap-1">' +
            '<button class="btn btn-outline-secondary btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="view" data-commit="' + escapeAttr(commit.commitId) + '" title="DSL dieser Version anzeigen">' +
            '\uD83D\uDC41 Ansehen</button>' +
            '<button class="btn btn-outline-info btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="compare" data-commit="' + escapeAttr(commit.commitId) + '" title="Mit aktuellem Stand vergleichen">' +
            '\uD83D\uDD0D Vergleichen</button>' +
            '<button class="btn btn-outline-warning btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="restore" data-commit="' + escapeAttr(commit.commitId) + '" title="Auf diese Version zur\u00FCcksetzen">' +
            '\u21A9 Zur\u00FCcksetzen</button>' +
            '<button class="btn btn-outline-danger btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="revert" data-commit="' + escapeAttr(commit.commitId) + '" title="Diese \u00C4nderung r\u00FCckg\u00E4ngig machen">' +
            '\u274C R\u00FCckg\u00E4ngig</button>' +
            '<button class="btn btn-outline-success btn-sm py-0 px-2" style="font-size:0.75rem;" ' +
            'data-action="variant" data-commit="' + escapeAttr(commit.commitId) + '" data-branch="' + escapeAttr(currentBranch) + '" title="Variante aus dieser Version erstellen">' +
            '\uD83C\uDF3F Variante</button>' +
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
                showModal('Fehler', '<div class="text-danger">' + escapeHtml(err.message) + '</div>');
            });
    }

    function compareVersion(commitId) {
        fetch('/api/git/state?branch=' + encodeURIComponent(currentBranch))
            .then(function (r) { return r.json(); })
            .then(function (state) {
                var headCommit = state.headCommit;
                if (!headCommit || headCommit === commitId) {
                    showModal('Vergleich', '<div class="text-muted">Dies ist die aktuelle Version \u2014 nichts zu vergleichen.</div>');
                    return;
                }
                return fetch('/api/dsl/diff/' + encodeURIComponent(commitId) + '/' + encodeURIComponent(headCommit));
            })
            .then(function (r) { return r ? r.json() : null; })
            .then(function (diff) {
                if (!diff) return;
                var html = '<div class="small">';
                html += '<div class="compare-summary-card mb-3">';
                html += '<div class="compare-title">Vergleich: ' + commitId.substring(0, 7) + ' \u2194 Aktuell</div>';
                html += '<div class="compare-stats">';
                if (diff.added && diff.added.length > 0) {
                    html += '<span class="compare-stat text-success">\uD83D\uDFE2 +' + diff.added.length + ' hinzugef\u00FCgt</span>';
                }
                if (diff.removed && diff.removed.length > 0) {
                    html += '<span class="compare-stat text-danger">\uD83D\uDD34 \u2212' + diff.removed.length + ' entfernt</span>';
                }
                if (diff.changed && diff.changed.length > 0) {
                    html += '<span class="compare-stat text-warning">\uD83D\uDFE1 ~' + diff.changed.length + ' ge\u00E4ndert</span>';
                }
                html += '</div></div>';

                if (diff.added && diff.added.length > 0) {
                    html += '<h6 class="text-success">Hinzugef\u00FCgt (' + diff.added.length + ')</h6>';
                    html += '<ul>' + diff.added.map(function (a) { return '<li>' + escapeHtml(a.code || a.id || JSON.stringify(a)) + '</li>'; }).join('') + '</ul>';
                }
                if (diff.removed && diff.removed.length > 0) {
                    html += '<h6 class="text-danger">Entfernt (' + diff.removed.length + ')</h6>';
                    html += '<ul>' + diff.removed.map(function (r) { return '<li>' + escapeHtml(r.code || r.id || JSON.stringify(r)) + '</li>'; }).join('') + '</ul>';
                }
                if (diff.changed && diff.changed.length > 0) {
                    html += '<h6 class="text-warning">Ge\u00E4ndert (' + diff.changed.length + ')</h6>';
                    html += '<ul>' + diff.changed.map(function (c) { return '<li>' + escapeHtml(c.code || c.id || JSON.stringify(c)) + '</li>'; }).join('') + '</ul>';
                }
                if ((diff.totalChanges || 0) === 0) {
                    html += '<p class="text-muted">Keine Unterschiede gefunden.</p>';
                }
                html += '</div>';
                showModal('Vergleich: ' + commitId.substring(0, 7) + ' \u2194 Aktuell', html);
            })
            .catch(function (err) {
                showModal('Fehler', '<div class="text-danger">' + escapeHtml(err.message) + '</div>');
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
        var bodyHtml = '<p>Auf Version <code>' + escapeHtml(commitId.substring(0, 7)) + '</code> zur\u00FCcksetzen?</p>';
        bodyHtml += '<p class="small text-muted">Es wird eine neue Version erstellt, die den Inhalt der ausgew\u00E4hlten Version enth\u00E4lt. Die bisherige Versionsgeschichte bleibt erhalten.</p>';

        if (diff) {
            var total = (diff.totalChanges || 0);
            var addedCount = diff.added ? diff.added.length : 0;
            var removedCount = diff.removed ? diff.removed.length : 0;
            var changedCount = diff.changed ? diff.changed.length : 0;

            if (total > 0) {
                bodyHtml += '<div class="restore-preview">';
                bodyHtml += '<strong>Vorschau der \u00C4nderungen:</strong>';
                bodyHtml += '<div class="mt-1">';
                if (addedCount > 0) bodyHtml += '<span class="text-success me-2">+' + addedCount + ' hinzugef\u00FCgt</span>';
                if (removedCount > 0) bodyHtml += '<span class="text-danger me-2">\u2212' + removedCount + ' entfernt</span>';
                if (changedCount > 0) bodyHtml += '<span class="text-warning me-2">~' + changedCount + ' ge\u00E4ndert</span>';
                bodyHtml += '</div></div>';
            } else {
                bodyHtml += '<div class="restore-preview text-muted">Keine Unterschiede zum aktuellen Stand.</div>';
            }
        }

        showConfirmModal('Auf Version zur\u00FCcksetzen', bodyHtml, 'Zur\u00FCcksetzen', 'btn-warning', function () {
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
                        window.TaxonomyOperationResult.showError('Wiederherstellung fehlgeschlagen', data.error);
                    } else {
                        alert('Wiederherstellung fehlgeschlagen: ' + data.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess('Version wiederhergestellt',
                            'Version ' + commitId.substring(0, 7) + ' wurde wiederhergestellt.');
                    }
                    loadTimeline();
                    refreshGitStatus();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError('Wiederherstellung fehlgeschlagen', err.message);
                } else {
                    alert('Wiederherstellung fehlgeschlagen: ' + err.message);
                }
            });
    }

    /**
     * AP 4: Revert with confirmation modal.
     */
    function revertVersion(commitId) {
        var bodyHtml = '<p>\u00C4nderung von Version <code>' + escapeHtml(commitId.substring(0, 7)) + '</code> r\u00FCckg\u00E4ngig machen?</p>';
        bodyHtml += '<p class="small text-muted">Es wird eine neue Version erstellt, die die \u00C4nderungen dieses Commits r\u00FCckg\u00E4ngig macht. Nur die \u00C4nderungen dieses einen Commits werden zur\u00FCckgenommen.</p>';

        showConfirmModal('\u00C4nderung r\u00FCckg\u00E4ngig machen', bodyHtml, 'R\u00FCckg\u00E4ngig machen', 'btn-danger', function () {
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
                        window.TaxonomyOperationResult.showError('R\u00FCckg\u00E4ngig fehlgeschlagen', data.error);
                    } else {
                        alert('R\u00FCckg\u00E4ngig fehlgeschlagen: ' + data.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess('\u00C4nderung r\u00FCckg\u00E4ngig gemacht',
                            'Version ' + commitId.substring(0, 7) + ' wurde r\u00FCckg\u00E4ngig gemacht.');
                    }
                    loadTimeline();
                    refreshGitStatus();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError('R\u00FCckg\u00E4ngig fehlgeschlagen', err.message);
                } else {
                    alert('R\u00FCckg\u00E4ngig fehlgeschlagen: ' + err.message);
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
        var branchName = currentBranch === 'draft' ? 'Hauptversion' : currentBranch;
        showConfirmModal(
            'Letzte \u00C4nderung r\u00FCckg\u00E4ngig machen',
            '<p>Die letzte \u00C4nderung auf <strong>' + escapeHtml(branchName) + '</strong> r\u00FCckg\u00E4ngig machen?</p>'
            + '<p class="small text-muted">Der letzte Eintrag wird aus der Versionsgeschichte entfernt.</p>',
            'R\u00FCckg\u00E4ngig machen',
            'btn-warning',
            function () {
                fetch('/api/dsl/undo?branch=' + encodeURIComponent(currentBranch), { method: 'POST' })
                    .then(function (r) { return r.json(); })
                    .then(function (data) {
                        if (data.error) {
                            if (window.TaxonomyOperationResult) {
                                window.TaxonomyOperationResult.showError('R\u00FCckg\u00E4ngig fehlgeschlagen', data.error);
                            } else {
                                alert('R\u00FCckg\u00E4ngig fehlgeschlagen: ' + data.error);
                            }
                        } else {
                            loadTimeline();
                            refreshGitStatus();
                        }
                    })
                    .catch(function (err) {
                        if (window.TaxonomyOperationResult) {
                            window.TaxonomyOperationResult.showError('R\u00FCckg\u00E4ngig fehlgeschlagen', err.message);
                        } else {
                            alert('R\u00FCckg\u00E4ngig fehlgeschlagen: ' + err.message);
                        }
                    });
            }
        );
    }

    function updateUndoInfo(latestCommit) {
        var info = el('versionsUndoInfo');
        if (!info) return;
        if (latestCommit) {
            info.textContent = 'Letzte: \u201E' + (latestCommit.message || '').substring(0, MAX_COMMIT_MESSAGE_DISPLAY) + '\u201C';
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
                statusEl.textContent = 'Bitte geben Sie einen Titel ein.';
                statusEl.className = 'ms-2 small text-danger';
            }
            return;
        }

        var message = title + (desc ? '\n\n' + desc : '');

        fetch('/api/dsl/git/head?branch=' + encodeURIComponent(currentBranch))
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (!data.dslText) throw new Error('Kein DSL-Inhalt auf diesem Zweig');
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
                        statusEl.textContent = 'Speichern fehlgeschlagen: ' + (result.errors || [result.error]).join(', ');
                        statusEl.className = 'ms-2 small text-danger';
                    }
                } else {
                    if (statusEl) {
                        statusEl.textContent = 'Version gespeichert! (' + (result.commitId || '').substring(0, 7) + ')';
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
                    statusEl.textContent = 'Speichern fehlgeschlagen: ' + err.message;
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
            '<button type="button" class="btn btn-secondary btn-sm" data-bs-dismiss="modal">Abbrechen</button>' +
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
            '<button type="button" class="btn btn-secondary btn-sm" data-bs-dismiss="modal">Schlie\u00DFen</button>' +
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
                return 'Heute, ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            }
            return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
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
