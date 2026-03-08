/* taxonomy.js – frontend logic for NATO NC3T Taxonomy Browser */

(function () {
    'use strict';

    let taxonomyData = [];
    let currentScores = null;
    let currentReasons = {};   // code → reason string
    let currentView = 'list'; // 'list' | 'tabs' | 'sunburst' | 'tree' | 'decision'
    let currentTreeRoot = 'BP'; // code of the taxonomy shown in tree view

    // ── Interactive mode state ─────────────────────────────────────────────────
    let interactiveMode = true;       // ON by default
    let storedBusinessText = null;    // stored when user clicks Analyze in interactive mode
    let evaluatedNodes = new Set();   // track which parent nodes have been evaluated
    let lastAnalyzedText = null;      // text that was most recently analyzed successfully

    // ── Proposal state ────────────────────────────────────────────────────────
    let currentProposalFilter = 'PENDING';
    let pendingProposalNodeCode = null; // node code for propose modal

    // ── Bootstrap ─────────────────────────────────────────────────────────────
    document.addEventListener('DOMContentLoaded', function () {
        loadTaxonomy();
        checkAiStatus();
        // Poll AI status every 30 seconds to keep the indicator current
        setInterval(checkAiStatus, 30000);
        document.getElementById('analyzeBtn').addEventListener('click', function () {
            const interactiveCb = document.getElementById('interactiveMode');
            interactiveMode = interactiveCb ? interactiveCb.checked : false;
            if (interactiveMode) {
                // Switch to list view automatically so interactive expand/collapse works
                if (currentView !== 'list' && currentView !== 'tabs') {
                    switchView('list');
                }
                runInteractiveAnalysis();
            } else {
                if (currentView === 'list' || currentView === 'tabs') {
                    runStreamingAnalysis();
                } else {
                    runAnalysis();
                }
            }
        });

        // Sync interactive mode checkbox state
        const interactiveCb = document.getElementById('interactiveMode');
        if (interactiveCb) {
            interactiveCb.addEventListener('change', function () {
                interactiveMode = interactiveCb.checked;
            });
        }

        document.getElementById('expandAll').addEventListener('click', expandAll);
        document.getElementById('collapseAll').addEventListener('click', collapseAll);

        // Description visibility toggle
        const showDescriptionsChk = document.getElementById('showDescriptions');
        if (showDescriptionsChk) {
            applyDescriptionVisibility(showDescriptionsChk.checked);
            showDescriptionsChk.addEventListener('change', function () {
                applyDescriptionVisibility(this.checked);
            });
        }

        // Taxonomy root selector (tree view)
        const treeRootSelect = document.getElementById('treeRootSelect');
        if (treeRootSelect) {
            treeRootSelect.addEventListener('change', function () {
                currentTreeRoot = this.value;
                renderView(taxonomyData, currentScores);
            });
        }

        // View switcher buttons
        ['viewList', 'viewTabs', 'viewSunburst', 'viewTree', 'viewDecision'].forEach(function (id) {
            const btn = document.getElementById(id);
            if (btn) {
                btn.addEventListener('click', function () {
                    switchView(btn.dataset.view);
                });
            }
        });

        // Prompt template editor
        initPromptEditor();

        // Clear LLM communication log
        const clearLlmLogBtn = document.getElementById('clearLlmLog');
        if (clearLlmLogBtn) {
            clearLlmLogBtn.addEventListener('click', function () {
                const content = document.getElementById('llmCommLogContent');
                if (content) {
                    content.innerHTML = '<div class="text-muted p-2">No LLM calls yet. Use Interactive Mode and expand nodes to see communication.</div>';
                }
            });
        }

        // Export buttons
        ['exportSvg', 'exportPng', 'exportPdf', 'exportCsv', 'exportJson', 'exportVisio', 'exportArchiMate'].forEach(function (id) {
            const btn = document.getElementById(id);
            if (btn) {
                btn.addEventListener('click', function () { handleExport(id); });
            }
        });

        // Import JSON button
        const importJsonBtn = document.getElementById('importJson');
        const importJsonFile = document.getElementById('importJsonFile');
        if (importJsonBtn && importJsonFile) {
            importJsonBtn.addEventListener('click', function () {
                importJsonFile.value = '';
                importJsonFile.click();
            });
            importJsonFile.addEventListener('change', function () {
                const file = importJsonFile.files && importJsonFile.files[0];
                if (!file) { return; }
                const reader = new FileReader();
                reader.onload = function (e) {
                    const jsonText = e.target.result;
                    fetch('/api/scores/import', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: jsonText
                    })
                    .then(function (resp) { return resp.json(); })
                    .then(function (data) {
                        if (data.error) {
                            showStatus('danger', '❌ Import failed: ' + data.error);
                            return;
                        }
                        currentScores = data.scores || {};
                        currentReasons = data.reasons || {};
                        // Update business text field if present in the imported data
                        if (data.requirement) {
                            const btEl = document.getElementById('businessText');
                            if (btEl) { btEl.value = data.requirement; }
                            storedBusinessText = data.requirement;
                            lastAnalyzedText   = data.requirement;
                        }
                        // Render tree with imported scores
                        renderView(taxonomyData, currentScores);
                        updateExportGroupVisibility();
                        const scored = Object.keys(currentScores).length;
                        let msg = '✅ Loaded analysis: ' + scored + ' node(s) scored.';
                        if (data.warnings && data.warnings.length > 0) {
                            msg += ' ⚠️ ' + data.warnings.join('; ');
                        }
                        showStatus(data.warnings && data.warnings.length > 0 ? 'warning' : 'success', msg);
                    })
                    .catch(function (err) {
                        showStatus('danger', '❌ Import failed: ' + err.message);
                    });
                };
                reader.readAsText(file);
            });
        }

        // Dark mode toggle
        const darkModeBtn = document.getElementById('darkModeToggle');
        if (darkModeBtn) {
            const savedTheme = localStorage.getItem('taxonomy-theme');
            if (savedTheme === 'dark') {
                document.documentElement.setAttribute('data-bs-theme', 'dark');
                darkModeBtn.textContent = '☀️';
            }
            darkModeBtn.addEventListener('click', function () {
                const isDark = document.documentElement.getAttribute('data-bs-theme') === 'dark';
                if (isDark) {
                    document.documentElement.removeAttribute('data-bs-theme');
                    darkModeBtn.textContent = '🌙';
                    localStorage.setItem('taxonomy-theme', 'light');
                } else {
                    document.documentElement.setAttribute('data-bs-theme', 'dark');
                    darkModeBtn.textContent = '☀️';
                    localStorage.setItem('taxonomy-theme', 'dark');
                }
            });
        }

        // Diagnostics panel
        checkAdminStatus();
        initAdminModal();
        const refreshDiagBtn = document.getElementById('refreshDiagnostics');
        if (refreshDiagBtn) {
            refreshDiagBtn.addEventListener('click', loadDiagnostics);
        }
        const testLlmBtn = document.getElementById('testLlmConnection');
        if (testLlmBtn) {
            testLlmBtn.addEventListener('click', testLlmConnection);
        }

        // Proposal review panel — load on page start
        loadProposals('PENDING');
        ['filterPending', 'filterAll', 'filterAccepted', 'filterRejected'].forEach(function (id) {
            const btn = document.getElementById(id);
            if (btn) {
                btn.addEventListener('click', function () {
                    const filter = btn.dataset.filter;
                    currentProposalFilter = filter;
                    // Update active button styling
                    ['filterPending', 'filterAll', 'filterAccepted', 'filterRejected'].forEach(function (bid) {
                        const b = document.getElementById(bid);
                        if (!b) return;
                        const isPending   = bid === 'filterPending';
                        const isAccepted  = bid === 'filterAccepted';
                        const isRejected  = bid === 'filterRejected';
                        const isActive    = bid === id;
                        b.setAttribute('aria-pressed', isActive ? 'true' : 'false');
                        if (isPending) {
                            b.className = isActive ? 'btn btn-warning' : 'btn btn-outline-warning';
                        } else if (isAccepted) {
                            b.className = isActive ? 'btn btn-success' : 'btn btn-outline-success';
                        } else if (isRejected) {
                            b.className = isActive ? 'btn btn-danger' : 'btn btn-outline-danger';
                        } else {
                            b.className = isActive ? 'btn btn-secondary' : 'btn btn-outline-secondary';
                        }
                    });
                    loadProposals(filter);
                });
            }
        });

        // Propose Relations modal submit
        const proposeSubmitBtn = document.getElementById('proposeRelationsSubmit');
        if (proposeSubmitBtn) {
            proposeSubmitBtn.addEventListener('click', function () {
                const relationType = document.getElementById('proposeRelationType').value;
                if (pendingProposalNodeCode && relationType) {
                    proposeRelationsForNode(pendingProposalNodeCode, relationType);
                }
            });
        }

        // Warn when business text changes after analysis results are displayed
        let staleDebounceTimer = null;
        const businessTextEl = document.getElementById('businessText');
        if (businessTextEl) {
            businessTextEl.addEventListener('input', function () {
                clearTimeout(staleDebounceTimer);
                staleDebounceTimer = setTimeout(function () {
                    const hasScores = currentScores !== null &&
                        typeof currentScores === 'object' &&
                        Object.keys(currentScores).length > 0;
                    if (!hasScores) {
                        businessTextEl.classList.remove('stale-results');
                        return;
                    }
                    if (lastAnalyzedText !== null && businessTextEl.value !== lastAnalyzedText) {
                        businessTextEl.classList.add('stale-results');
                        showStatus('warning', '⚠️ Business text has changed — previous results are no longer valid.');
                        const statusArea = document.getElementById('statusArea');
                        if (statusArea) {
                            const alertEl = statusArea.querySelector('.alert');
                            if (alertEl && !alertEl.querySelector('.btn-warning')) {
                                const resetBtn = document.createElement('button');
                                resetBtn.className = 'btn btn-sm btn-warning ms-2';
                                resetBtn.textContent = '🔄 Reset Results';
                                resetBtn.addEventListener('click', resetStaleResults);
                                alertEl.appendChild(resetBtn);
                            }
                        }
                    } else {
                        businessTextEl.classList.remove('stale-results');
                        clearStatus();
                    }
                }, 300);
            });
        }
    });

    function applyDescriptionVisibility(show) {
        const tree = document.getElementById('taxonomyTree');
        if (!tree) { return; }
        tree.classList.toggle('hide-descriptions', !show);
    }

    // ── Admin mode ───────────────────────────────────────────────────────────
    let adminPasswordRequired = false;

    function isAdminMode() {
        return !!sessionStorage.getItem('adminToken');
    }

    function getAdminToken() {
        return sessionStorage.getItem('adminToken') || '';
    }

    function unlockAdmin(password) {
        return fetch('/api/admin/verify', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ password: password })
        })
        .then(r => r.json())
        .then(data => {
            if (data.valid) {
                sessionStorage.setItem('adminToken', password);
                updateAdminVisibility();
            }
            return data.valid;
        });
    }

    function lockAdmin() {
        sessionStorage.removeItem('adminToken');
        updateAdminVisibility();
    }

    function updateAdminVisibility() {
        const body = document.body;
        const lockBtn = document.getElementById('adminLockBtn');
        if (!adminPasswordRequired) {
            // No password configured — show everything, no lock button
            body.classList.remove('admin-unlocked');
            document.querySelectorAll('.admin-only').forEach(el => {
                el.style.display = '';
            });
            if (lockBtn) { lockBtn.classList.add('d-none'); }
            return;
        }
        if (isAdminMode()) {
            body.classList.add('admin-unlocked');
            if (lockBtn) {
                lockBtn.textContent = '🔓';
                lockBtn.title = 'Admin mode active — click to lock';
                lockBtn.classList.remove('d-none');
            }
        } else {
            body.classList.remove('admin-unlocked');
            if (lockBtn) {
                lockBtn.textContent = '🔐';
                lockBtn.title = 'Click to unlock admin mode';
                lockBtn.classList.remove('d-none');
            }
        }
    }

    function checkAdminStatus() {
        fetch('/api/admin/status')
            .then(r => r.json())
            .then(data => {
                adminPasswordRequired = data.passwordRequired === true;
                if (adminPasswordRequired && isAdminMode()) {
                    // Re-verify silently on page reload
                    fetch('/api/admin/verify', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ password: getAdminToken() })
                    })
                    .then(r => r.json())
                    .then(result => {
                        if (!result.valid) {
                            // Token no longer valid — clear it
                            sessionStorage.removeItem('adminToken');
                        }
                        updateAdminVisibility();
                        if (isAdminMode()) { loadDiagnostics(); }
                    })
                    .catch(() => { updateAdminVisibility(); });
                } else {
                    updateAdminVisibility();
                    if (!adminPasswordRequired) { loadDiagnostics(); }
                }
            })
            .catch(() => {
                // If status check fails, treat as no password required
                adminPasswordRequired = false;
                updateAdminVisibility();
                loadDiagnostics();
            });
    }

    function initAdminModal() {
        const lockBtn = document.getElementById('adminLockBtn');
        if (!lockBtn) { return; }
        lockBtn.addEventListener('click', function () {
            const modal = document.getElementById('adminModal');
            if (!modal) { return; }
            const unlockForm = document.getElementById('adminUnlockForm');
            const lockedInfo = document.getElementById('adminLockedInfo');
            const unlockBtn = document.getElementById('adminUnlockBtn');
            const lockConfirmBtn = document.getElementById('adminLockConfirmBtn');
            const passwordInput = document.getElementById('adminPasswordInput');
            const errorMsg = document.getElementById('adminPasswordError');
            if (isAdminMode()) {
                // Show "lock" view
                if (unlockForm) { unlockForm.classList.add('d-none'); }
                if (lockedInfo) { lockedInfo.classList.remove('d-none'); }
                if (unlockBtn) { unlockBtn.classList.add('d-none'); }
                if (lockConfirmBtn) { lockConfirmBtn.classList.remove('d-none'); }
            } else {
                // Show "unlock" view
                if (unlockForm) { unlockForm.classList.remove('d-none'); }
                if (lockedInfo) { lockedInfo.classList.add('d-none'); }
                if (unlockBtn) { unlockBtn.classList.remove('d-none'); }
                if (lockConfirmBtn) { lockConfirmBtn.classList.add('d-none'); }
                if (errorMsg) { errorMsg.classList.add('d-none'); }
                if (passwordInput) { passwordInput.value = ''; }
            }
            const bsModal = new bootstrap.Modal(modal);
            bsModal.show();
        });

        const unlockBtn = document.getElementById('adminUnlockBtn');
        if (unlockBtn) {
            unlockBtn.addEventListener('click', function () {
                const passwordInput = document.getElementById('adminPasswordInput');
                const errorMsg = document.getElementById('adminPasswordError');
                const password = passwordInput ? passwordInput.value : '';
                unlockAdmin(password).then(function (valid) {
                    if (valid) {
                        const modal = bootstrap.Modal.getInstance(document.getElementById('adminModal'));
                        if (modal) { modal.hide(); }
                        loadDiagnostics();
                    } else {
                        if (errorMsg) { errorMsg.classList.remove('d-none'); }
                        if (passwordInput) { passwordInput.focus(); }
                    }
                });
            });
        }

        const lockConfirmBtn = document.getElementById('adminLockConfirmBtn');
        if (lockConfirmBtn) {
            lockConfirmBtn.addEventListener('click', function () {
                lockAdmin();
                const modal = bootstrap.Modal.getInstance(document.getElementById('adminModal'));
                if (modal) { modal.hide(); }
            });
        }

        // Allow Enter key to submit password
        const passwordInput = document.getElementById('adminPasswordInput');
        if (passwordInput) {
            passwordInput.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') {
                    const unlockBtnEl = document.getElementById('adminUnlockBtn');
                    if (unlockBtnEl) { unlockBtnEl.click(); }
                }
            });
        }
    }

    // ── Check AI availability ─────────────────────────────────────────────────
    function checkAiStatus() {
        fetch('/api/ai-status')
            .then(r => r.json())
            .then(status => {
                const btn = document.getElementById('analyzeBtn');
                const infoEl = document.getElementById('aiProviderInfo');
                const badge = document.getElementById('aiStatusBadge');
                if (status.available) {
                    btn.disabled = false;
                    const aiWarn = document.getElementById('aiUnavailableWarning');
                    if (aiWarn) aiWarn.classList.add('d-none');
                    if (!adminPasswordRequired || isAdminMode()) {
                        infoEl.textContent = 'Using: ' + status.provider;
                        infoEl.classList.remove('d-none');
                    } else {
                        infoEl.classList.add('d-none');
                    }
                    if (badge) {
                        if (!adminPasswordRequired || isAdminMode()) {
                            badge.textContent = '🟢 AI: ' + status.provider;
                            badge.title = 'AI is available (' + status.provider + ')';
                        } else {
                            badge.textContent = '🟢 AI: Ready';
                            badge.title = 'AI is available';
                        }
                        badge.className = 'badge bg-success ms-auto me-2 fs-6';
                    }
                } else {
                    btn.disabled = true;
                    infoEl.classList.add('d-none');
                    const aiWarn = document.getElementById('aiUnavailableWarning');
                    if (aiWarn) aiWarn.classList.remove('d-none');
                    if (badge) {
                        badge.textContent = '🔴 AI: Unavailable';
                        badge.className = 'badge bg-danger ms-auto me-2 fs-6';
                        badge.title = 'AI is not available — no LLM API key configured';
                    }
                    showStatus('warning',
                        '⚠️ AI analysis is not available — no LLM API key is configured. ' +
                        'Set one of the following environment variables: GEMINI_API_KEY, ' +
                        'OPENAI_API_KEY, DEEPSEEK_API_KEY, DASHSCOPE_API_KEY, LLAMA_API_KEY, ' +
                        'or MISTRAL_API_KEY.');
                }
            })
            .catch(() => {
                // If the status check fails, reset to a neutral unknown state.
                const btn = document.getElementById('analyzeBtn');
                if (btn) btn.disabled = false;
                const aiWarn = document.getElementById('aiUnavailableWarning');
                if (aiWarn) aiWarn.classList.add('d-none');
                const badge = document.getElementById('aiStatusBadge');
                if (badge) {
                    badge.textContent = '⚠️ AI: Unknown';
                    badge.className = 'badge bg-warning text-dark ms-auto me-2 fs-6';
                    badge.title = 'AI status could not be determined';
                }
            });
    }

    // ── LLM Diagnostics ───────────────────────────────────────────────────────
    function loadDiagnostics() {
        if (adminPasswordRequired && !isAdminMode()) { return; }
        const content = document.getElementById('diagnosticsContent');
        if (!content) { return; }
        content.innerHTML = '<div class="text-muted">Loading…</div>';
        const headers = { 'Accept': 'application/json' };
        if (isAdminMode()) { headers['X-Admin-Token'] = getAdminToken(); }
        fetch('/api/diagnostics', { headers: headers })
            .then(r => {
                if (r.status === 401) {
                    if (content) { content.innerHTML = '<div class="text-muted">Admin authentication required.</div>'; }
                    return null;
                }
                return r.json();
            })
            .then(d => {
                if (!d) { return; }
                console.log('[Taxonomy] Diagnostics:', d);
                const keyStatus = d.apiKeyConfigured
                    ? '<span class="diag-status-ok">&#9989; Configured</span>' +
                      (d.apiKeyPrefix ? ' (<code>' + escapeHtml(d.apiKeyPrefix) + '</code>)' : '')
                    : '<span class="diag-status-err">&#10060; Not configured</span>';
                const lastCallHtml = d.lastCallTime
                    ? escapeHtml(d.lastCallTime) + ' — ' +
                      (d.lastCallSuccess
                          ? '<span class="diag-status-ok">&#9989; Success</span>'
                          : '<span class="diag-status-err">&#10060; Failed</span>')
                    : '<span class="text-muted">No calls yet</span>';
                const lastErrorHtml = d.lastError
                    ? '<div class="mt-1 llm-log-error-detail"><strong>Last error:</strong> ' +
                      escapeHtml(d.lastError) + '</div>'
                    : '';
                content.innerHTML =
                    '<table class="table table-sm table-borderless mb-0" style="font-size:0.9em;">' +
                    '<tr><td class="fw-semibold" style="width:140px">Provider</td><td>' + escapeHtml(d.provider || '—') + '</td></tr>' +
                    '<tr><td class="fw-semibold">API Key</td><td>' + keyStatus + '</td></tr>' +
                    '<tr><td class="fw-semibold">Last call</td><td>' + lastCallHtml + '</td></tr>' +
                    '<tr><td class="fw-semibold">Stats</td><td>' +
                        escapeHtml(String(d.totalCalls)) + ' total / ' +
                        '<span class="diag-status-ok">' + escapeHtml(String(d.successfulCalls)) + ' ok</span> / ' +
                        '<span class="diag-status-err">' + escapeHtml(String(d.failedCalls)) + ' failed</span>' +
                    '</td></tr>' +
                    '<tr><td class="fw-semibold">Server time</td><td>' + escapeHtml(d.serverTime || '—') + '</td></tr>' +
                    '</table>' +
                    lastErrorHtml;
            })
            .catch(err => {
                console.error('[Taxonomy] Failed to load diagnostics', err);
                if (content) { content.innerHTML = '<div class="text-danger">Failed to load diagnostics: ' + escapeHtml(err.message) + '</div>'; }
            });
    }

    function testLlmConnection() {
        const btn = document.getElementById('testLlmConnection');
        if (btn) { btn.disabled = true; btn.textContent = '⏳ Testing…'; }
        const testText = 'Test connection: business process management';
        fetch('/api/analyze-node?parentCode=BP&businessText=' + encodeURIComponent(testText))
            .then(r => r.json())
            .then(result => {
                console.log('[Taxonomy] Test connection result:', result);
                if (result.error) {
                    showStatus('warning', '⚠️ Test connection error: ' + result.error);
                } else {
                    showStatus('success', '✅ Test connection successful via ' + (result.provider || 'unknown provider') + '.');
                }
                loadDiagnostics();
            })
            .catch(err => {
                console.error('[Taxonomy] Test connection failed', err);
                showStatus('danger', '❌ Test connection failed: ' + err.message);
            })
            .finally(() => {
                if (btn) { btn.disabled = false; btn.textContent = '🧪 Test Connection'; }
            });
    }

    // ── Load taxonomy tree from API ───────────────────────────────────────────
    function loadTaxonomy() {
        fetch('/api/taxonomy')
            .then(r => r.json())
            .then(data => {
                taxonomyData = data;
                populateTreeRootSelect(data);
                renderView(data, null);
                // Populate Graph Explorer node suggestions
                if (window.TaxonomyGraph) {
                    window.TaxonomyGraph.populateNodeSuggestions(data);
                }
            })
            .catch(err => {
                document.getElementById('taxonomyTree').innerHTML =
                    '<div class="alert alert-danger">Failed to load taxonomy: ' + err + '</div>';
            });
    }

    function populateTreeRootSelect(data) {
        const sel = document.getElementById('treeRootSelect');
        if (!sel || !data || data.length === 0) { return; }
        sel.innerHTML = '';
        data.forEach(function (root) {
            const opt = document.createElement('option');
            opt.value = root.code;
            opt.textContent = root.name || root.code;
            sel.appendChild(opt);
        });
        // Ensure currentTreeRoot is valid; if not, default to first root
        if (data.some(r => r.code === currentTreeRoot)) {
            sel.value = currentTreeRoot;
        } else {
            currentTreeRoot = data[0].code;
            sel.value = currentTreeRoot;
        }
    }

    // ── View switching ────────────────────────────────────────────────────────
    function switchView(view) {
        currentView = view;

        // Update button active states
        const viewIds = { list: 'viewList', tabs: 'viewTabs', sunburst: 'viewSunburst', tree: 'viewTree', decision: 'viewDecision' };
        Object.entries(viewIds).forEach(([v, id]) => {
            const btn = document.getElementById(id);
            if (!btn) { return; }
            btn.classList.toggle('btn-primary', v === view);
            btn.classList.toggle('btn-outline-secondary', v !== view);
        });

        // Show/hide Expand All / Collapse All only for list & tabs views
        const ecGroup = document.getElementById('expandCollapseGroup');
        if (ecGroup) {
            ecGroup.style.display = (view === 'sunburst' || view === 'tree' || view === 'decision') ? 'none' : '';
        }

        // Show taxonomy root selector only in tree view
        const treeRootGroup = document.getElementById('treeRootGroup');
        if (treeRootGroup) {
            treeRootGroup.style.display = (view === 'tree') ? '' : 'none';
        }

        // Disable SVG/PNG export buttons for non-D3 views (list/tabs have no SVG)
        const svgViewActive = (view === 'sunburst' || view === 'tree' || view === 'decision');
        ['exportSvg', 'exportPng'].forEach(function (id) {
            const btn = document.getElementById(id);
            if (btn) { btn.disabled = !svgViewActive; }
        });

        renderView(taxonomyData, currentScores);
    }

    // ── Master render dispatcher ──────────────────────────────────────────────
    function renderView(data, scores) {
        if (!data || data.length === 0) { return; }
        switch (currentView) {
            case 'list':
                renderTree(data, scores);
                if (scores) { expandMatched(scores); }
                break;
            case 'tabs':
                renderTabsView(data, scores);
                if (scores) { expandMatched(scores); }
                break;
            case 'sunburst':
                if (window.TaxonomyViews) {
                    window.TaxonomyViews.renderSunburst(
                        document.getElementById('taxonomyTree'), data, scores);
                }
                break;
            case 'tree':
                if (window.TaxonomyViews) {
                    const treeRoot = data.find(r => r.code === currentTreeRoot) || data[0];
                    window.TaxonomyViews.renderTreeDiagram(
                        document.getElementById('taxonomyTree'), [treeRoot], scores);
                }
                break;
            case 'decision':
                if (window.TaxonomyViews) {
                    window.TaxonomyViews.renderDecisionMap(
                        document.getElementById('taxonomyTree'), data, scores);
                }
                break;
        }
        updateExportGroupVisibility();
    }

    // ── Export group visibility ───────────────────────────────────────────────
    function updateExportGroupVisibility() {
        const exportGroup = document.getElementById('exportGroup');
        const exportHint = document.getElementById('exportHint');
        if (!exportGroup) { return; }
        const hasScores = currentScores && Object.values(currentScores).some(v => v > 0);
        exportGroup.style.display = hasScores ? '' : 'none';
        if (exportHint) {
            exportHint.classList.toggle('d-none', hasScores);
        }
    }

    // ── Export handler ────────────────────────────────────────────────────────
    function handleExport(btnId) {
        if (btnId === 'exportCsv') {
            if (window.TaxonomyExport) {
                window.TaxonomyExport.exportCsv(currentScores, taxonomyData);
            }
            return;
        }
        if (btnId === 'exportPdf') {
            window.print();
            return;
        }
        if (btnId === 'exportSvg') {
            if (window.TaxonomyExport) {
                window.TaxonomyExport.exportSvg('taxonomyTree');
            }
            return;
        }
        if (btnId === 'exportPng') {
            if (window.TaxonomyExport) {
                window.TaxonomyExport.exportPng('taxonomyTree');
            }
            return;
        }
        if (btnId === 'exportVisio') {
            if (window.TaxonomyExport) {
                var bt = document.getElementById('businessText');
                window.TaxonomyExport.exportVisio(bt ? bt.value : '');
            }
            return;
        }
        if (btnId === 'exportArchiMate') {
            if (window.TaxonomyExport) {
                var bt = document.getElementById('businessText');
                window.TaxonomyExport.exportArchiMate(bt ? bt.value : '');
            }
            return;
        }
        if (btnId === 'exportJson') {
            if (window.TaxonomyExport) {
                var bt = document.getElementById('businessText');
                window.TaxonomyExport.exportJson(currentScores, currentReasons, bt ? bt.value : '', null);
            }
            return;
        }
    }

    // ── Render tree (list view) ───────────────────────────────────────────────
    function renderTree(nodes, scores) {
        const container = document.getElementById('taxonomyTree');
        cleanupD3(container);
        container.innerHTML = '';
        nodes.forEach(node => container.appendChild(buildNodeEl(node, scores)));
    }

    // ── Render tabbed list view ────────────────────────────────────────────────
    function renderTabsView(data, scores) {
        const container = document.getElementById('taxonomyTree');
        cleanupD3(container);
        container.innerHTML = '';

        const navUl = document.createElement('ul');
        navUl.className = 'nav nav-tabs tax-tabs mb-2';
        navUl.id = 'taxonomyTabsNav';
        navUl.setAttribute('role', 'tablist');

        const tabContent = document.createElement('div');
        tabContent.className = 'tab-content';

        data.forEach((rootNode, i) => {
            const paneId = 'tax-pane-' + rootNode.code;
            const tabId  = 'tax-tab-'  + rootNode.code;
            const isFirst = (i === 0);

            // Tab button
            const li = document.createElement('li');
            li.className = 'nav-item';
            li.setAttribute('role', 'presentation');

            const btn = document.createElement('button');
            btn.id = tabId;
            btn.className = 'nav-link' + (isFirst ? ' active' : '');
            btn.setAttribute('data-bs-toggle', 'tab');
            btn.setAttribute('data-bs-target', '#' + paneId);
            btn.setAttribute('type', 'button');
            btn.setAttribute('role', 'tab');
            btn.setAttribute('aria-controls', paneId);
            btn.setAttribute('aria-selected', isFirst ? 'true' : 'false');
            btn.textContent = rootNode.code + ' ' + rootNode.name;

            // Green underline when scored
            if (scores && scores[rootNode.code] > 0) {
                const alpha = Math.min(scores[rootNode.code] / 100, 1).toFixed(2);
                btn.style.borderBottom = '3px solid rgba(0,128,0,' + alpha + ')';
            }

            li.appendChild(btn);
            navUl.appendChild(li);

            // Tab pane – render the root's children (not the root row itself)
            const pane = document.createElement('div');
            pane.id = paneId;
            pane.className = 'tab-pane fade' + (isFirst ? ' show active' : '');
            pane.setAttribute('role', 'tabpanel');
            pane.setAttribute('aria-labelledby', tabId);

            // Render the root node's children in the pane; fall back to the root
            // itself if it has no children (edge case: a root with no sub-nodes).
            const children = rootNode.children && rootNode.children.length ? rootNode.children : [rootNode];
            children.forEach(child => pane.appendChild(buildNodeEl(child, scores)));
            tabContent.appendChild(pane);
        });

        container.appendChild(navUl);
        container.appendChild(tabContent);

        // Fallback tab switching – works even when Bootstrap JS is not available
        navUl.addEventListener('click', function (e) {
            const clickedBtn = e.target.closest('[data-bs-toggle="tab"]');
            if (!clickedBtn) { return; }
            e.preventDefault();
            navUl.querySelectorAll('.nav-link').forEach(b => {
                b.classList.remove('active');
                b.setAttribute('aria-selected', 'false');
            });
            tabContent.querySelectorAll('.tab-pane').forEach(p => p.classList.remove('show', 'active'));
            clickedBtn.classList.add('active');
            clickedBtn.setAttribute('aria-selected', 'true');
            const targetPane = tabContent.querySelector(clickedBtn.getAttribute('data-bs-target'));
            if (targetPane) { targetPane.classList.add('show', 'active'); }
        });
    }

    // ── Cleanup D3 resize observers ───────────────────────────────────────────
    function cleanupD3(container) {
        if (container._taxObserver) {
            container._taxObserver.disconnect();
            container._taxObserver = null;
        }
    }

    function buildNodeEl(node, scores) {
        const hasChildren = node.children && node.children.length > 0;
        const pct = scores ? (scores[node.code] !== undefined ? scores[node.code] : null) : null;
        const reason = currentReasons ? (currentReasons[node.code] || null) : null;

        const wrapper = document.createElement('div');
        wrapper.className = 'tax-node tax-level-' + node.level;
        wrapper.dataset.code = node.code;

        // Header row
        const header = document.createElement('div');
        header.className = 'tax-node-header';

        // Apply green background based on match percentage
        if (pct !== null && pct > 0) {
            const alpha = Math.min(pct / 100, 1).toFixed(2);
            header.style.backgroundColor = 'rgba(0,128,0,' + alpha + ')';
            if (pct >= 60) header.style.color = '#fff';
        }

        // Toggle arrow
        const toggle = document.createElement('span');
        toggle.className = 'tax-toggle';
        if (hasChildren) {
            toggle.textContent = '▶';
            toggle.addEventListener('click', function (e) {
                e.stopPropagation();
                toggleNode(wrapper, toggle);
            });
        }
        header.appendChild(toggle);

        // Code badge
        const code = document.createElement('span');
        code.className = 'tax-code';
        code.textContent = node.code;
        header.appendChild(code);

        // Name
        const name = document.createElement('span');
        name.className = 'tax-name';
        name.textContent = node.name;
        if (node.description) {
            name.title = node.description;
        }
        header.appendChild(name);

        // Percentage badge (if analysed)
        if (pct !== null) {
            const badge = document.createElement('span');
            badge.className = 'tax-pct';
            badge.textContent = pct + '%';
            header.appendChild(badge);
        }

        // Reason icon (if score > 0 and reason available)
        if (pct !== null && pct > 0 && reason) {
            const reasonIcon = document.createElement('span');
            reasonIcon.className = 'tax-reason-icon';
            reasonIcon.textContent = '💬';
            reasonIcon.title = reason;
            reasonIcon.setAttribute('aria-label', 'Reason: ' + reason);
            header.appendChild(reasonIcon);
        }

        wrapper.appendChild(header);

        // Visible description below the header row
        if (node.description) {
            const desc = document.createElement('div');
            desc.className = 'tax-description';
            desc.textContent = node.description;
            wrapper.appendChild(desc);
        }

        // Children container
        if (hasChildren) {
            const childContainer = document.createElement('div');
            childContainer.className = 'tax-children';
            childContainer.style.display = 'none'; // collapsed by default
            node.children.forEach(child => childContainer.appendChild(buildNodeEl(child, scores)));
            wrapper.appendChild(childContainer);
        }

        // Leaf justification button (leaf node with score > 0)
        if (!hasChildren && pct !== null && pct > 0 && storedBusinessText) {
            const justifyBtn = document.createElement('button');
            justifyBtn.className = 'btn btn-sm btn-outline-info tax-justify-btn';
            justifyBtn.textContent = '📋 Request Justification';
            justifyBtn.dataset.code = node.code;
            justifyBtn.addEventListener('click', function (e) {
                e.stopPropagation();
                requestLeafJustification(node.code, justifyBtn);
            });
            wrapper.appendChild(justifyBtn);
        }

        // Propose Relations button (always visible on every node)
        const proposeBtn = document.createElement('button');
        proposeBtn.className = 'btn btn-sm btn-outline-secondary tax-justify-btn proposal-btn';
        proposeBtn.textContent = '🔗 Propose Relations';
        proposeBtn.title = 'Generate relation proposals for ' + node.code;
        proposeBtn.setAttribute('aria-label', 'Propose relations for ' + node.code);
        proposeBtn.dataset.code = node.code;
        proposeBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            pendingProposalNodeCode = node.code;
            const codeEl = document.getElementById('proposeNodeCode');
            if (codeEl) { codeEl.textContent = node.code + ' — ' + node.name; }
            const modal = document.getElementById('proposeRelationsModal');
            if (modal && window.bootstrap) {
                new window.bootstrap.Modal(modal).show();
            }
        });
        wrapper.appendChild(proposeBtn);

        // Graph Explorer button (always visible on every node)
        const graphBtn = document.createElement('button');
        graphBtn.className = 'btn btn-sm btn-outline-secondary tax-justify-btn graph-explore-btn';
        graphBtn.textContent = '🔎 Graph';
        graphBtn.title = 'Explore upstream/downstream dependencies and failure impact for ' + node.code;
        graphBtn.setAttribute('aria-label', 'Open graph explorer for ' + node.code);
        graphBtn.dataset.code = node.code;
        graphBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            if (window.TaxonomyGraph) {
                window.TaxonomyGraph.openGraphExplorer(node.code);
            }
        });
        wrapper.appendChild(graphBtn);

        // Find Similar button (visible when embeddings are available)
        const similarBtn = document.createElement('button');
        similarBtn.className = 'btn btn-sm btn-outline-secondary tax-justify-btn search-similar-btn';
        similarBtn.textContent = '🔍 Similar';
        similarBtn.title = 'Find semantically similar nodes to ' + node.code;
        similarBtn.setAttribute('aria-label', 'Find similar nodes to ' + node.code);
        similarBtn.dataset.code = node.code;
        similarBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            if (window.TaxonomySearch) {
                window.TaxonomySearch.findSimilar(node.code);
            }
        });
        wrapper.appendChild(similarBtn);

        return wrapper;
    }

    function toggleNode(wrapper, toggleEl) {
        const children = wrapper.querySelector(':scope > .tax-children');
        if (!children) return;
        const isHidden = children.style.display === 'none';

        if (isHidden && interactiveMode && storedBusinessText) {
            const code = wrapper.dataset.code;
            if (!evaluatedNodes.has(code)) {
                evaluatedNodes.add(code);
                evaluateNodeChildren(code, wrapper, toggleEl);
                return; // don't toggle yet — wait for API response
            }
        }

        children.style.display = isHidden ? '' : 'none';
        toggleEl.textContent = isHidden ? '▼' : '▶';
    }

    function evaluateNodeChildren(parentCode, wrapper, toggle) {
        wrapper.classList.add('tax-evaluating');
        console.log('[Taxonomy] evaluateNodeChildren: parentCode=' + parentCode
            + ', businessText=' + (storedBusinessText ? storedBusinessText.substring(0, 80) : ''));

        fetch('/api/analyze-node?parentCode=' + encodeURIComponent(parentCode)
              + '&businessText=' + encodeURIComponent(storedBusinessText))
            .then(r => {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(result => {
                console.log('[Taxonomy] analyze-node response for', parentCode, result);
                wrapper.classList.remove('tax-evaluating');
                wrapper.classList.remove('tax-has-unevaluated');

                const scores = result.scores || {};
                const reasons = result.reasons || {};

                // Show error in status area if present
                if (result.error) {
                    console.warn('[Taxonomy] LLM error for', parentCode, ':', result.error);
                    showStatus('warning', '⚠️ LLM issue for ' + parentCode + ': ' + result.error);
                }

                // Update global reasons
                Object.assign(currentReasons, reasons);

                // Append entry to LLM communication log
                appendLlmLogEntry(parentCode, scores, result);

                // Apply scores to children
                Object.entries(scores).forEach(([code, pct]) => {
                    const childEl = wrapper.querySelector('[data-code="' + CSS.escape(code) + '"]');
                    if (childEl) {
                        const childHeader = childEl.querySelector(':scope > .tax-node-header');
                        if (pct > 0) {
                            const alpha = Math.min(pct / 100, 1).toFixed(2);
                            childHeader.style.backgroundColor = 'rgba(0,128,0,' + alpha + ')';
                            if (pct >= 60) { childHeader.style.color = '#fff'; }
                        } else {
                            childHeader.style.backgroundColor = '';
                            childHeader.style.color = '';
                        }

                        // Add/update percentage badge
                        let badge = childHeader.querySelector('.tax-pct');
                        if (!badge) {
                            badge = document.createElement('span');
                            badge.className = 'tax-pct';
                            childHeader.appendChild(badge);
                        }
                        badge.textContent = pct + '%';

                        // Add/update reason icon
                        const reason = reasons[code];
                        let reasonIcon = childHeader.querySelector('.tax-reason-icon');
                        if (pct > 0 && reason) {
                            if (!reasonIcon) {
                                reasonIcon = document.createElement('span');
                                reasonIcon.className = 'tax-reason-icon';
                                childHeader.appendChild(reasonIcon);
                            }
                            reasonIcon.textContent = '💬';
                            reasonIcon.title = reason;
                        } else if (reasonIcon) {
                            reasonIcon.remove();
                        }

                        // Mark children that have their own children as "expandable for evaluation"
                        const grandchildren = childEl.querySelector(':scope > .tax-children');
                        if (grandchildren && pct > 0) {
                            childEl.classList.add('tax-has-unevaluated');
                        }

                        // Add justify button for leaf nodes with score > 0
                        if (!grandchildren && pct > 0 && storedBusinessText) {
                            let justifyBtn = childEl.querySelector('.tax-justify-btn');
                            if (!justifyBtn) {
                                justifyBtn = document.createElement('button');
                                justifyBtn.className = 'btn btn-sm btn-outline-info tax-justify-btn';
                                justifyBtn.textContent = '📋 Request Justification';
                                justifyBtn.dataset.code = code;
                                justifyBtn.addEventListener('click', function (e) {
                                    e.stopPropagation();
                                    requestLeafJustification(code, justifyBtn);
                                });
                                childEl.appendChild(justifyBtn);
                            }
                        }
                    }
                });

                // Now expand the node
                const children = wrapper.querySelector(':scope > .tax-children');
                if (children) {
                    children.style.display = '';
                    toggle.textContent = '▼';
                }

                // Update currentScores
                if (!currentScores) { currentScores = {}; }
                Object.assign(currentScores, scores);
                updateExportGroupVisibility();

                // Log to console
                const matched = Object.entries(scores).filter(([k, v]) => v > 0);
                console.log('[Taxonomy] Interactive eval of', parentCode, ':', matched.length, 'matches', scores);
            })
            .catch(err => {
                wrapper.classList.remove('tax-evaluating');
                evaluatedNodes.delete(parentCode); // allow retry
                console.error('[Taxonomy] Failed to evaluate', parentCode, err);
                showStatus('danger', 'Failed to evaluate ' + parentCode + ': ' + err.message);
                // Still expand the node (without scores)
                const children = wrapper.querySelector(':scope > .tax-children');
                if (children) {
                    children.style.display = '';
                    toggle.textContent = '▼';
                }
            });
    }

    /** Requests a leaf-node justification from the server and shows it in the UI. */
    function requestLeafJustification(nodeCode, btnEl) {
        if (!storedBusinessText) {
            showStatus('warning', 'No business text stored. Please run an analysis first.');
            return;
        }
        const originalText = btnEl.textContent;
        btnEl.disabled = true;
        btnEl.textContent = '⏳ Generating…';

        fetch('/api/justify-leaf', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                nodeCode: nodeCode,
                businessText: storedBusinessText,
                scores: currentScores || {},
                reasons: currentReasons || {}
            })
        })
            .then(r => {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(data => {
                btnEl.disabled = false;
                btnEl.textContent = originalText;
                showLeafJustificationModal(nodeCode, data.justification || '(no justification returned)');
            })
            .catch(err => {
                btnEl.disabled = false;
                btnEl.textContent = originalText;
                console.error('[Taxonomy] Failed to get leaf justification for', nodeCode, err);
                showStatus('danger', 'Failed to get justification for ' + nodeCode + ': ' + err.message);
            });
    }

    /** Displays the leaf justification in the modal. */
    function showLeafJustificationModal(nodeCode, justification) {
        const modal = document.getElementById('leafJustificationModal');
        const titleEl = document.getElementById('leafJustificationModalTitle');
        const bodyEl = document.getElementById('leafJustificationModalBody');
        if (!modal || !titleEl || !bodyEl) return;
        titleEl.textContent = '📋 Justification for ' + nodeCode;
        bodyEl.textContent = justification;
        const bsModal = new bootstrap.Modal(modal);
        bsModal.show();
    }

    function expandAll() {
        document.querySelectorAll('.tax-children').forEach(el => {
            el.style.display = '';
        });
        document.querySelectorAll('.tax-toggle').forEach(el => {
            if (el.textContent === '▶') el.textContent = '▼';
        });
    }

    function collapseAll() {
        document.querySelectorAll('.tax-children').forEach(el => {
            el.style.display = 'none';
        });
        document.querySelectorAll('.tax-toggle').forEach(el => {
            if (el.textContent === '▼') el.textContent = '▶';
        });
    }

    // ── Analysis ──────────────────────────────────────────────────────────────
    function runAnalysis() {
        const text = document.getElementById('businessText').value.trim();
        if (!text) {
            showStatus('warning', 'Please enter a business requirement text before analyzing.');
            return;
        }

        console.log('[Taxonomy] Starting analysis with text:', text.substring(0, 100) + '...');
        const analysisStart = new Date();

        setAnalyzing(true);
        clearStatus();
        clearAnalysisLog();
        document.getElementById('businessText').classList.remove('stale-results');

        fetch('/api/analyze', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                businessText: text,
                includeArchitectureView: document.getElementById('includeArchitectureView').checked
            })
        })
            .then(r => {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(result => {
                setAnalyzing(false);
                taxonomyData = result.tree;
                currentScores = result.scores;
                lastAnalyzedText = text;
                renderView(taxonomyData, currentScores);

                console.log('[Taxonomy] Analysis result:', result);
                console.log('[Taxonomy] Scores:', result.scores);
                const matchedEntries = Object.entries(result.scores).filter(([k, v]) => v > 0);
                console.log('[Taxonomy] Matched nodes:', matchedEntries);

                const matchedCount = Object.values(result.scores).filter(v => v > 0).length;

                if (result.status === 'SUCCESS') {
                    if (matchedCount === 0) {
                        showStatus('warning',
                            '⚠️ Analysis returned 0 matches. This may indicate the LLM API key is not configured or the LLM returned empty results. Check the server logs for details.');
                    } else {
                        showStatus('success',
                            '✅ Analysis complete. ' + matchedCount + ' node(s) matched.');
                    }
                } else if (result.status === 'PARTIAL') {
                    showStatus('warning',
                        '⚠️ Partial results — ' + (result.errorMessage || 'Analysis incomplete.') +
                        ' ' + matchedCount + ' node(s) matched so far.');
                } else if (result.status === 'ERROR') {
                    showStatus('danger',
                        '❌ Analysis failed: ' + (result.errorMessage || 'Unknown error.'));
                } else {
                    if (matchedCount === 0) {
                        showStatus('warning',
                            '⚠️ Analysis returned 0 matches. This may indicate the LLM API key is not configured or the LLM returned empty results. Check the server logs for details.');
                    } else {
                        showStatus('success',
                            '✅ Analysis complete. ' + matchedCount + ' node(s) matched.');
                    }
                }

                if (result.warnings && result.warnings.length > 0) {
                    const warningList = result.warnings
                        .map(w => '<li>' + escapeHtml(w) + '</li>')
                        .join('');
                    document.getElementById('statusArea').innerHTML +=
                        '<ul class="mb-0 mt-1 ps-3" style="font-size:0.9em">' + warningList + '</ul>';
                }

                // Update analysis log panel
                updateAnalysisLog({
                    timestamp: analysisStart,
                    totalNodes: Object.keys(result.scores).length,
                    matchedEntries: matchedEntries,
                    warnings: result.warnings || [],
                    status: result.status
                });

                // Render architecture view if present
                renderArchitectureView(result.architectureView);
            })
            .catch(err => {
                setAnalyzing(false);
                showStatus('danger', 'Analysis failed: ' + err.message);
                updateAnalysisLog({
                    timestamp: analysisStart,
                    totalNodes: 0,
                    matchedEntries: [],
                    warnings: ['Error: ' + err.message],
                    status: 'ERROR'
                });
            });
    }

    function clearAnalysisLog() {
        const log = document.getElementById('analysisLog');
        if (log) { log.style.display = 'none'; }
        const logContent = document.getElementById('analysisLogContent');
        if (logContent) { logContent.innerHTML = ''; }
        // Also hide architecture view
        const archPanel = document.getElementById('architectureViewPanel');
        if (archPanel) { archPanel.style.display = 'none'; }
    }

    function renderArchitectureView(view) {
        const panel = document.getElementById('architectureViewPanel');
        const content = document.getElementById('architectureViewContent');
        if (!panel || !content) return;

        if (!view) {
            panel.style.display = 'none';
            return;
        }

        let html = '';

        // Notes
        if (view.notes && view.notes.length > 0) {
            html += '<div class="alert alert-info py-1 px-2 small mb-2">' +
                view.notes.map(n => escapeHtml(n)).join('<br>') + '</div>';
        }

        // Anchors summary
        if (view.anchors && view.anchors.length > 0) {
            html += '<h6 class="mb-1">Anchors</h6>';
            html += '<div class="mb-2 small">';
            view.anchors.forEach(a => {
                html += '<span class="badge bg-success me-1">' +
                    escapeHtml(a.nodeCode) + ' (' + a.directScore + '%) — ' +
                    escapeHtml(a.reason) + '</span>';
            });
            html += '</div>';
        }

        // Elements table
        if (view.includedElements && view.includedElements.length > 0) {
            html += '<h6 class="mb-1">Included Elements</h6>';
            html += '<div class="table-responsive"><table class="table table-sm table-bordered small mb-2">';
            html += '<thead><tr><th>Code</th><th>Title</th><th>Sheet</th><th>Relevance</th><th>Hops</th><th>Anchor</th><th>Reason</th></tr></thead><tbody>';
            view.includedElements.forEach(e => {
                const rowClass = e.anchor ? 'table-success' : '';
                html += '<tr class="' + rowClass + '">' +
                    '<td>' + escapeHtml(e.nodeCode) + '</td>' +
                    '<td>' + escapeHtml(e.title || '') + '</td>' +
                    '<td>' + escapeHtml(e.taxonomySheet || '') + '</td>' +
                    '<td>' + (e.relevance * 100).toFixed(1) + '%</td>' +
                    '<td>' + e.hopDistance + '</td>' +
                    '<td>' + (e.anchor ? '✓' : '') + '</td>' +
                    '<td>' + escapeHtml(e.includedBecause || '') + '</td>' +
                    '</tr>';
            });
            html += '</tbody></table></div>';
        }

        // Relationships table
        if (view.includedRelationships && view.includedRelationships.length > 0) {
            html += '<h6 class="mb-1">Included Relationships</h6>';
            html += '<div class="table-responsive"><table class="table table-sm table-bordered small mb-0">';
            html += '<thead><tr><th>Source</th><th>→</th><th>Target</th><th>Type</th><th>Relevance</th><th>Hops</th><th>Reason</th></tr></thead><tbody>';
            view.includedRelationships.forEach(r => {
                html += '<tr>' +
                    '<td>' + escapeHtml(r.sourceCode) + '</td>' +
                    '<td>→</td>' +
                    '<td>' + escapeHtml(r.targetCode) + '</td>' +
                    '<td>' + escapeHtml(r.relationType) + '</td>' +
                    '<td>' + (r.propagatedRelevance * 100).toFixed(1) + '%</td>' +
                    '<td>' + r.hopDistance + '</td>' +
                    '<td>' + escapeHtml(r.includedBecause || '') + '</td>' +
                    '</tr>';
            });
            html += '</tbody></table></div>';
        }

        if (!html) {
            html = '<p class="text-muted small mb-0">Architecture view is empty.</p>';
        }

        content.innerHTML = html;
        panel.style.display = '';
    }

    function updateAnalysisLog(info) {
        const logEl = document.getElementById('analysisLog');
        const logContent = document.getElementById('analysisLogContent');
        if (!logEl || !logContent) { return; }

        const timeStr = info.timestamp.toLocaleTimeString();
        const matchedList = info.matchedEntries.length > 0
            ? info.matchedEntries.map(([k, v]) => escapeHtml(k) + ': ' + v + '%').join(', ')
            : '(none)';
        const warnHtml = info.warnings.length > 0
            ? '<div class="text-warning mt-1"><strong>Warnings:</strong><ul class="mb-0 ps-3">' +
              info.warnings.map(w => '<li>' + escapeHtml(w) + '</li>').join('') + '</ul></div>'
            : '';

        logContent.innerHTML =
            '<div><strong>Time:</strong> ' + timeStr + '</div>' +
            '<div><strong>Nodes evaluated:</strong> ' + info.totalNodes + '</div>' +
            '<div><strong>Status:</strong> ' + escapeHtml(info.status || 'unknown') + '</div>' +
            '<div><strong>Matched codes (' + info.matchedEntries.length + '):</strong> ' + matchedList + '</div>' +
            warnHtml;
        logEl.style.display = '';
    }

    function appendLlmLogEntry(parentCode, scores, detail) {
        const content = document.getElementById('llmCommLogContent');
        if (!content) { return; }

        // Remove the placeholder message on first entry
        const placeholder = content.querySelector('.text-muted.p-2');
        if (placeholder) { placeholder.remove(); }

        const now = new Date();
        const timeStr = now.toLocaleTimeString();
        const nodeCount = scores ? Object.keys(scores).length : 0;
        const matchCount = scores ? Object.values(scores).filter(v => v > 0).length : 0;
        const provider = (detail && detail.provider) ? detail.provider.toUpperCase() : 'UNKNOWN';
        const durationSec = (detail && detail.durationMs) ? (detail.durationMs / 1000).toFixed(1) : '?';
        const prompt = (detail && detail.prompt) ? detail.prompt : '';
        const rawResponse = (detail && detail.rawResponse) ? detail.rawResponse : '';
        const errorMsg = (detail && detail.error) ? detail.error : null;
        const reasons = (detail && detail.reasons) ? detail.reasons : {};

        const entry = document.createElement('details');
        entry.className = 'llm-log-entry' + (errorMsg ? ' llm-log-error' : '');

        const summary = document.createElement('summary');
        summary.style.cursor = 'pointer';
        const statusBadge = errorMsg
            ? '<span class="llm-log-error-badge">&#10060; ERROR</span>'
            : '<span class="text-success">&#10003; ' + matchCount + ' match' + (matchCount !== 1 ? 'es' : '') + '</span>';
        summary.innerHTML =
            '&#128100; <strong>' + escapeHtml(timeStr) + '</strong> — ' +
            '<code>' + escapeHtml(parentCode) + '</code> ' +
            '(' + nodeCount + ' nodes) via <strong>' + escapeHtml(provider) + '</strong> ' +
            '[' + durationSec + 's] ' +
            statusBadge;
        entry.appendChild(summary);

        const body = document.createElement('div');
        body.className = 'px-2 pb-2';

        const errorHtml = errorMsg
            ? '<div class="mt-1 llm-log-error-detail"><strong>&#9888; ERROR:</strong> ' + escapeHtml(errorMsg) + '</div>'
            : '';

        // Build reasons HTML if any reasons were returned
        const reasonEntries = Object.entries(reasons).filter(([, r]) => r);
        let reasonsHtml = '';
        if (reasonEntries.length > 0) {
            const reasonLines = reasonEntries.map(([code, reason]) => {
                const pct = scores && scores[code] !== undefined ? scores[code] : '?';
                return '<div class="llm-log-reason-entry"><code>' + escapeHtml(code) + '</code> '
                    + '(' + pct + '%): ' + escapeHtml(reason) + '</div>';
            }).join('');
            reasonsHtml = '<div class="mt-1"><strong>&#128172; REASONS:</strong>'
                + '<div class="llm-log-reasons">' + reasonLines + '</div></div>';
        }

        body.innerHTML =
            errorHtml +
            '<div class="mt-1"><strong>&#128228; PROMPT:</strong>' +
            '<div class="llm-log-prompt">' + escapeHtml(prompt) + '</div></div>' +
            '<div class="mt-1"><strong>&#128229; RESPONSE:</strong>' +
            '<div class="llm-log-response">' + escapeHtml(rawResponse) + '</div></div>' +
            reasonsHtml;

        entry.appendChild(body);

        // Prepend so newest entries appear at top
        content.insertBefore(entry, content.firstChild);
    }

    // ── Interactive analysis (stores text, renders tree without LLM calls) ─────
    function runInteractiveAnalysis() {
        const text = document.getElementById('businessText').value.trim();
        if (!text) {
            showStatus('warning', 'Please enter a business requirement text before analyzing.');
            return;
        }

        // Reset interactive state
        storedBusinessText = text;
        lastAnalyzedText = text;
        evaluatedNodes = new Set();
        currentScores = {};
        currentReasons = {};
        document.getElementById('businessText').classList.remove('stale-results');

        // Render the tree without scores; mark all expandable nodes as unevaluated
        renderView(taxonomyData, null);
        document.querySelectorAll('.tax-node').forEach(function (el) {
            if (el.querySelector(':scope > .tax-children')) {
                el.classList.add('tax-has-unevaluated');
            }
        });

        showStatus('info', '🔍 Interactive Mode: expand nodes to evaluate them with AI.');
    }

    // ── Streaming analysis (list / tabs views) ────────────────────────────────
    function runStreamingAnalysis() {
        const text = document.getElementById('businessText').value.trim();
        if (!text) {
            showStatus('warning', 'Please enter a business requirement text before analyzing.');
            return;
        }

        setAnalyzing(true);
        clearStatus();
        currentScores = {};
        currentReasons = {};
        document.getElementById('businessText').classList.remove('stale-results');

        // Render a clean tree without scores first
        renderView(taxonomyData, null);

        const url = '/api/analyze-stream?businessText=' + encodeURIComponent(text);
        const eventSource = new EventSource(url);

        eventSource.addEventListener('phase', function (e) {
            const data = JSON.parse(e.data);
            showStatus('info', '🔄 ' + data.message);
        });

        eventSource.addEventListener('scores', function (e) {
            const data = JSON.parse(e.data);
            Object.assign(currentScores, data.scores);
            if (data.reasons) { Object.assign(currentReasons, data.reasons); }
            Object.entries(data.scores).forEach(function ([code, pct]) {
                applyScoreToNode(code, pct, data.reasons ? data.reasons[code] : null);
            });
        });

        eventSource.addEventListener('expanding', function (e) {
            const data = JSON.parse(e.data);
            expandNodeByCode(data.parentCode);
            data.childCodes.forEach(function (code) {
                markNodeAsEvaluating(code);
            });
        });

        eventSource.addEventListener('complete', function (e) {
            const data = JSON.parse(e.data);
            eventSource.close();
            setAnalyzing(false);
            currentScores = data.totalScores;
            lastAnalyzedText = text;
            const matchedCount = Object.values(data.totalScores).filter(v => v > 0).length;
            showStatus('success', '✅ Analysis complete. ' + matchedCount + ' node(s) matched.');
            updateExportGroupVisibility();
        });

        eventSource.addEventListener('error', function (e) {
            if (e.data) {
                try {
                    const data = JSON.parse(e.data);
                    eventSource.close();
                    setAnalyzing(false);
                    showStatus('warning', '⚠️ ' + data.errorMessage);
                } catch (parseErr) {
                    console.error('Failed to parse SSE error event:', parseErr);
                }
            }
        });

        eventSource.onerror = function () {
            eventSource.close();
            setAnalyzing(false);
            showStatus('danger', 'Connection to server lost.');
        };
    }

    // ── Incremental DOM update helpers ────────────────────────────────────────

    /** Apply a score (and optional reason) to a node already in the DOM without re-rendering. */
    function applyScoreToNode(code, pct, reason) {
        const el = document.querySelector('[data-code="' + CSS.escape(code) + '"]');
        if (!el) return;
        const header = el.querySelector(':scope > .tax-node-header');
        if (!header) return;

        // Remove evaluating animation
        el.classList.remove('tax-evaluating');

        if (pct > 0) {
            const alpha = Math.min(pct / 100, 1).toFixed(2);
            header.style.backgroundColor = 'rgba(0,128,0,' + alpha + ')';
            if (pct >= 60) { header.style.color = '#fff'; }

            let badge = header.querySelector('.tax-pct');
            if (!badge) {
                badge = document.createElement('span');
                badge.className = 'tax-pct';
                header.appendChild(badge);
            }
            badge.textContent = pct + '%';

            // Add/update reason icon
            if (reason) {
                let reasonIcon = header.querySelector('.tax-reason-icon');
                if (!reasonIcon) {
                    reasonIcon = document.createElement('span');
                    reasonIcon.className = 'tax-reason-icon';
                    header.appendChild(reasonIcon);
                }
                reasonIcon.textContent = '💬';
                reasonIcon.title = reason;
            }
        } else {
            // Explicitly scored zero – clear any previous highlight
            header.style.backgroundColor = '';
            header.style.color = '';
        }
    }

    /** Expand a node in the DOM and all of its ancestors. */
    function expandNodeByCode(code) {
        const el = document.querySelector('[data-code="' + CSS.escape(code) + '"]');
        if (!el) return;
        const children = el.querySelector(':scope > .tax-children');
        if (children) {
            children.style.display = '';
            const toggle = el.querySelector(':scope > .tax-node-header > .tax-toggle');
            if (toggle) toggle.textContent = '▼';
        }
        // Also make sure all ancestors are visible
        let parent = el.parentElement;
        while (parent) {
            if (parent.classList.contains('tax-children')) {
                parent.style.display = '';
                const pNode = parent.parentElement;
                if (pNode) {
                    const t = pNode.querySelector(':scope > .tax-node-header > .tax-toggle');
                    if (t) t.textContent = '▼';
                }
            }
            parent = parent.parentElement;
        }
    }

    /** Add a pulsing CSS class to indicate a node is currently being evaluated. */
    function markNodeAsEvaluating(code) {
        const el = document.querySelector('[data-code="' + CSS.escape(code) + '"]');
        if (el) el.classList.add('tax-evaluating');
    }

    /** Expand all nodes that have a match > 0 (and their ancestors). */
    function expandMatched(scores) {
        Object.entries(scores).forEach(([code, pct]) => {
            if (pct > 0) {
                const el = document.querySelector('[data-code="' + CSS.escape(code) + '"]');
                if (!el) return;
                // expand this node
                const children = el.querySelector(':scope > .tax-children');
                if (children) {
                    children.style.display = '';
                    const toggle = el.querySelector(':scope > .tax-node-header > .tax-toggle');
                    if (toggle) toggle.textContent = '▼';
                }
                // expand ancestors
                let parent = el.parentElement;
                while (parent) {
                    if (parent.classList.contains('tax-children')) {
                        parent.style.display = '';
                        const parentNode = parent.parentElement;
                        if (parentNode) {
                            const t = parentNode.querySelector(':scope > .tax-node-header > .tax-toggle');
                            if (t) t.textContent = '▼';
                        }
                    }
                    parent = parent.parentElement;
                }
            }
        });
    }

    // ── UI helpers ────────────────────────────────────────────────────────────
    function setAnalyzing(on) {
        const btn = document.getElementById('analyzeBtn');
        const spinner = document.getElementById('analyzeSpinner');
        btn.disabled = on;
        spinner.classList.toggle('d-none', !on);
        btn.textContent = on ? ' Analyzing…' : 'Analyze with AI';
        if (on) btn.prepend(spinner);
    }

    function showStatus(type, msg) {
        document.getElementById('statusArea').innerHTML =
            '<div class="alert alert-' + type + ' py-2">' + escapeHtml(msg) + '</div>';
    }

    function clearStatus() {
        document.getElementById('statusArea').innerHTML = '';
    }

    function resetStaleResults() {
        currentScores = null;
        currentReasons = {};
        storedBusinessText = null;
        evaluatedNodes = new Set();
        lastAnalyzedText = null;
        renderView(taxonomyData, null);
        clearStatus();
        const businessTextEl = document.getElementById('businessText');
        if (businessTextEl) { businessTextEl.classList.remove('stale-results'); }
        const content = document.getElementById('llmCommLogContent');
        if (content) {
            content.innerHTML = '<div class="text-muted p-2">No LLM calls yet. Use Interactive Mode and expand nodes to see communication.</div>';
        }
    }

    function escapeHtml(s) {
        return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    // ── Relation Proposal Review ──────────────────────────────────────────────

    function loadProposals(statusFilter) {
        const container = document.getElementById('proposalsTableContainer');
        if (!container) return;
        container.innerHTML = '<div class="text-muted small p-2" role="status" aria-live="polite">Loading proposals&hellip;</div>';
        // PENDING has a dedicated endpoint; ACCEPTED/REJECTED/ALL need the full list
        const url = statusFilter === 'PENDING' ? '/api/proposals/pending' : '/api/proposals';
        fetch(url)
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (proposals) {
                let filtered = proposals;
                if (statusFilter !== 'ALL' && statusFilter !== 'PENDING') {
                    filtered = proposals.filter(function (p) { return p.status === statusFilter; });
                }
                renderProposalTable(filtered);
                updateProposalBadge();
            })
            .catch(function (err) {
                console.error('[Taxonomy] Failed to load proposals', err);
                if (container) {
                    container.innerHTML = '<div class="text-danger small p-2">Failed to load proposals: ' + escapeHtml(err.message) + '</div>';
                }
            });
    }

    function renderProposalTable(proposals) {
        const container = document.getElementById('proposalsTableContainer');
        if (!container) return;

        if (!proposals || proposals.length === 0) {
            container.innerHTML = '<div class="text-muted small p-2">No proposals found.</div>';
            return;
        }

        let html = '<table class="table table-sm table-hover proposal-table mb-0">' +
            '<thead><tr>' +
            '<th scope="col">Source</th>' +
            '<th scope="col" aria-label="arrow">→</th>' +
            '<th scope="col">Target</th>' +
            '<th scope="col">Type</th>' +
            '<th scope="col">Confidence</th>' +
            '<th scope="col">Rationale</th>' +
            '<th scope="col">Actions</th>' +
            '</tr></thead><tbody>';

        proposals.forEach(function (p) {
            const confPct = p.confidence !== null && p.confidence !== undefined
                ? Math.round(p.confidence * 100) : null;
            let confClass = '';
            let confLabel = '—';
            if (confPct !== null) {
                confLabel = confPct + '%';
                if (confPct >= 80) confClass = 'proposal-conf-high';
                else if (confPct >= 50) confClass = 'proposal-conf-mid';
                else confClass = 'proposal-conf-low';
            }

            const sourceLabel = escapeHtml(p.sourceCode || '') +
                (p.sourceName ? ' <span class="text-muted">' + escapeHtml(p.sourceName) + '</span>' : '');
            const targetLabel = escapeHtml(p.targetCode || '') +
                (p.targetName ? ' <span class="text-muted">' + escapeHtml(p.targetName) + '</span>' : '');

            const rationale = p.rationale ? escapeHtml(p.rationale) : '—';
            const rationaleTrunc = p.rationale && p.rationale.length > 60
                ? escapeHtml(p.rationale.substring(0, 60)) + '…'
                : rationale;

            const isPending = p.status === 'PENDING';
            const actionHtml = isPending
                ? '<button class="btn btn-accept btn-sm me-1" ' +
                    'aria-label="Accept proposal ' + p.id + '" ' +
                    'onclick="window._proposalAccept(' + p.id + ')">&#9989; Accept</button>' +
                  '<button class="btn btn-reject btn-sm" ' +
                    'aria-label="Reject proposal ' + p.id + '" ' +
                    'onclick="window._proposalReject(' + p.id + ')">&#10060; Reject</button>'
                : '<span class="badge ' + (p.status === 'ACCEPTED' ? 'bg-success' : 'bg-secondary') + '">' +
                    escapeHtml(p.status) + '</span>';

            html += '<tr>' +
                '<td>' + sourceLabel + '</td>' +
                '<td>→</td>' +
                '<td>' + targetLabel + '</td>' +
                '<td><span class="badge bg-light text-dark border">' + escapeHtml(p.relationType || '') + '</span></td>' +
                '<td><span class="proposal-conf ' + confClass + '" aria-label="Confidence ' + confLabel + '">' + confLabel + '</span></td>' +
                '<td><span class="proposal-rationale" title="' + rationale + '">' + rationaleTrunc + '</span></td>' +
                '<td class="text-nowrap">' + actionHtml + '</td>' +
                '</tr>';
        });

        html += '</tbody></table>';
        container.innerHTML = html;
    }

    function acceptProposal(id) {
        fetch('/api/proposals/' + id + '/accept', { method: 'POST' })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function () {
                showStatus('success', '✅ Proposal accepted and relation created.');
                loadProposals(currentProposalFilter);
            })
            .catch(function (err) {
                console.error('[Taxonomy] Accept proposal failed', err);
                showStatus('danger', '❌ Failed to accept proposal: ' + err.message);
            });
    }

    function rejectProposal(id) {
        fetch('/api/proposals/' + id + '/reject', { method: 'POST' })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function () {
                showStatus('success', '🗑️ Proposal rejected.');
                loadProposals(currentProposalFilter);
            })
            .catch(function (err) {
                console.error('[Taxonomy] Reject proposal failed', err);
                showStatus('danger', '❌ Failed to reject proposal: ' + err.message);
            });
    }

    function proposeRelationsForNode(nodeCode, relationType) {
        const spinner = document.getElementById('proposeSpinner');
        const submitBtn = document.getElementById('proposeRelationsSubmit');
        if (spinner) spinner.classList.remove('d-none');
        if (submitBtn) submitBtn.disabled = true;

        fetch('/api/proposals/propose', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sourceCode: nodeCode, relationType: relationType, limit: 10 })
        })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (proposals) {
                // Close modal
                const modalEl = document.getElementById('proposeRelationsModal');
                if (modalEl && window.bootstrap) {
                    const modal = window.bootstrap.Modal.getInstance(modalEl);
                    if (modal) modal.hide();
                }
                const count = Array.isArray(proposals) ? proposals.length : 0;
                showStatus('success', '✅ Generated ' + count + ' proposal(s) for ' + nodeCode + ' (' + relationType + ').');
                loadProposals(currentProposalFilter);
            })
            .catch(function (err) {
                console.error('[Taxonomy] Propose relations failed', err);
                showStatus('danger', '❌ Failed to generate proposals: ' + err.message);
            })
            .finally(function () {
                if (spinner) spinner.classList.add('d-none');
                if (submitBtn) submitBtn.disabled = false;
            });
    }

    function updateProposalBadge() {
        fetch('/api/proposals/pending')
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (pending) {
                const badge = document.getElementById('proposalPendingBadge');
                if (badge) {
                    const count = Array.isArray(pending) ? pending.length : 0;
                    badge.textContent = count + ' pending';
                    badge.className = count > 0
                        ? 'badge bg-warning text-dark proposal-pending-badge'
                        : 'badge bg-secondary proposal-pending-badge';
                }
            })
            .catch(function (err) {
                console.error('[Taxonomy] Failed to update proposal badge', err);
            });
    }

    // Expose accept/reject as global helpers for onclick handlers in rendered table
    window._proposalAccept = acceptProposal;
    window._proposalReject = rejectProposal;

    // Expose current scores for Requirement Impact Analysis
    window._getCurrentScores = function () { return currentScores; };

    // ── Prompt template editor ────────────────────────────────────────────────

    let promptTemplates = [];

    /** Returns headers object including X-Admin-Token when admin mode is active. */
    function getAdminHeaders() {
        const h = {};
        if (isAdminMode()) { h['X-Admin-Token'] = getAdminToken(); }
        return h;
    }

    function initPromptEditor() {
        const select   = document.getElementById('promptSelect');
        const saveBtn  = document.getElementById('promptSaveBtn');
        const resetBtn = document.getElementById('promptResetBtn');
        if (!select || !saveBtn || !resetBtn) return;

        fetch('/api/prompts', { headers: getAdminHeaders() })
            .then(r => {
                if (r.status === 401) { return null; }
                return r.json();
            })
            .then(function (templates) {
                if (!templates) { return; }
                promptTemplates = templates;
                templates.forEach(function (t) {
                    const opt = document.createElement('option');
                    opt.value = t.code;
                    opt.textContent = t.code + ' — ' + t.name;
                    select.appendChild(opt);
                });
                if (templates.length > 0) {
                    loadPromptIntoEditor(templates[0].code);
                }
            })
            .catch(function () {
                setPromptStatus('danger', 'Failed to load prompt templates.');
            });

        select.addEventListener('change', function () {
            loadPromptIntoEditor(select.value);
        });

        saveBtn.addEventListener('click', function () {
            const code     = select.value;
            const template = document.getElementById('promptTextarea').value;
            fetch('/api/prompts/' + encodeURIComponent(code), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json', ...getAdminHeaders() },
                body: JSON.stringify({ template: template })
            })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function () {
                setPromptStatus('success', '✅ Template saved.');
                updateOverrideBadge(code, true);
                // Update local cache
                const entry = promptTemplates.find(t => t.code === code);
                if (entry) { entry.template = template; entry.overridden = true; }
            })
            .catch(function (e) {
                setPromptStatus('danger', '❌ Save failed: ' + e.message);
            });
        });

        resetBtn.addEventListener('click', function () {
            const code = select.value;
            fetch('/api/prompts/' + encodeURIComponent(code), {
                method: 'DELETE',
                headers: getAdminHeaders()
            })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function () {
                // Reload to get the file-based default
                return fetch('/api/prompts/' + encodeURIComponent(code), {
                    headers: getAdminHeaders()
                }).then(r => r.json());
            })
            .then(function (data) {
                document.getElementById('promptTextarea').value = data.template;
                updateOverrideBadge(code, false);
                setPromptStatus('success', 'Reset to default.');
                const entry = promptTemplates.find(t => t.code === code);
                if (entry) { entry.template = data.template; entry.overridden = false; }
            })
            .catch(function (e) {
                setPromptStatus('danger', '❌ Reset failed: ' + e.message);
            });
        });
    }

    function loadPromptIntoEditor(code) {
        fetch('/api/prompts/' + encodeURIComponent(code), { headers: getAdminHeaders() })
            .then(r => {
                if (r.status === 401) { return null; }
                return r.json();
            })
            .then(function (data) {
                if (!data) { return; }
                document.getElementById('promptTextarea').value = data.template;
                updateOverrideBadge(code, data.overridden);
                setPromptStatus('', '');
            })
            .catch(function () {
                setPromptStatus('danger', 'Failed to load template for ' + code);
            });
    }

    function updateOverrideBadge(code, overridden) {
        const over = document.getElementById('promptOverrideBadge');
        const def  = document.getElementById('promptDefaultBadge');
        if (!over || !def) return;
        over.classList.toggle('d-none', !overridden);
        def.classList.toggle('d-none', overridden);
    }

    function setPromptStatus(type, msg) {
        const el = document.getElementById('promptStatusMsg');
        if (!el) return;
        if (!type || !msg) { el.innerHTML = ''; return; }
        el.innerHTML = '<span class="text-' + type + '">' + escapeHtml(msg) + '</span>';
    }

})();

