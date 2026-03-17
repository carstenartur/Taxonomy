/* taxonomy-browse.js – Tree rendering, navigation & UI for the Taxonomy Browser */

(function () {
    'use strict';
    var t = TaxonomyI18n.t;

    var S = window.TaxonomyState;
    // SC (scoring functions) resolved lazily — script may load before taxonomy-scoring.js
    function SC() { return window.TaxonomyScoring || {}; }

    // ── Accessibility helpers (WCAG 4.1.3) ────────────────────────────────────
    function announceStatus(message) {
        var el = document.getElementById('a11yStatus');
        if (el) { el.textContent = ''; requestAnimationFrame(function () { el.textContent = message; }); }
    }
    function announceAlert(message) {
        var el = document.getElementById('a11yAlert');
        if (el) { el.textContent = ''; requestAnimationFrame(function () { el.textContent = message; }); }
    }

    // ── Bootstrap ─────────────────────────────────────────────────────────────
    document.addEventListener('DOMContentLoaded', function () {
        loadTaxonomy();
        checkAiStatus();
        // Poll AI status every 30 seconds to keep the indicator current
        setInterval(checkAiStatus, 30000);
        document.getElementById('analyzeBtn').addEventListener('click', function () {
            // Check if Manual Scoring is selected
            var providerSelect = document.getElementById('providerSelect');
            var selectedProvider = providerSelect ? providerSelect.value : '';
            if (selectedProvider === 'MANUAL') {
                enterManualScoringMode();
                return;
            }

            const interactiveCb = document.getElementById('interactiveMode');
            S.interactiveMode = interactiveCb ? interactiveCb.checked : false;
            if (S.interactiveMode) {
                // Switch to list view automatically so interactive expand/collapse works
                if (S.currentView !== 'list' && S.currentView !== 'tabs') {
                    switchView('list');
                }
                SC().runInteractiveAnalysis();
            } else {
                if (S.currentView === 'list' || S.currentView === 'tabs') {
                    SC().runStreamingAnalysis();
                } else {
                    SC().runAnalysis();
                }
            }
        });

        // Sync interactive mode checkbox state
        const interactiveCb = document.getElementById('interactiveMode');
        if (interactiveCb) {
            interactiveCb.addEventListener('change', function () {
                S.interactiveMode = interactiveCb.checked;
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
                S.currentTreeRoot = this.value;
                renderView(S.taxonomyData, S.currentScores);
            });
        }

        // View switcher buttons
        ['viewList', 'viewTabs', 'viewSunburst', 'viewTree', 'viewDecision', 'viewSummary'].forEach(function (id) {
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
                    content.innerHTML = '<div class="text-muted p-2">' + t('browse.llm.log.empty') + '</div>';
                }
            });
        }

        // Export buttons
        ['exportSvg', 'exportPng', 'exportPdf', 'exportCsv', 'exportJson', 'exportVisio', 'exportArchiMate', 'exportMermaid',
         'exportDot', 'exportMermaidTree',
         'exportReportMd', 'exportReportHtml', 'exportReportDocx'].forEach(function (id) {
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
                            showStatus('danger', t('browse.import.failed', data.error));
                            return;
                        }
                        S.currentScores = data.scores || {};
                        S.currentReasons = data.reasons || {};
                        // Update business text field if present in the imported data
                        if (data.requirement) {
                            const btEl = document.getElementById('businessText');
                            if (btEl) { btEl.value = data.requirement; }
                            S.storedBusinessText = data.requirement;
                            S.lastAnalyzedText   = data.requirement;
                        }
                        // Render tree with imported scores
                        renderView(S.taxonomyData, S.currentScores);
                        updateExportGroupVisibility();
                        const scored = Object.keys(S.currentScores).length;
                        let msg = t('browse.import.loaded', scored);
                        if (data.warnings && data.warnings.length > 0) {
                            msg += ' ⚠️ ' + data.warnings.join('; ');
                        }
                        showStatus(data.warnings && data.warnings.length > 0 ? 'warning' : 'success', msg);
                    })
                    .catch(function (err) {
                        showStatus('danger', t('browse.import.failed', err.message));
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
                    S.currentProposalFilter = filter;
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
                if (S.pendingProposalNodeCode && relationType) {
                    proposeRelationsForNode(S.pendingProposalNodeCode, relationType);
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
                    const hasScores = S.currentScores !== null &&
                        typeof S.currentScores === 'object' &&
                        Object.keys(S.currentScores).length > 0;
                    if (!hasScores) {
                        businessTextEl.classList.remove('stale-results');
                        return;
                    }
                    if (S.lastAnalyzedText !== null && businessTextEl.value !== S.lastAnalyzedText) {
                        businessTextEl.classList.add('stale-results');
                        showStatus('warning', t('browse.stale.warning'));
                        const statusArea = document.getElementById('statusArea');
                        if (statusArea) {
                            const alertEl = statusArea.querySelector('.alert');
                            if (alertEl && !alertEl.querySelector('.btn-warning')) {
                                const resetBtn = document.createElement('button');
                                resetBtn.className = 'btn btn-sm btn-warning ms-2';
                                resetBtn.textContent = t('browse.stale.reset');
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
            if (data.valid && data.token) {
                sessionStorage.setItem('adminToken', data.token);
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
        const adminNavTab = document.getElementById('adminNavTab');
        if (!adminPasswordRequired) {
            // No password configured — admin features always accessible, no lock button
            body.classList.add('admin-unlocked');
            document.querySelectorAll('.admin-only').forEach(el => {
                el.style.display = '';
            });
            if (adminNavTab) { adminNavTab.style.display = ''; }
            if (lockBtn) { lockBtn.classList.add('d-none'); }
            return;
        }
        if (isAdminMode()) {
            body.classList.add('admin-unlocked');
            if (adminNavTab) { adminNavTab.style.display = ''; }
            if (lockBtn) {
                lockBtn.innerHTML = '<span aria-hidden="true">🔓</span><span class="visually-hidden">Admin Mode</span>';
                lockBtn.title = t('browse.admin.active.title');
                lockBtn.setAttribute('aria-label', t('browse.admin.active.aria'));
                lockBtn.classList.remove('d-none');
            }
        } else {
            body.classList.remove('admin-unlocked');
            if (adminNavTab) { adminNavTab.style.display = 'none'; }
            if (lockBtn) {
                lockBtn.innerHTML = '<span aria-hidden="true">🔐</span><span class="visually-hidden">Admin Mode</span>';
                lockBtn.title = t('browse.admin.locked.title');
                lockBtn.setAttribute('aria-label', t('browse.admin.locked.aria'));
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
            // Return focus to trigger when modal closes (WCAG 2.4.3)
            modal.addEventListener('hidden.bs.modal', function () {
                lockBtn.focus();
            }, { once: true });
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
    function populateProviderDropdown(aiStatus) {
        var select = document.getElementById('providerSelect');
        if (!select || !aiStatus.availableProviders) return;

        // Keep the first static option (Server Default)
        while (select.options.length > 1) select.remove(1);

        aiStatus.availableProviders.forEach(function(p) {
            var opt = document.createElement('option');
            opt.value = p;
            var icon = (p === 'LOCAL_ONNX') ? '💻' : '☁️';
            opt.textContent = icon + ' ' + p;
            select.appendChild(opt);
        });

        // Manual scoring option
        var manualOpt = document.createElement('option');
        manualOpt.value = 'MANUAL';
        manualOpt.textContent = '✏️ ' + t('browse.manual.scoring');
        select.appendChild(manualOpt);
    }

    function checkAiStatus() {
        fetch('/api/ai-status')
            .then(r => r.json())
            .then(status => {
                const btn = document.getElementById('analyzeBtn');
                const infoEl = document.getElementById('aiProviderInfo');
                const badge = document.getElementById('aiStatusBadge');

                // Populate provider dropdown
                populateProviderDropdown(status);

                if (status.available) {
                    btn.disabled = false;
                    const aiWarn = document.getElementById('aiUnavailableWarning');
                    if (aiWarn) aiWarn.classList.add('d-none');
                    if (!adminPasswordRequired || isAdminMode()) {
                        infoEl.textContent = t('browse.ai.using', status.provider);
                        infoEl.classList.remove('d-none');
                    } else {
                        infoEl.classList.add('d-none');
                    }
                    if (badge) {
                        if (!adminPasswordRequired || isAdminMode()) {
                            badge.textContent = t('browse.ai.badge.provider', status.provider);
                            badge.title = t('browse.ai.badge.available.title', status.provider);
                        } else {
                            badge.textContent = t('browse.ai.badge.ready');
                            badge.title = t('browse.ai.badge.ready.title');
                        }
                        badge.className = 'badge bg-success ms-auto me-2 fs-6';
                    }
                } else {
                    // Even when no cloud provider is available, allow LOCAL_ONNX and MANUAL
                    btn.disabled = false;
                    infoEl.classList.add('d-none');
                    const aiWarn = document.getElementById('aiUnavailableWarning');
                    if (aiWarn) aiWarn.classList.remove('d-none');
                    if (badge) {
                        badge.textContent = t('browse.ai.badge.unavailable');
                        badge.className = 'badge bg-danger ms-auto me-2 fs-6';
                        badge.title = t('browse.ai.badge.unavailable.title');
                    }
                    showStatus('warning', t('browse.ai.unavailable.detail'));
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
                    badge.textContent = t('browse.ai.badge.unknown');
                    badge.className = 'badge bg-warning text-dark ms-auto me-2 fs-6';
                    badge.title = t('browse.ai.badge.unknown.title');
                }
            });
    }

    // ── LLM Diagnostics ───────────────────────────────────────────────────────
    function loadDiagnostics() {
        if (adminPasswordRequired && !isAdminMode()) { return; }
        const content = document.getElementById('diagnosticsContent');
        if (!content) { return; }
        content.innerHTML = '<div class="text-muted">' + t('status.loading') + '</div>';
        const headers = { 'Accept': 'application/json' };
        if (isAdminMode()) { headers['X-Admin-Token'] = getAdminToken(); }
        fetch('/api/diagnostics', { headers: headers })
            .then(r => {
                if (r.status === 401) {
                    if (content) { content.innerHTML = '<div class="text-muted">' + t('browse.diagnostics.auth.required') + '</div>'; }
                    return null;
                }
                return r.json();
            })
            .then(d => {
                if (!d) { return; }
                console.log('[Taxonomy] Diagnostics:', d);
                const keyStatus = d.apiKeyConfigured
                    ? '<span class="diag-status-ok">&#9989; ' + t('browse.diagnostics.configured') + '</span>' +
                      (d.apiKeyPrefix ? ' (<code>' + escapeHtml(d.apiKeyPrefix) + '</code>)' : '')
                    : '<span class="diag-status-err">&#10060; ' + t('browse.diagnostics.not.configured') + '</span>';
                const lastCallHtml = d.lastCallTime
                    ? escapeHtml(d.lastCallTime) + ' — ' +
                      (d.lastCallSuccess
                          ? '<span class="diag-status-ok">&#9989; ' + t('browse.diagnostics.success') + '</span>'
                          : '<span class="diag-status-err">&#10060; ' + t('browse.diagnostics.failed.label') + '</span>')
                    : '<span class="text-muted">' + t('browse.diagnostics.no.calls') + '</span>';
                const lastErrorHtml = d.lastError
                    ? '<div class="mt-1 llm-log-error-detail"><strong>' + t('browse.diagnostics.last.error') + '</strong> ' +
                      escapeHtml(d.lastError) + '</div>'
                    : '';
                content.innerHTML =
                    '<table class="table table-sm table-borderless mb-0" style="font-size:0.9em;">' +
                    '<tr><td class="fw-semibold" style="width:140px">' + t('browse.diagnostics.provider') + '</td><td>' + escapeHtml(d.provider || '—') + '</td></tr>' +
                    '<tr><td class="fw-semibold">' + t('browse.diagnostics.api.key') + '</td><td>' + keyStatus + '</td></tr>' +
                    '<tr><td class="fw-semibold">' + t('browse.diagnostics.last.call') + '</td><td>' + lastCallHtml + '</td></tr>' +
                    '<tr><td class="fw-semibold">' + t('browse.diagnostics.stats') + '</td><td>' +
                        escapeHtml(String(d.totalCalls)) + ' total / ' +
                        '<span class="diag-status-ok">' + escapeHtml(String(d.successfulCalls)) + ' ok</span> / ' +
                        '<span class="diag-status-err">' + escapeHtml(String(d.failedCalls)) + ' failed</span>' +
                    '</td></tr>' +
                    '<tr><td class="fw-semibold">' + t('browse.diagnostics.server.time') + '</td><td>' + escapeHtml(d.serverTime || '—') + '</td></tr>' +
                    '</table>' +
                    lastErrorHtml;
            })
            .catch(err => {
                console.error('[Taxonomy] Failed to load diagnostics', err);
                if (content) { content.innerHTML = '<div class="text-danger">' + t('browse.diagnostics.load.failed', escapeHtml(err.message)) + '</div>'; }
            });
    }

    function testLlmConnection() {
        const btn = document.getElementById('testLlmConnection');
        if (btn) { btn.disabled = true; btn.textContent = t('browse.test.testing'); }
        const testText = 'Test connection: business process management';
        fetch('/api/analyze-node?parentCode=BP&businessText=' + encodeURIComponent(testText))
            .then(r => r.json())
            .then(result => {
                console.log('[Taxonomy] Test connection result:', result);
                if (result.error) {
                    showStatus('warning', t('browse.test.error', result.error));
                } else {
                    showStatus('success', t('browse.test.success', (result.provider || 'unknown provider')));
                }
                loadDiagnostics();
            })
            .catch(err => {
                console.error('[Taxonomy] Test connection failed', err);
                showStatus('danger', t('browse.test.failed', err.message));
            })
            .finally(() => {
                if (btn) { btn.disabled = false; btn.textContent = t('browse.test.btn'); }
            });
    }

    // ── Load taxonomy tree from API ───────────────────────────────────────────
    var LOADING_MSG = t('browse.loading.message');

    /** Shows or hides the global startup banner and disables/enables interactive elements. */
    function setStartupBanner(show, statusText) {
        var banner = document.getElementById('startupBanner');
        var statusSpan = document.getElementById('startupBannerStatus');
        var analyzeBtn = document.getElementById('analyzeBtn');
        if (banner) {
            banner.classList.toggle('d-none', !show);
        }
        if (statusSpan) {
            statusSpan.textContent = statusText || '';
        }
        if (analyzeBtn) {
            analyzeBtn.disabled = show;
            analyzeBtn.title = show
                ? t('browse.loading.wait')
                : t('browse.analyze.btn.title');
        }
    }

    function loadTaxonomy() {
        fetch('/api/taxonomy')
            .then(function (r) {
                if (r.status === 503) {
                    // Taxonomy is still loading — show global banner and poll until ready
                    setStartupBanner(true);
                    document.getElementById('taxonomyTree').innerHTML =
                        '<div class="alert alert-info">' + LOADING_MSG + '</div>';
                    pollStartupStatus();
                    return null;
                }
                return r.json();
            })
            .then(function (data) {
                if (!data) return; // 503 branch already handled
                setStartupBanner(false);
                S.taxonomyData = data;
                populateTreeRootSelect(data);
                renderView(data, null);
                // Populate Graph Explorer node suggestions
                if (window.TaxonomyGraph) {
                    window.TaxonomyGraph.populateNodeSuggestions(data);
                }
            })
            .catch(function (err) {
                setStartupBanner(false);
                document.getElementById('taxonomyTree').innerHTML =
                    '<div class="alert alert-danger">' + t('browse.load.failed', err) + '</div>';
            });
    }

    /** Polls /api/status/startup every 5 s until the taxonomy is ready, then reloads the tree. */
    function pollStartupStatus() {
        setTimeout(function () {
            fetch('/api/status/startup')
                .then(function (r) { return r.json(); })
                .then(function (s) {
                    if (s && s.initialized) {
                        setStartupBanner(false);
                        loadTaxonomy();
                    } else {
                        var statusMsg = s && s.phaseMessage
                            ? ' — ' + s.phaseMessage
                            : (s && s.status ? ' (status: ' + s.status + ')' : '');
                        setStartupBanner(true, statusMsg.trim());
                        document.getElementById('taxonomyTree').innerHTML =
                            '<div class="alert alert-info">' + LOADING_MSG + statusMsg + '</div>';
                        pollStartupStatus();
                    }
                })
                .catch(function () {
                    pollStartupStatus(); // retry on network error
                });
        }, 5000);
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
        if (data.some(r => r.code === S.currentTreeRoot)) {
            sel.value = S.currentTreeRoot;
        } else {
            S.currentTreeRoot = data[0].code;
            sel.value = S.currentTreeRoot;
        }
    }

    // ── View switching ────────────────────────────────────────────────────────
    function switchView(view) {
        S.currentView = view;

        // Update button active states
        const viewIds = { list: 'viewList', tabs: 'viewTabs', sunburst: 'viewSunburst', tree: 'viewTree', decision: 'viewDecision', summary: 'viewSummary' };
        Object.entries(viewIds).forEach(([v, id]) => {
            const btn = document.getElementById(id);
            if (!btn) { return; }
            btn.classList.toggle('btn-primary', v === view);
            btn.classList.toggle('btn-outline-secondary', v !== view);
        });

        // Show/hide Expand All / Collapse All only for list & tabs views
        const ecGroup = document.getElementById('expandCollapseGroup');
        if (ecGroup) {
            ecGroup.style.display = (view === 'list' || view === 'tabs') ? '' : 'none';
        }

        // Show taxonomy root selector only in tree view
        const treeRootGroup = document.getElementById('treeRootGroup');
        if (treeRootGroup) {
            treeRootGroup.style.display = (view === 'tree') ? '' : 'none';
        }

        // Disable SVG/PNG export buttons for non-D3 views (list/tabs/summary have no SVG)
        const svgViewActive = (view === 'sunburst' || view === 'tree' || view === 'decision');
        ['exportSvg', 'exportPng'].forEach(function (id) {
            const btn = document.getElementById(id);
            if (btn) { btn.disabled = !svgViewActive; }
        });

        renderView(S.taxonomyData, S.currentScores);
    }

    // ── Master render dispatcher ──────────────────────────────────────────────
    function renderView(data, scores) {
        if (!data || data.length === 0) { return; }
        document.getElementById('taxonomyTree').removeAttribute('data-view-rendered');
        switch (S.currentView) {
            case 'list':
                renderTree(data, scores);
                if (scores) { SC().expandMatched(scores); }
                document.getElementById('taxonomyTree').setAttribute('data-view-rendered', 'list');
                break;
            case 'tabs':
                renderTabsView(data, scores);
                if (scores) { SC().expandMatched(scores); }
                document.getElementById('taxonomyTree').setAttribute('data-view-rendered', 'tabs');
                break;
            case 'sunburst':
                if (window.TaxonomyViews) {
                    window.TaxonomyViews.renderSunburst(
                        document.getElementById('taxonomyTree'), data, scores);
                }
                break;
            case 'tree':
                if (window.TaxonomyViews) {
                    const treeRoot = data.find(r => r.code === S.currentTreeRoot) || data[0];
                    const treeContainer = document.getElementById('taxonomyTree');
                    const canUseCanvas = window.TaxonomyViews.countNodes
                        && window.TaxonomyViews.renderTreeCanvas
                        && window.TaxonomyViews.countNodes(treeRoot) > 500;
                    if (canUseCanvas) {
                        window.TaxonomyViews.renderTreeCanvas(treeContainer, [treeRoot], scores);
                    } else {
                        window.TaxonomyViews.renderTreeDiagram(treeContainer, [treeRoot], scores);
                    }
                }
                break;
            case 'decision':
                if (window.TaxonomyViews) {
                    window.TaxonomyViews.renderDecisionMap(
                        document.getElementById('taxonomyTree'), data, scores);
                }
                break;
            case 'summary':
                SC().renderSummaryView(data, scores);
                break;
        }
        updateExportGroupVisibility();
    }

    // ── Export group visibility ───────────────────────────────────────────────
    function updateExportGroupVisibility() {
        const exportGroup = document.getElementById('exportGroup');
        const exportHint = document.getElementById('exportHint');
        if (!exportGroup) { return; }
        const hasScores = S.currentScores && Object.values(S.currentScores).some(v => v > 0);
        exportGroup.style.display = hasScores ? '' : 'none';
        if (exportHint) {
            exportHint.classList.toggle('d-none', hasScores);
        }
        // Share scores with other modules (e.g. taxonomy-analysis.js)
        window._taxonomyCurrentScores = S.currentScores;
    }

    // ── Export handler ────────────────────────────────────────────────────────
    function handleExport(btnId) {
        if (btnId === 'exportCsv') {
            if (window.TaxonomyExport) {
                window.TaxonomyExport.exportCsv(S.currentScores, S.taxonomyData);
            }
            return;
        }
        if (btnId === 'exportPdf') {
            if (window.TaxonomyExport && window.TaxonomyExport.exportPdf) {
                window.TaxonomyExport.exportPdf();
            } else {
                window.print();
            }
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
                window.TaxonomyExport.exportJson(S.currentScores, S.currentReasons, bt ? bt.value : '', null);
            }
            return;
        }
        if (btnId === 'exportMermaid') {
            if (window.TaxonomyExport) {
                var bt = document.getElementById('businessText');
                window.TaxonomyExport.exportMermaid(bt ? bt.value : '');
            }
            return;
        }
        if (btnId === 'exportDot') {
            if (window.TaxonomyExport) {
                window.TaxonomyExport.exportDot(S.currentScores, S.taxonomyData);
            }
            return;
        }
        if (btnId === 'exportMermaidTree') {
            if (window.TaxonomyExport) {
                window.TaxonomyExport.exportMermaidTree(S.currentScores, S.taxonomyData);
            }
            return;
        }
        if (btnId === 'exportReportMd' || btnId === 'exportReportHtml' || btnId === 'exportReportDocx') {
            var formatMap = { 'exportReportMd': 'markdown', 'exportReportHtml': 'html', 'exportReportDocx': 'docx' };
            var extMap = { 'exportReportMd': '.md', 'exportReportHtml': '.html', 'exportReportDocx': '.docx' };
            var format = formatMap[btnId];
            var ext = extMap[btnId];
            var bt = document.getElementById('businessText');
            var businessText = bt ? bt.value : '';
            fetch('/api/report/' + format, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ scores: S.currentScores, businessText: businessText, minScore: 20 })
            })
            .then(function (resp) {
                if (!resp.ok) throw new Error('Report generation failed');
                return resp.blob();
            })
            .then(function (blob) {
                var url = URL.createObjectURL(blob);
                var a = document.createElement('a');
                a.href = url;
                a.download = 'architecture-report' + ext;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(url);
            })
            .catch(function (err) { alert(t('browse.report.export.failed', err.message)); });
            return;
        }
    }

    // ── Render tree (list view) ───────────────────────────────────────────────
    function renderTree(nodes, scores) {
        const container = document.getElementById('taxonomyTree');
        cleanupD3(container);
        container.innerHTML = '';
        nodes.forEach(node => container.appendChild(buildNodeEl(node, scores)));
        initTreeKeyboardNavigation(container);
    }

    // ── Keyboard navigation for treeview (WCAG 2.1.1, 2.4.3) ─────────────────
    function initTreeKeyboardNavigation(tree) {
        if (!tree) return;
        tree.addEventListener('keydown', function (e) {
            var current = document.activeElement;
            if (!current || current.getAttribute('role') !== 'treeitem') return;

            var allItems = Array.from(tree.querySelectorAll('[role="treeitem"]')).filter(function (el) {
                // Only include visible items
                var p = el;
                while (p && p !== tree) {
                    if (p.style && p.style.display === 'none') return false;
                    p = p.parentElement;
                }
                return true;
            });
            var idx = allItems.indexOf(current);

            switch (e.key) {
                case 'ArrowDown':
                    e.preventDefault();
                    if (idx < allItems.length - 1) allItems[idx + 1].focus();
                    break;
                case 'ArrowUp':
                    e.preventDefault();
                    if (idx > 0) allItems[idx - 1].focus();
                    break;
                case 'ArrowRight':
                    e.preventDefault();
                    if (current.getAttribute('aria-expanded') === 'false') {
                        var toggle = current.querySelector(':scope > .tax-node-header > .tax-toggle');
                        if (toggle) { toggle.click(); }
                    } else {
                        var firstChild = current.querySelector('[role="group"] > [role="treeitem"]');
                        if (firstChild) firstChild.focus();
                    }
                    break;
                case 'ArrowLeft':
                    e.preventDefault();
                    if (current.getAttribute('aria-expanded') === 'true') {
                        var collapseToggle = current.querySelector(':scope > .tax-node-header > .tax-toggle');
                        if (collapseToggle) { collapseToggle.click(); }
                    } else {
                        var parent = current.parentElement ? current.parentElement.closest('[role="treeitem"]') : null;
                        if (parent) parent.focus();
                    }
                    break;
                case 'Enter':
                case ' ':
                    e.preventDefault();
                    var headerEl = current.querySelector(':scope > .tax-node-header');
                    if (headerEl) headerEl.click();
                    break;
                case 'Home':
                    e.preventDefault();
                    if (allItems.length > 0) allItems[0].focus();
                    break;
                case 'End':
                    e.preventDefault();
                    if (allItems.length > 0) allItems[allItems.length - 1].focus();
                    break;
            }
        });
        // First root item gets tabindex="0" so it's in the tab order
        var firstItem = tree.querySelector('[role="treeitem"]');
        if (firstItem) firstItem.setAttribute('tabindex', '0');
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
        const reason = S.currentReasons ? (S.currentReasons[node.code] || null) : null;

        const wrapper = document.createElement('div');
        wrapper.className = 'tax-node tax-level-' + node.level;
        wrapper.dataset.code = node.code;

        // ARIA treeview role (WCAG 4.1.2)
        wrapper.setAttribute('role', 'treeitem');
        wrapper.setAttribute('tabindex', '-1');
        var ariaLabel = node.code + ' ' + (node.name || '');
        if (pct !== null && pct > 0) {
            ariaLabel += ', ' + t('browse.node.score', pct);
            if (reason) { ariaLabel += ', ' + t('browse.node.reason', reason); }
        } else if (pct !== null) {
            ariaLabel += ', ' + t('browse.node.score', 0);
        }
        wrapper.setAttribute('aria-label', ariaLabel);
        if (hasChildren) {
            wrapper.setAttribute('aria-expanded', 'false');
        }

        // Header row
        const header = document.createElement('div');
        header.className = 'tax-node-header';

        // Apply green background based on match percentage (M11: contrast-safe)
        if (pct !== null && pct > 0) {
            const alpha = Math.min(pct / 100, 1).toFixed(2);
            header.style.backgroundColor = 'rgba(0,128,0,' + alpha + ')';
            if (pct >= 75) { header.style.color = '#fff'; }
            else { header.style.color = '#1a1a1a'; }
        }

        // Toggle arrow
        const toggle = document.createElement('span');
        toggle.className = 'tax-toggle';
        toggle.setAttribute('aria-hidden', 'true');
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
            badge.setAttribute('aria-hidden', 'true');
            header.appendChild(badge);
        }

        // Reason icon (if score > 0 and reason available)
        if (pct !== null && pct > 0 && reason) {
            const reasonIcon = document.createElement('span');
            reasonIcon.className = 'tax-reason-icon';
            reasonIcon.textContent = '💬';
            reasonIcon.title = reason;
            reasonIcon.setAttribute('aria-label', t('browse.node.reason', reason));
            header.appendChild(reasonIcon);
        }

        // Inline action buttons — shown on hover, same line as the node header
        const actions = document.createElement('span');
        actions.className = 'tax-node-actions';

        // Leaf justification button (leaf node with score > 0)
        if (!hasChildren && pct !== null && pct > 0 && S.storedBusinessText) {
            const justifyBtn = document.createElement('button');
            justifyBtn.className = 'btn btn-sm btn-outline-info tax-justify-btn';
            justifyBtn.textContent = t('browse.btn.justify');
            justifyBtn.dataset.code = node.code;
            justifyBtn.addEventListener('click', function (e) {
                e.stopPropagation();
                SC().requestLeafJustification(node.code, justifyBtn);
            });
            actions.appendChild(justifyBtn);
        }

        // Propose Relations button
        const proposeBtn = document.createElement('button');
        proposeBtn.className = 'btn btn-sm btn-outline-secondary tax-justify-btn proposal-btn';
        proposeBtn.textContent = t('browse.btn.propose.relations');
        proposeBtn.title = t('browse.btn.propose.relations.title', node.code);
        proposeBtn.setAttribute('aria-label', t('browse.btn.propose.relations.aria', node.code));
        proposeBtn.dataset.code = node.code;
        proposeBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            S.pendingProposalNodeCode = node.code;
            const codeEl = document.getElementById('proposeNodeCode');
            if (codeEl) { codeEl.textContent = node.code + ' — ' + node.name; }
            const modal = document.getElementById('proposeRelationsModal');
            if (modal && window.bootstrap) {
                new window.bootstrap.Modal(modal).show();
            }
        });
        actions.appendChild(proposeBtn);

        // Graph Explorer button
        const graphBtn = document.createElement('button');
        graphBtn.className = 'btn btn-sm btn-outline-secondary tax-justify-btn graph-explore-btn';
        graphBtn.textContent = t('browse.btn.graph');
        graphBtn.title = t('browse.btn.graph.title', node.code);
        graphBtn.setAttribute('aria-label', t('browse.btn.graph.aria', node.code));
        graphBtn.dataset.code = node.code;
        graphBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            if (window.TaxonomyGraph) {
                window.TaxonomyGraph.openGraphExplorer(node.code);
            }
        });
        actions.appendChild(graphBtn);

        // Find Similar button
        const similarBtn = document.createElement('button');
        similarBtn.className = 'btn btn-sm btn-outline-secondary tax-justify-btn search-similar-btn';
        similarBtn.textContent = t('browse.btn.similar');
        similarBtn.title = t('browse.btn.similar.title', node.code);
        similarBtn.setAttribute('aria-label', t('browse.btn.similar.aria', node.code));
        similarBtn.dataset.code = node.code;
        similarBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            if (window.TaxonomySearch) {
                window.TaxonomySearch.findSimilar(node.code);
            }
        });
        actions.appendChild(similarBtn);

        header.appendChild(actions);
        wrapper.appendChild(header);

        // Visible description below the header row
        if (node.description) {
            const desc = document.createElement('div');
            desc.className = 'tax-description';
            desc.innerHTML = escapeHtml(node.description).replace(/\n/g, '<br>');
            wrapper.appendChild(desc);
        }

        // Children container
        if (hasChildren) {
            const childContainer = document.createElement('div');
            childContainer.className = 'tax-children';
            childContainer.setAttribute('role', 'group');
            childContainer.style.display = 'none'; // collapsed by default
            node.children.forEach(child => childContainer.appendChild(buildNodeEl(child, scores)));
            wrapper.appendChild(childContainer);
        }

        return wrapper;
    }

    function toggleNode(wrapper, toggleEl) {
        const children = wrapper.querySelector(':scope > .tax-children');
        if (!children) return;
        const isHidden = children.style.display === 'none';

        if (isHidden && S.interactiveMode && S.storedBusinessText) {
            const code = wrapper.dataset.code;
            if (!S.evaluatedNodes.has(code)) {
                S.evaluatedNodes.add(code);
                evaluateNodeChildren(code, wrapper, toggleEl);
                return; // don't toggle yet — wait for API response
            }
        }

        children.style.display = isHidden ? '' : 'none';
        toggleEl.textContent = isHidden ? '▼' : '▶';
        // Update ARIA expanded state (WCAG 4.1.2)
        wrapper.setAttribute('aria-expanded', isHidden ? 'true' : 'false');
    }

    function evaluateNodeChildren(parentCode, wrapper, toggle) {
        wrapper.classList.add('tax-evaluating');
        console.log('[Taxonomy] evaluateNodeChildren: parentCode=' + parentCode
            + ', businessText=' + (S.storedBusinessText ? S.storedBusinessText.substring(0, 80) : ''));

        const parentScore = (S.currentScores && S.currentScores[parentCode] != null)
            ? S.currentScores[parentCode] : 100;
        fetch('/api/analyze-node?parentCode=' + encodeURIComponent(parentCode)
              + '&businessText=' + encodeURIComponent(S.storedBusinessText)
              + '&parentScore=' + encodeURIComponent(parentScore))
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
                    showStatus('warning', t('browse.eval.llm.issue', parentCode, result.error));
                }

                // Update global reasons
                Object.assign(S.currentReasons, reasons);

                // Append entry to LLM communication log
                SC().appendLlmLogEntry(parentCode, scores, result);

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
                        if (!grandchildren && pct > 0 && S.storedBusinessText) {
                            let justifyBtn = childEl.querySelector('.tax-justify-btn');
                            if (!justifyBtn) {
                                justifyBtn = document.createElement('button');
                                justifyBtn.className = 'btn btn-sm btn-outline-info tax-justify-btn';
                                justifyBtn.textContent = t('browse.btn.justify');
                                justifyBtn.dataset.code = code;
                                justifyBtn.addEventListener('click', function (e) {
                                    e.stopPropagation();
                                    SC().requestLeafJustification(code, justifyBtn);
                                });
                                childEl.appendChild(justifyBtn);
                            }
                        }

                        // Add manual score edit button
                        if (!childEl.querySelector('.tax-manual-btn')) {
                            var manualBtn = document.createElement('button');
                            manualBtn.className = 'btn btn-sm btn-outline-secondary tax-manual-btn ms-1';
                            manualBtn.textContent = '✏️';
                            manualBtn.title = t('browse.manual.score.title');
                            manualBtn.addEventListener('click', function (e) {
                                e.stopPropagation();
                                var score = prompt(t('browse.manual.score.prompt', code));
                                if (score !== null) {
                                    var val = parseInt(score, 10);
                                    if (!isNaN(val) && val >= 0 && val <= 100) {
                                        S.currentScores[code] = val;
                                        S.currentReasons[code] = t('browse.manual.reason');
                                        SC().applyScoreToNode(code, val, 'Manually assigned');
                                        updateExportGroupVisibility();
                                    }
                                }
                            });
                            childEl.appendChild(manualBtn);
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
                if (!S.currentScores) { S.currentScores = {}; }
                Object.assign(S.currentScores, scores);
                updateExportGroupVisibility();

                // Log to console
                const matched = Object.entries(scores).filter(([k, v]) => v > 0);
                console.log('[Taxonomy] Interactive eval of', parentCode, ':', matched.length, 'matches', scores);
            })
            .catch(err => {
                wrapper.classList.remove('tax-evaluating');
                S.evaluatedNodes.delete(parentCode); // allow retry
                console.error('[Taxonomy] Failed to evaluate', parentCode, err);
                showStatus('danger', t('browse.eval.failed', parentCode, err.message));
                // Still expand the node (without scores)
                const children = wrapper.querySelector(':scope > .tax-children');
                if (children) {
                    children.style.display = '';
                    toggle.textContent = '▼';
                }
            });
    }

    function expandAll() {
        document.querySelectorAll('.tax-children').forEach(el => {
            el.style.display = '';
        });
        document.querySelectorAll('.tax-toggle').forEach(el => {
            if (el.textContent === '▶') el.textContent = '▼';
        });
        // Update ARIA expanded state
        document.querySelectorAll('[role="treeitem"][aria-expanded]').forEach(el => {
            el.setAttribute('aria-expanded', 'true');
        });
    }

    function collapseAll() {
        document.querySelectorAll('.tax-children').forEach(el => {
            el.style.display = 'none';
        });
        document.querySelectorAll('.tax-toggle').forEach(el => {
            if (el.textContent === '▼') el.textContent = '▶';
        });
        // Update ARIA expanded state
        document.querySelectorAll('[role="treeitem"][aria-expanded]').forEach(el => {
            el.setAttribute('aria-expanded', 'false');
        });
    }

    // ── UI helpers ────────────────────────────────────────────────────────────
    function showStatus(type, msg) {
        document.getElementById('statusArea').innerHTML =
            '<div class="alert alert-' + type + ' py-2">' + escapeHtml(msg) + '</div>';
        // Announce status to screen readers (WCAG 4.1.3)
        if (type === 'danger') { announceAlert(msg); }
        else { announceStatus(msg); }
    }

    function clearStatus() {
        document.getElementById('statusArea').innerHTML = '';
    }

    function resetStaleResults() {
        S.currentScores = null;
        S.currentReasons = {};
        S.storedBusinessText = null;
        S.evaluatedNodes = new Set();
        S.lastAnalyzedText = null;
        renderView(S.taxonomyData, null);
        clearStatus();
        const businessTextEl = document.getElementById('businessText');
        if (businessTextEl) { businessTextEl.classList.remove('stale-results'); }
        const content = document.getElementById('llmCommLogContent');
        if (content) {
            content.innerHTML = '<div class="text-muted p-2">' + t('browse.llm.log.empty') + '</div>';
        }
    }

    var escapeHtml = TaxonomyUtils.escapeHtml;

    // ── Relation Proposal Review ──────────────────────────────────────────────

    function loadProposals(statusFilter) {
        const container = document.getElementById('proposalsTableContainer');
        if (!container) return;
        container.innerHTML = '<div class="text-muted small p-2" role="status" aria-live="polite">' + t('browse.proposals.loading') + '</div>';
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
                    container.innerHTML = '<div class="text-danger small p-2">' + t('browse.proposals.load.failed', escapeHtml(err.message)) + '</div>';
                }
            });
    }

    function renderProposalTable(proposals) {
        const container = document.getElementById('proposalsTableContainer');
        if (!container) return;

        if (!proposals || proposals.length === 0) {
            container.innerHTML = '<div class="text-muted small p-2">' + t('browse.proposals.none') + '</div>';
            return;
        }

        const hasPending = proposals.some(function (p) { return p.status === 'PENDING'; });

        let html = '';

        // Bulk actions bar (only for pending proposals)
        if (hasPending) {
            html += '<div class="bulk-actions-bar" id="bulkActionsBar">' +
                '<span class="bulk-count" id="bulkCount">' + t('browse.proposals.selected', 0) + '</span>' +
                '<button class="btn btn-sm btn-accept" id="bulkAcceptBtn" disabled>' + t('browse.proposals.accept.selected') + '</button>' +
                '<button class="btn btn-sm btn-reject" id="bulkRejectBtn" disabled>' + t('browse.proposals.reject.selected') + '</button>' +
                '</div>';
        }

        html += '<table class="table table-sm table-hover proposal-table mb-0">' +
            '<thead><tr>';

        if (hasPending) {
            html += '<th scope="col"><input type="checkbox" id="proposalSelectAll" aria-label="' + t('browse.proposals.select.all') + '" title="' + t('browse.proposals.select.all') + '"></th>';
        }

        html += '<th scope="col">' + t('browse.proposals.table.source') + '</th>' +
            '<th scope="col" aria-label="arrow">\u2192</th>' +
            '<th scope="col">' + t('browse.proposals.table.target') + '</th>' +
            '<th scope="col">' + t('browse.proposals.table.type') + '</th>' +
            '<th scope="col">' + t('browse.proposals.table.confidence') + '</th>' +
            '<th scope="col">' + t('browse.proposals.table.rationale') + '</th>' +
            '<th scope="col">' + t('browse.proposals.table.actions') + '</th>' +
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

            html += '<tr>';

            if (hasPending) {
                html += '<td>';
                if (isPending) {
                    html += '<input type="checkbox" class="proposal-select" data-id="' + p.id + '" aria-label="Select proposal ' + p.id + '">';
                }
                html += '</td>';
            }

            html += '<td>' + sourceLabel + '</td>' +
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

        // Attach bulk action event listeners
        if (hasPending) {
            attachBulkListeners();
        }
    }

    function attachBulkListeners() {
        var selectAll = document.getElementById('proposalSelectAll');
        var bulkAccept = document.getElementById('bulkAcceptBtn');
        var bulkReject = document.getElementById('bulkRejectBtn');

        function updateBulkCount() {
            var checked = document.querySelectorAll('.proposal-select:checked');
            var countEl = document.getElementById('bulkCount');
            if (countEl) countEl.textContent = t('browse.proposals.selected', checked.length);
            if (bulkAccept) bulkAccept.disabled = checked.length === 0;
            if (bulkReject) bulkReject.disabled = checked.length === 0;
        }

        if (selectAll) {
            selectAll.addEventListener('change', function () {
                document.querySelectorAll('.proposal-select').forEach(function (cb) {
                    cb.checked = selectAll.checked;
                });
                updateBulkCount();
            });
        }

        document.querySelectorAll('.proposal-select').forEach(function (cb) {
            cb.addEventListener('change', updateBulkCount);
        });

        if (bulkAccept) {
            bulkAccept.addEventListener('click', function () {
                var ids = getSelectedProposalIds();
                if (ids.length === 0) return;
                bulkAction(ids, 'ACCEPT');
            });
        }

        if (bulkReject) {
            bulkReject.addEventListener('click', function () {
                var ids = getSelectedProposalIds();
                if (ids.length === 0) return;
                bulkAction(ids, 'REJECT');
            });
        }
    }

    function getSelectedProposalIds() {
        var ids = [];
        document.querySelectorAll('.proposal-select:checked').forEach(function (cb) {
            ids.push(parseInt(cb.dataset.id, 10));
        });
        return ids;
    }

    function bulkAction(ids, action) {
        fetch('/api/proposals/bulk', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ ids: ids, action: action })
        })
        .then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(function (result) {
            showStatus('success', action === 'ACCEPT'
                ? t('browse.proposals.accepted', result.success)
                : t('browse.proposals.rejected', result.success));
            loadProposals(S.currentProposalFilter);
            // Show undo toast
            showUndoToast(ids, action);
        })
        .catch(function (err) {
            showStatus('danger', t('browse.proposals.bulk.failed', err.message));
        });
    }

    function acceptProposal(id) {
        fetch('/api/proposals/' + id + '/accept', { method: 'POST' })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function () {
                showStatus('success', t('browse.proposals.accept.success'));
                loadProposals(S.currentProposalFilter);
                showUndoToast([id], 'ACCEPT');
            })
            .catch(function (err) {
                console.error('[Taxonomy] Accept proposal failed', err);
                showStatus('danger', t('browse.proposals.accept.failed', err.message));
            });
    }

    function rejectProposal(id) {
        fetch('/api/proposals/' + id + '/reject', { method: 'POST' })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function () {
                showStatus('success', t('browse.proposals.reject.success'));
                loadProposals(S.currentProposalFilter);
                showUndoToast([id], 'REJECT');
            })
            .catch(function (err) {
                console.error('[Taxonomy] Reject proposal failed', err);
                showStatus('danger', t('browse.proposals.reject.failed', err.message));
            });
    }

    // ── Undo Toast ────────────────────────────────────────────────────────────
    var undoTimeout = null;
    var UNDO_TOAST_DURATION_MS = 8000;
    var UNDO_FADE_DURATION_MS = 300;

    function showUndoToast(ids, action) {
        // Remove existing toast
        var existing = document.getElementById('undoToast');
        if (existing) existing.remove();
        if (undoTimeout) clearTimeout(undoTimeout);

        var label = action === 'ACCEPT'
            ? t('browse.proposals.undo.accepted', ids.length)
            : t('browse.proposals.undo.rejected', ids.length);

        var toast = document.createElement('div');
        toast.className = 'undo-toast';
        toast.id = 'undoToast';
        toast.innerHTML = '<span>' + label + '</span>' +
            '<button class="undo-btn" id="undoBtn">' + t('browse.proposals.undo.btn') + '</button>';

        document.body.appendChild(toast);

        var undoBtn = document.getElementById('undoBtn');
        if (undoBtn) {
            undoBtn.addEventListener('click', function () {
                revertProposals(ids);
                toast.remove();
                if (undoTimeout) clearTimeout(undoTimeout);
            });
        }

        // Auto-dismiss after configured duration
        undoTimeout = setTimeout(function () {
            if (toast.parentNode) {
                toast.style.opacity = '0';
                toast.style.transition = 'opacity ' + UNDO_FADE_DURATION_MS + 'ms ease';
                setTimeout(function () { if (toast.parentNode) toast.remove(); }, UNDO_FADE_DURATION_MS);
            }
        }, UNDO_TOAST_DURATION_MS);
    }

    function revertProposals(ids) {
        var promises = ids.map(function (id) {
            return fetch('/api/proposals/' + id + '/revert', { method: 'POST' })
                .then(function (r) {
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    return r.json();
                });
        });

        Promise.all(promises)
            .then(function () {
                showStatus('info', t('browse.proposals.reverted', ids.length));
                loadProposals(S.currentProposalFilter);
            })
            .catch(function (err) {
                showStatus('danger', t('browse.proposals.undo.failed', err.message));
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
                showStatus('success', t('browse.proposals.generated', count, nodeCode, relationType));
                loadProposals(S.currentProposalFilter);
            })
            .catch(function (err) {
                console.error('[Taxonomy] Propose relations failed', err);
                showStatus('danger', t('browse.proposals.generate.failed', err.message));
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
                    badge.textContent = t('browse.proposals.pending', count);
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
    window._getCurrentScores = function () { return S.currentScores; };
    // Test helpers (screenshot tests only — not for production use): allow Selenium tests to set
    // storedBusinessText and re-render the list view so that leaf justify buttons appear without
    // relying on the interactive expansion flow.
    window._setStoredBusinessText = function (text) { S.storedBusinessText = text; };
    window._renderViewWithCurrentScores = function () { renderView(S.taxonomyData, S.currentScores); };

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
                setPromptStatus('danger', t('browse.prompt.load.failed'));
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
                setPromptStatus('success', t('browse.prompt.saved'));
                updateOverrideBadge(code, true);
                // Update local cache
                const entry = promptTemplates.find(t => t.code === code);
                if (entry) { entry.template = template; entry.overridden = true; }
            })
            .catch(function (e) {
                setPromptStatus('danger', t('browse.prompt.save.failed', e.message));
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
                setPromptStatus('success', t('browse.prompt.reset.success'));
                const entry = promptTemplates.find(t => t.code === code);
                if (entry) { entry.template = data.template; entry.overridden = false; }
            })
            .catch(function (e) {
                setPromptStatus('danger', t('browse.prompt.reset.failed', e.message));
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
                setPromptStatus('danger', t('browse.prompt.load.template.failed', code));
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

    // ── Manual Scoring Mode ───────────────────────────────────────────────────

    function enterManualScoringMode() {
        var text = document.getElementById('businessText').value.trim();
        if (!text) {
            showStatus('warning', t('browse.manual.enter.text'));
            return;
        }

        S.storedBusinessText = text;
        S.lastAnalyzedText = text;
        S.currentScores = {};
        S.currentReasons = {};

        renderView(S.taxonomyData, null);
        addManualScoreInputs();

        showStatus('info', t('browse.manual.mode.info'));
        showManualApplyButton();
    }

    function addManualScoreInputs() {
        document.querySelectorAll('.tax-node').forEach(function(node) {
            var code = node.dataset ? node.dataset.code : null;
            if (!code) return;
            var header = node.querySelector(':scope > .tax-node-header');
            if (!header) return;

            var input = document.createElement('input');
            input.type = 'number';
            input.min = '0';
            input.max = '100';
            input.value = '0';
            input.className = 'form-control form-control-sm manual-score-input';
            input.dataset.code = code;
            input.style.cssText = 'width:60px; display:inline-block; margin-left:8px;';
            input.title = t('browse.manual.score.input.title', code);
            input.addEventListener('click', function(e) { e.stopPropagation(); });
            header.appendChild(input);
        });
    }

    function showManualApplyButton() {
        var existing = document.getElementById('manualApplyBtn');
        if (existing) existing.remove();

        var container = document.getElementById('statusArea');
        if (!container) return;

        var btn = document.createElement('button');
        btn.id = 'manualApplyBtn';
        btn.className = 'btn btn-success btn-sm mt-2';
        btn.textContent = t('browse.manual.apply.btn');
        btn.addEventListener('click', applyManualScores);
        container.parentNode.insertBefore(btn, container.nextSibling);
    }

    function applyManualScores() {
        var scores = {};
        var reasons = {};
        document.querySelectorAll('.manual-score-input').forEach(function(input) {
            var val = parseInt(input.value, 10);
            if (!isNaN(val) && val > 0) {
                scores[input.dataset.code] = val;
                reasons[input.dataset.code] = t('browse.manual.reason');
            }
        });

        if (Object.keys(scores).length === 0) {
            showStatus('warning', t('browse.manual.no.scores'));
            return;
        }

        S.currentScores = scores;
        S.currentReasons = reasons;
        window._taxonomyCurrentScores = scores;

        renderView(S.taxonomyData, scores);

        var exportGroup = document.getElementById('exportGroup');
        if (exportGroup) exportGroup.style.display = '';
        var exportHint = document.getElementById('exportHint');
        if (exportHint) exportHint.style.display = 'none';

        showStatus('success', t('browse.manual.applied', Object.keys(scores).length));

        // Remove score inputs and apply button
        document.querySelectorAll('.manual-score-input').forEach(function(el) {
            el.remove();
        });
        var applyBtn = document.getElementById('manualApplyBtn');
        if (applyBtn) applyBtn.remove();
    }

    // ── Public API for cross-module use ──────────────────────────────────────
    window.TaxonomyBrowse = {
        renderView: renderView,
        switchView: switchView,
        showStatus: showStatus,
        clearStatus: clearStatus,
        escapeHtml: escapeHtml,
        updateExportGroupVisibility: updateExportGroupVisibility
    };

})();
