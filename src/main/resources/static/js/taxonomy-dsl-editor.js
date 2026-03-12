/**
 * DSL Editor — Frontend logic for the Architecture DSL panel.
 *
 * Calls /api/dsl/* endpoints for:
 *  - Editing, parsing, validating DSL text
 *  - Committing versions (with branch, author, message)
 *  - Browsing commit history & computing diffs
 *  - Managing branches (list, create, fork)
 *  - Materialising DSL into the database (full & incremental)
 */
(function () {
    'use strict';

    // ── DOM references ──────────────────────────────────────────────
    var editor, parseBtn, validateBtn, commitBtn, materializeBtn, loadCurrentBtn;
    var branchSelect, newBranchBtn, authorInput, messageInput;
    var validationOutput, historyBody, diffOutput, statusArea;
    var materializeIncrBtn;

    document.addEventListener('DOMContentLoaded', init);

    function init() {
        editor            = document.getElementById('dslEditorTextarea');
        parseBtn          = document.getElementById('dslParseBtn');
        validateBtn       = document.getElementById('dslValidateBtn');
        commitBtn         = document.getElementById('dslCommitBtn');
        materializeBtn    = document.getElementById('dslMaterializeBtn');
        materializeIncrBtn = document.getElementById('dslMaterializeIncrBtn');
        loadCurrentBtn    = document.getElementById('dslLoadCurrentBtn');
        branchSelect      = document.getElementById('dslBranchSelect');
        newBranchBtn      = document.getElementById('dslNewBranchBtn');
        authorInput       = document.getElementById('dslAuthorInput');
        messageInput      = document.getElementById('dslMessageInput');
        validationOutput  = document.getElementById('dslValidationOutput');
        historyBody       = document.getElementById('dslHistoryBody');
        diffOutput        = document.getElementById('dslDiffOutput');
        statusArea        = document.getElementById('dslStatusArea');

        if (!editor) return; // tab not rendered yet

        // Bind buttons
        if (parseBtn)          parseBtn.addEventListener('click', parseDsl);
        if (validateBtn)       validateBtn.addEventListener('click', validateDsl);
        if (commitBtn)         commitBtn.addEventListener('click', commitDsl);
        if (materializeBtn)    materializeBtn.addEventListener('click', materializeDsl);
        if (materializeIncrBtn) materializeIncrBtn.addEventListener('click', materializeIncremental);
        if (loadCurrentBtn)    loadCurrentBtn.addEventListener('click', loadCurrent);
        if (newBranchBtn)      newBranchBtn.addEventListener('click', createBranch);
        if (branchSelect)      branchSelect.addEventListener('change', onBranchChange);

        // Initial load
        loadBranches();
        loadCurrent();
    }

    // ── Status helper ───────────────────────────────────────────────
    function showStatus(msg, type) {
        if (!statusArea) return;
        var cls = type === 'error' ? 'alert-danger' : type === 'success' ? 'alert-success' : 'alert-info';
        statusArea.className = 'alert ' + cls + ' py-2 small mb-2';
        statusArea.textContent = msg;
        statusArea.classList.remove('d-none');
        if (type !== 'error') {
            setTimeout(function () { statusArea.classList.add('d-none'); }, 6000);
        }
    }

    // ── Load current architecture as DSL ────────────────────────────
    function loadCurrent() {
        showStatus('Loading current architecture…', 'info');
        fetch('/api/dsl/export')
            .then(function (r) { return r.text(); })
            .then(function (text) {
                if (editor) editor.value = text;
                showStatus('Loaded current architecture DSL (' + text.length + ' chars)', 'success');
            })
            .catch(function (e) { showStatus('Failed to load: ' + e.message, 'error'); });
    }

    // ── Parse ───────────────────────────────────────────────────────
    function parseDsl() {
        if (!editor) return;
        showStatus('Parsing…', 'info');
        fetch('/api/dsl/parse', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: editor.value
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                renderValidation(data);
                showStatus('Parsed: ' + data.elements + ' elements, ' + data.relations + ' relations', 'success');
            })
            .catch(function (e) { showStatus('Parse error: ' + e.message, 'error'); });
    }

    // ── Validate ────────────────────────────────────────────────────
    function validateDsl() {
        if (!editor) return;
        showStatus('Validating…', 'info');
        fetch('/api/dsl/validate', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: editor.value
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                renderValidation(data);
                showStatus(data.valid ? '✅ Valid DSL' : '❌ Validation errors', data.valid ? 'success' : 'error');
            })
            .catch(function (e) { showStatus('Validate error: ' + e.message, 'error'); });
    }

    function renderValidation(data) {
        if (!validationOutput) return;
        var html = '';
        if (data.valid) {
            html = '<span class="text-success">✅ Valid</span>';
            if (data.elements !== undefined) {
                html += ' — ' + data.elements + ' elements, ' + data.relations + ' relations';
            }
        } else {
            html = '<span class="text-danger">❌ Invalid</span><ul class="mb-0 mt-1">';
            (data.errors || []).forEach(function (e) {
                html += '<li class="text-danger small">' + escapeHtml(e) + '</li>';
            });
            html += '</ul>';
        }
        if (data.warnings && data.warnings.length > 0) {
            html += '<ul class="mb-0 mt-1">';
            data.warnings.forEach(function (w) {
                html += '<li class="text-warning small">⚠ ' + escapeHtml(w) + '</li>';
            });
            html += '</ul>';
        }
        validationOutput.innerHTML = html;
    }

    // ── Commit ──────────────────────────────────────────────────────
    function commitDsl() {
        if (!editor) return;
        var branch = branchSelect ? branchSelect.value : 'draft';
        var author = authorInput ? authorInput.value.trim() : '';
        var message = messageInput ? messageInput.value.trim() : '';
        if (!message) { message = 'Manual commit'; }
        showStatus('Committing to branch "' + branch + '"…', 'info');
        var url = '/api/dsl/commit?branch=' + encodeURIComponent(branch);
        if (author) url += '&author=' + encodeURIComponent(author);
        if (message) url += '&message=' + encodeURIComponent(message);
        fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: editor.value
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.valid === false) {
                    showStatus('Commit rejected: validation errors', 'error');
                    renderValidation(data);
                    return;
                }
                showStatus('✅ Committed: ' + (data.commitId || data.documentId), 'success');
                if (messageInput) messageInput.value = '';
                loadHistory(branch);
            })
            .catch(function (e) { showStatus('Commit error: ' + e.message, 'error'); });
    }

    // ── Materialize (full) ──────────────────────────────────────────
    function materializeDsl() {
        if (!editor) return;
        var branch = branchSelect ? branchSelect.value : '';
        showStatus('Materializing…', 'info');
        var url = '/api/dsl/materialize';
        if (branch) url += '?branch=' + encodeURIComponent(branch);
        fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: editor.value
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.valid === false) {
                    showStatus('Materialization failed: validation errors', 'error');
                    renderValidation(data);
                    return;
                }
                showStatus('✅ Materialized: ' + data.relationsCreated + ' relations, ' +
                    data.hypothesesCreated + ' hypotheses (doc #' + data.documentId + ')', 'success');
            })
            .catch(function (e) { showStatus('Materialize error: ' + e.message, 'error'); });
    }

    // ── Materialize incremental ─────────────────────────────────────
    function materializeIncremental() {
        // Get the last two documents from history to do an incremental materialization
        var branch = branchSelect ? branchSelect.value : 'draft';
        showStatus('Loading history for incremental materialization…', 'info');
        fetch('/api/dsl/history?branch=' + encodeURIComponent(branch))
            .then(function (r) { return r.json(); })
            .then(function (docs) {
                if (docs.length < 2) {
                    showStatus('Need at least 2 commits on branch "' + branch + '" for incremental materialization', 'error');
                    return;
                }
                var afterId = docs[0].documentId;
                var beforeId = docs[1].documentId;
                return fetch('/api/dsl/materialize-incremental?afterDocId=' + afterId + '&beforeDocId=' + beforeId, {
                    method: 'POST'
                }).then(function (r) { return r.json(); });
            })
            .then(function (data) {
                if (!data) return;
                showStatus('✅ Incremental: ' + data.relationsCreated + ' relations, ' +
                    data.hypothesesCreated + ' hypotheses', 'success');
            })
            .catch(function (e) { showStatus('Incremental error: ' + e.message, 'error'); });
    }

    // ── Branches ────────────────────────────────────────────────────
    function loadBranches() {
        fetch('/api/dsl/branches')
            .then(function (r) { return r.json(); })
            .then(function (branches) {
                if (!branchSelect) return;
                var current = branchSelect.value || 'draft';
                branchSelect.innerHTML = '';
                // Always include draft
                var hasDraft = false;
                branches.forEach(function (b) {
                    var opt = document.createElement('option');
                    opt.value = b.name;
                    opt.textContent = b.name + (b.headCommitId ? ' (' + b.headCommitId.substring(0, 8) + ')' : '');
                    branchSelect.appendChild(opt);
                    if (b.name === 'draft') hasDraft = true;
                });
                if (!hasDraft) {
                    var opt = document.createElement('option');
                    opt.value = 'draft';
                    opt.textContent = 'draft (new)';
                    branchSelect.insertBefore(opt, branchSelect.firstChild);
                }
                branchSelect.value = current;
                loadHistory(current);
            })
            .catch(function () {
                // Fallback: just show draft
                if (branchSelect) {
                    branchSelect.innerHTML = '<option value="draft">draft</option>';
                }
            });
    }

    function onBranchChange() {
        var branch = branchSelect ? branchSelect.value : 'draft';
        loadHistory(branch);
    }

    function createBranch() {
        var name = prompt('New branch name:');
        if (!name || !name.trim()) return;
        name = name.trim();
        var fromBranch = branchSelect ? branchSelect.value : 'draft';
        showStatus('Creating branch "' + name + '" from "' + fromBranch + '"…', 'info');
        fetch('/api/dsl/branches?name=' + encodeURIComponent(name) + '&fromBranch=' + encodeURIComponent(fromBranch), {
            method: 'POST'
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.error) {
                    showStatus('Branch creation failed: ' + data.error, 'error');
                    return;
                }
                showStatus('✅ Created branch "' + name + '"', 'success');
                loadBranches();
                if (branchSelect) branchSelect.value = name;
            })
            .catch(function (e) { showStatus('Branch error: ' + e.message, 'error'); });
    }

    // ── History ─────────────────────────────────────────────────────
    function loadHistory(branch) {
        if (!historyBody) return;
        fetch('/api/dsl/history?branch=' + encodeURIComponent(branch || 'draft'))
            .then(function (r) { return r.json(); })
            .then(function (docs) {
                renderHistory(docs);
            })
            .catch(function () {
                historyBody.innerHTML = '<tr><td colspan="4" class="text-muted">Failed to load history</td></tr>';
            });
    }

    function renderHistory(docs) {
        if (!historyBody) return;
        if (!docs || docs.length === 0) {
            historyBody.innerHTML = '<tr><td colspan="4" class="text-muted">No commits yet</td></tr>';
            return;
        }
        var html = '';
        docs.forEach(function (d, i) {
            var ts = d.timestamp ? new Date(d.timestamp).toLocaleString() : '—';
            var commitShort = d.commitId ? d.commitId.substring(0, 8) : '#' + d.documentId;
            html += '<tr>';
            html += '<td><code class="small">' + escapeHtml(commitShort) + '</code></td>';
            html += '<td class="small">' + escapeHtml(d.branch || '') + '</td>';
            html += '<td class="small">' + escapeHtml(ts) + '</td>';
            html += '<td>';
            html += '<button class="btn btn-sm btn-outline-secondary me-1 dsl-load-commit" data-doc-id="' + d.documentId + '" title="Load this version">📂</button>';
            if (i < docs.length - 1) {
                html += '<button class="btn btn-sm btn-outline-info dsl-diff-btn" data-before="' + docs[i + 1].documentId + '" data-after="' + d.documentId + '" title="Diff with previous">🔍</button>';
            }
            html += '</td>';
            html += '</tr>';
        });
        historyBody.innerHTML = html;

        // Bind load-commit buttons
        historyBody.querySelectorAll('.dsl-load-commit').forEach(function (btn) {
            btn.addEventListener('click', function () {
                loadDocumentById(this.getAttribute('data-doc-id'));
            });
        });

        // Bind diff buttons
        historyBody.querySelectorAll('.dsl-diff-btn').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var before = this.getAttribute('data-before');
                var after = this.getAttribute('data-after');
                loadDiff(before, after);
            });
        });
    }

    function loadDocumentById(docId) {
        showStatus('Loading document #' + docId + '…', 'info');
        fetch('/api/dsl/documents')
            .then(function (r) { return r.json(); })
            .then(function (docs) {
                var doc = docs.find(function (d) { return String(d.id) === String(docId); });
                if (doc && doc.rawContent && editor) {
                    editor.value = doc.rawContent;
                    showStatus('Loaded document #' + docId, 'success');
                } else {
                    showStatus('Document #' + docId + ' not found or empty', 'error');
                }
            })
            .catch(function (e) { showStatus('Load error: ' + e.message, 'error'); });
    }

    // ── Diff ────────────────────────────────────────────────────────
    function loadDiff(beforeId, afterId) {
        if (!diffOutput) return;
        showStatus('Computing diff…', 'info');
        fetch('/api/dsl/diff/' + beforeId + '/' + afterId)
            .then(function (r) { return r.json(); })
            .then(function (data) {
                renderDiff(data);
                showStatus('Diff: ' + data.totalChanges + ' changes', 'success');
            })
            .catch(function (e) {
                diffOutput.innerHTML = '<span class="text-danger">Diff failed: ' + escapeHtml(e.message) + '</span>';
                showStatus('Diff error: ' + e.message, 'error');
            });
    }

    function renderDiff(data) {
        if (!diffOutput) return;
        if (data.isEmpty) {
            diffOutput.innerHTML = '<span class="text-muted">No changes</span>';
            return;
        }
        var html = '<div class="small">';
        html += '<strong>' + data.totalChanges + ' change(s)</strong><br>';

        if (data.addedElements > 0) html += '<span class="text-success">+ ' + data.addedElements + ' element(s) added</span><br>';
        if (data.removedElements > 0) html += '<span class="text-danger">− ' + data.removedElements + ' element(s) removed</span><br>';
        if (data.changedElements > 0) html += '<span class="text-warning">~ ' + data.changedElements + ' element(s) changed</span><br>';
        if (data.addedRelations > 0) html += '<span class="text-success">+ ' + data.addedRelations + ' relation(s) added</span><br>';
        if (data.removedRelations > 0) html += '<span class="text-danger">− ' + data.removedRelations + ' relation(s) removed</span><br>';
        if (data.changedRelations > 0) html += '<span class="text-warning">~ ' + data.changedRelations + ' relation(s) changed</span><br>';

        // Show details if present
        if (data.details) {
            var d = data.details;
            if (d.addedElements && d.addedElements.length > 0) {
                html += '<details class="mt-2"><summary class="text-success">Added Elements</summary><ul>';
                d.addedElements.forEach(function (e) {
                    html += '<li><code>' + escapeHtml(e.id) + '</code> ' + escapeHtml(e.title || '') + ' <small class="text-muted">(' + escapeHtml(e.type || '') + ')</small></li>';
                });
                html += '</ul></details>';
            }
            if (d.removedElements && d.removedElements.length > 0) {
                html += '<details class="mt-1"><summary class="text-danger">Removed Elements</summary><ul>';
                d.removedElements.forEach(function (e) {
                    html += '<li><code>' + escapeHtml(e.id) + '</code> ' + escapeHtml(e.title || '') + '</li>';
                });
                html += '</ul></details>';
            }
            if (d.addedRelations && d.addedRelations.length > 0) {
                html += '<details class="mt-1"><summary class="text-success">Added Relations</summary><ul>';
                d.addedRelations.forEach(function (r) {
                    html += '<li><code>' + escapeHtml(r.sourceId) + '</code> —[' + escapeHtml(r.relationType) + ']→ <code>' + escapeHtml(r.targetId) + '</code></li>';
                });
                html += '</ul></details>';
            }
            if (d.removedRelations && d.removedRelations.length > 0) {
                html += '<details class="mt-1"><summary class="text-danger">Removed Relations</summary><ul>';
                d.removedRelations.forEach(function (r) {
                    html += '<li><code>' + escapeHtml(r.sourceId) + '</code> —[' + escapeHtml(r.relationType) + ']→ <code>' + escapeHtml(r.targetId) + '</code></li>';
                });
                html += '</ul></details>';
            }
        }
        html += '</div>';
        diffOutput.innerHTML = html;
    }

    // ── Util ────────────────────────────────────────────────────────
    function escapeHtml(s) {
        if (!s) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
}());
