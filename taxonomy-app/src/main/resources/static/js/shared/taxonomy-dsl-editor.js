/**
 * DSL Editor — Frontend logic for the Architecture DSL panel.
 *
 * Calls /api/dsl/* endpoints for:
 *  - Editing, parsing, validating DSL text
 *  - Committing versions (with branch, author, message)
 *  - Browsing commit history & computing diffs
 *  - Managing branches (list, create, fork)
 *  - Materialising DSL into the database (full & incremental)
 *
 * The editor uses CodeMirror 6 (loaded via taxonomy-dsl-codemirror.mjs).
 * window.dslCmView is the EditorView instance once the module has initialised.
 */
(function () {
    'use strict';

    // ── DOM references ──────────────────────────────────────────────
    var parseBtn, validateBtn, formatBtn, commitBtn, materializeBtn, loadCurrentBtn;
    var branchSelect, newBranchBtn, authorInput, messageInput;
    var validationOutput, historyBody, diffOutput, statusArea;
    var materializeIncrBtn, mergeBtn;
    var editorContainer;

    document.addEventListener('DOMContentLoaded', init);

    // ── CodeMirror helpers ──────────────────────────────────────────
    function getEditorContent() {
        var view = window.dslCmView;
        return view ? view.state.doc.toString() : '';
    }

    function setEditorContent(text) {
        var view = window.dslCmView;
        if (view) {
            view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text } });
        }
    }

    // Expose format callback so the CodeMirror Shift+Alt+F keymap can call it
    window.dslFormatContent = function () { formatDsl(); };

    function init() {
        editorContainer   = document.getElementById('dslEditorContainer');
        parseBtn          = document.getElementById('dslParseBtn');
        validateBtn       = document.getElementById('dslValidateBtn');
        formatBtn         = document.getElementById('dslFormatBtn');
        commitBtn         = document.getElementById('dslCommitBtn');
        materializeBtn    = document.getElementById('dslMaterializeBtn');
        materializeIncrBtn = document.getElementById('dslMaterializeIncrBtn');
        loadCurrentBtn    = document.getElementById('dslLoadCurrentBtn');
        branchSelect      = document.getElementById('dslBranchSelect');
        newBranchBtn      = document.getElementById('dslNewBranchBtn');
        mergeBtn          = document.getElementById('dslMergeBtn');
        authorInput       = document.getElementById('dslAuthorInput');
        messageInput      = document.getElementById('dslMessageInput');
        validationOutput  = document.getElementById('dslValidationOutput');
        historyBody       = document.getElementById('dslHistoryBody');
        diffOutput        = document.getElementById('dslDiffOutput');
        statusArea        = document.getElementById('dslStatusArea');

        if (!editorContainer) return; // tab not rendered yet

        // Bind buttons
        if (parseBtn)          parseBtn.addEventListener('click', parseDsl);
        if (validateBtn)       validateBtn.addEventListener('click', validateDsl);
        if (formatBtn)         formatBtn.addEventListener('click', formatDsl);
        if (commitBtn)         commitBtn.addEventListener('click', commitDsl);
        if (materializeBtn)    materializeBtn.addEventListener('click', materializeDsl);
        if (materializeIncrBtn) materializeIncrBtn.addEventListener('click', materializeIncremental);
        if (loadCurrentBtn)    loadCurrentBtn.addEventListener('click', loadCurrent);
        if (newBranchBtn)      newBranchBtn.addEventListener('click', createBranch);
        if (mergeBtn)          mergeBtn.addEventListener('click', mergeBranch);
        if (branchSelect)      branchSelect.addEventListener('change', onBranchChange);

        // Initial load — defer content load until CodeMirror is ready
        loadBranches();
        if (window.dslCmView) {
            loadCurrent();
        } else {
            editorContainer.addEventListener('cm-ready', loadCurrent, { once: true });
        }
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
                setEditorContent(text);
                showStatus('Loaded current architecture DSL (' + text.length + ' chars)', 'success');
            })
            .catch(function (e) { showStatus('Failed to load: ' + e.message, 'error'); });
    }

    // ── Parse ───────────────────────────────────────────────────────
    function parseDsl() {
        showStatus('Parsing…', 'info');
        fetch('/api/dsl/parse', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: getEditorContent()
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
        showStatus('Validating…', 'info');
        fetch('/api/dsl/validate', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: getEditorContent()
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                renderValidation(data);
                showStatus(data.valid ? '✅ Valid DSL' : '❌ Validation errors', data.valid ? 'success' : 'error');
            })
            .catch(function (e) { showStatus('Validate error: ' + e.message, 'error'); });
    }

    // ── Format ──────────────────────────────────────────────────────
    function formatDsl() {
        showStatus('Formatting…', 'info');
        fetch('/api/dsl/format', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: getEditorContent()
        })
            .then(function (r) { return r.text(); })
            .then(function (formatted) {
                setEditorContent(formatted);
                showStatus('✨ Formatted', 'success');
            })
            .catch(function (e) { showStatus('Format error: ' + e.message, 'error'); });
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
            body: getEditorContent()
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
        var branch = branchSelect ? branchSelect.value : '';
        showStatus('Materializing…', 'info');
        var url = '/api/dsl/materialize';
        if (branch) url += '?branch=' + encodeURIComponent(branch);
        fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: getEditorContent()
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
            .then(function (response) {
                var docs = response.commits || response;
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
            .then(function (response) {
                var docs = response.commits || response;
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
            var commitShort = d.commitId ? d.commitId.substring(0, 8) : '—';
            html += '<tr>';
            html += '<td><code class="small">' + escapeHtml(commitShort) + '</code></td>';
            html += '<td class="small">' + escapeHtml(d.branch || '') + '</td>';
            html += '<td class="small">' + escapeHtml(ts) + '</td>';
            html += '<td>';
            if (d.commitId) {
                html += '<button class="btn btn-sm btn-outline-secondary me-1 dsl-load-commit" data-commit-id="' + escapeHtml(d.commitId) + '" title="Load this version">📂</button>';
                html += '<button class="btn btn-sm btn-outline-warning me-1 dsl-cherry-pick-btn" data-commit-id="' + escapeHtml(d.commitId) + '" title="Cherry-pick this commit onto another branch">🍒</button>';
            }
            if (i < docs.length - 1 && d.commitId && docs[i + 1].commitId) {
                html += '<button class="btn btn-sm btn-outline-info dsl-diff-btn" data-before="' + escapeHtml(docs[i + 1].commitId) + '" data-after="' + escapeHtml(d.commitId) + '" title="Diff with previous">🔍</button>';
            }
            html += '</td>';
            html += '</tr>';
        });
        historyBody.innerHTML = html;

        // Bind load-commit buttons (reads from JGit)
        historyBody.querySelectorAll('.dsl-load-commit').forEach(function (btn) {
            btn.addEventListener('click', function () {
                loadCommitById(this.getAttribute('data-commit-id'));
            });
        });

        // Bind diff buttons (uses Git commit SHAs)
        historyBody.querySelectorAll('.dsl-diff-btn').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var before = this.getAttribute('data-before');
                var after = this.getAttribute('data-after');
                loadDiff(before, after);
            });
        });

        // Bind cherry-pick buttons
        historyBody.querySelectorAll('.dsl-cherry-pick-btn').forEach(function (btn) {
            btn.addEventListener('click', function () {
                cherryPickCommit(this.getAttribute('data-commit-id'));
            });
        });
    }

    function loadCommitById(commitId) {
        showStatus('Loading commit ' + commitId.substring(0, 8) + '…', 'info');
        fetch('/api/dsl/git/commit/' + encodeURIComponent(commitId))
            .then(function (r) {
                if (!r.ok) throw new Error('Commit not found');
                return r.json();
            })
            .then(function (data) {
                if (data.dslText) {
                    setEditorContent(data.dslText);
                    showStatus('Loaded commit ' + commitId.substring(0, 8), 'success');
                } else {
                    showStatus('Commit ' + commitId.substring(0, 8) + ' has no DSL content', 'error');
                }
            })
            .catch(function (e) { showStatus('Load error: ' + e.message, 'error'); });
    }

    // ── Diff ────────────────────────────────────────────────────────
    function loadDiff(beforeId, afterId) {
        if (!diffOutput) return;
        showStatus('Computing diff…', 'info');
        fetch('/api/dsl/diff/' + encodeURIComponent(beforeId) + '/' + encodeURIComponent(afterId))
            .then(function (r) { return r.json(); })
            .then(function (data) {
                renderDiff(data, beforeId, afterId);
                showStatus('Diff: ' + data.totalChanges + ' changes', 'success');
            })
            .catch(function (e) {
                diffOutput.innerHTML = '<span class="text-danger">Diff failed: ' + escapeHtml(e.message) + '</span>';
                showStatus('Diff error: ' + e.message, 'error');
            });
    }

    function loadTextDiff(beforeId, afterId) {
        if (!diffOutput) return;
        showStatus('Computing text diff…', 'info');
        fetch('/api/dsl/diff/text/' + encodeURIComponent(beforeId) + '/' + encodeURIComponent(afterId))
            .then(function (r) {
                if (!r.ok) throw new Error('Text diff failed');
                return r.text();
            })
            .then(function (patch) {
                var html = '<div class="d-flex justify-content-between align-items-center mb-2">';
                html += '<strong class="small">Unified Diff</strong>';
                html += '<button class="btn btn-sm btn-outline-info dsl-semantic-diff-toggle" data-before="' + escapeHtml(beforeId) + '" data-after="' + escapeHtml(afterId) + '">Semantic Diff</button>';
                html += '</div>';
                html += '<pre class="small bg-light p-2 rounded" style="max-height:400px; overflow:auto; white-space:pre-wrap;">' + escapeHtml(patch || '(no changes)') + '</pre>';
                diffOutput.innerHTML = html;
                diffOutput.querySelector('.dsl-semantic-diff-toggle').addEventListener('click', function () {
                    loadDiff(this.getAttribute('data-before'), this.getAttribute('data-after'));
                });
                showStatus('Text diff loaded', 'success');
            })
            .catch(function (e) {
                diffOutput.innerHTML = '<span class="text-danger">Text diff failed: ' + escapeHtml(e.message) + '</span>';
                showStatus('Text diff error: ' + e.message, 'error');
            });
    }

    function renderDiff(data, beforeId, afterId) {
        if (!diffOutput) return;
        if (data.isEmpty) {
            diffOutput.innerHTML = '<span class="text-muted">No changes</span>';
            return;
        }
        var html = '<div class="small">';

        // Toggle button for text diff view
        if (beforeId && afterId) {
            html += '<div class="d-flex justify-content-between align-items-center mb-2">';
            html += '<strong>' + data.totalChanges + ' change(s)</strong>';
            html += '<button class="btn btn-sm btn-outline-secondary dsl-text-diff-toggle" data-before="' + escapeHtml(beforeId) + '" data-after="' + escapeHtml(afterId) + '">Text Diff</button>';
            html += '</div>';
        } else {
            html += '<strong>' + data.totalChanges + ' change(s)</strong><br>';
        }

        if (data.addedElements > 0) html += '<span class="text-success"><span aria-hidden="true">✅</span> + ' + data.addedElements + ' element(s) added</span><br>';
        if (data.removedElements > 0) html += '<span class="text-danger"><span aria-hidden="true">❌</span> − ' + data.removedElements + ' element(s) removed</span><br>';
        if (data.changedElements > 0) html += '<span class="text-warning"><span aria-hidden="true">⚠️</span> ~ ' + data.changedElements + ' element(s) changed</span><br>';
        if (data.addedRelations > 0) html += '<span class="text-success"><span aria-hidden="true">✅</span> + ' + data.addedRelations + ' relation(s) added</span><br>';
        if (data.removedRelations > 0) html += '<span class="text-danger"><span aria-hidden="true">❌</span> − ' + data.removedRelations + ' relation(s) removed</span><br>';
        if (data.changedRelations > 0) html += '<span class="text-warning"><span aria-hidden="true">⚠️</span> ~ ' + data.changedRelations + ' relation(s) changed</span><br>';

        // Show details if present
        if (data.details) {
            var d = data.details;
            if (d.addedElements && d.addedElements.length > 0) {
                html += '<details class="mt-2"><summary class="text-success"><span aria-hidden="true">+</span> Added Elements (' + d.addedElements.length + ')</summary><ul>';
                d.addedElements.forEach(function (e) {
                    html += '<li><code>' + escapeHtml(e.id) + '</code> ' + escapeHtml(e.title || '') + ' <small class="text-muted">(' + escapeHtml(e.type || '') + ')</small></li>';
                });
                html += '</ul></details>';
            }
            if (d.removedElements && d.removedElements.length > 0) {
                html += '<details class="mt-1"><summary class="text-danger"><span aria-hidden="true">−</span> Removed Elements (' + d.removedElements.length + ')</summary><ul>';
                d.removedElements.forEach(function (e) {
                    html += '<li><code>' + escapeHtml(e.id) + '</code> ' + escapeHtml(e.title || '') + '</li>';
                });
                html += '</ul></details>';
            }
            if (d.addedRelations && d.addedRelations.length > 0) {
                html += '<details class="mt-1"><summary class="text-success"><span aria-hidden="true">+</span> Added Relations (' + d.addedRelations.length + ')</summary><ul>';
                d.addedRelations.forEach(function (r) {
                    html += '<li><code>' + escapeHtml(r.sourceId) + '</code> —[' + escapeHtml(r.relationType) + ']→ <code>' + escapeHtml(r.targetId) + '</code></li>';
                });
                html += '</ul></details>';
            }
            if (d.removedRelations && d.removedRelations.length > 0) {
                html += '<details class="mt-1"><summary class="text-danger"><span aria-hidden="true">−</span> Removed Relations (' + d.removedRelations.length + ')</summary><ul>';
                d.removedRelations.forEach(function (r) {
                    html += '<li><code>' + escapeHtml(r.sourceId) + '</code> —[' + escapeHtml(r.relationType) + ']→ <code>' + escapeHtml(r.targetId) + '</code></li>';
                });
                html += '</ul></details>';
            }
        }
        html += '</div>';
        diffOutput.innerHTML = html;

        // Bind text diff toggle
        var toggleBtn = diffOutput.querySelector('.dsl-text-diff-toggle');
        if (toggleBtn) {
            toggleBtn.addEventListener('click', function () {
                loadTextDiff(this.getAttribute('data-before'), this.getAttribute('data-after'));
            });
        }
    }

    // ── Cherry-pick ───────────────────────────────────────────────
    function cherryPickCommit(commitId) {
        var targetBranch = prompt('Cherry-pick commit ' + commitId.substring(0, 8) + ' onto which branch?', 'review');
        if (!targetBranch || !targetBranch.trim()) return;
        targetBranch = targetBranch.trim();

        // Use preview dialog if available
        if (window.TaxonomyActionGuards) {
            window.TaxonomyActionGuards.showCherryPickPreview(commitId, targetBranch, function () {
                executeCherryPick(commitId, targetBranch);
            });
        } else {
            executeCherryPick(commitId, targetBranch);
        }
    }

    function executeCherryPick(commitId, targetBranch) {
        showStatus('Cherry-picking ' + commitId.substring(0, 8) + ' onto "' + targetBranch + '"…', 'info');
        fetch('/api/dsl/cherry-pick?commitId=' + encodeURIComponent(commitId) + '&targetBranch=' + encodeURIComponent(targetBranch), {
            method: 'POST'
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.error) {
                    showStatus('Cherry-pick failed: ' + data.error, 'error');
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError('Cherry-Pick Failed', data.error);
                    }
                    return;
                }
                showStatus('✅ Cherry-picked onto "' + data.targetBranch + '" → ' + data.commitId.substring(0, 8), 'success');
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showSuccess('Cherry-Pick Successful',
                        'Applied onto "' + data.targetBranch + '" → ' + data.commitId.substring(0, 8));
                }
                loadBranches();
            })
            .catch(function (e) { showStatus('Cherry-pick error: ' + e.message, 'error'); });
    }

    // ── Merge ───────────────────────────────────────────────────────
    function mergeBranch() {
        var fromBranch = branchSelect ? branchSelect.value : 'draft';
        var intoBranch = prompt('Merge branch "' + fromBranch + '" into which branch?', 'accepted');
        if (!intoBranch || !intoBranch.trim()) return;
        intoBranch = intoBranch.trim();

        // Use preview dialog if available
        if (window.TaxonomyActionGuards) {
            window.TaxonomyActionGuards.showMergePreview(fromBranch, intoBranch, function () {
                executeMerge(fromBranch, intoBranch);
            });
        } else {
            executeMerge(fromBranch, intoBranch);
        }
    }

    function executeMerge(fromBranch, intoBranch) {
        showStatus('Merging "' + fromBranch + '" into "' + intoBranch + '"…', 'info');
        fetch('/api/dsl/merge?fromBranch=' + encodeURIComponent(fromBranch) + '&intoBranch=' + encodeURIComponent(intoBranch), {
            method: 'POST'
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.error) {
                    showStatus('Merge failed: ' + data.error, 'error');
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError('Merge Failed', data.error);
                    }
                    return;
                }
                showStatus('✅ Merged "' + data.fromBranch + '" into "' + data.intoBranch + '" → ' + data.commitId.substring(0, 8), 'success');
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showSuccess('Merge Successful',
                        'Merged "' + data.fromBranch + '" into "' + data.intoBranch + '" → ' + data.commitId.substring(0, 8));
                }
                loadBranches();
            })
            .catch(function (e) { showStatus('Merge error: ' + e.message, 'error'); });
    }

    // ── Util ────────────────────────────────────────────────────────
    var escapeHtml = TaxonomyUtils.escapeHtml;
}());
