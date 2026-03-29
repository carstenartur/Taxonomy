/* taxonomy-scoring.js – Analysis, Scoring & Streaming logic for the Taxonomy Browser */

(function () {
    'use strict';

    var t = TaxonomyI18n.t;
    var S = window.TaxonomyState;
    // B (browse functions) resolved lazily — script may load before taxonomy-browse.js
    function B() { return window.TaxonomyBrowse || {}; }

    // ── Architecture Summary View ─────────────────────────────────────────────
    var LAYER_CONFIG = {
        // Full sheet names (used by fallback HTML and future backend changes)
        'Capabilities':           { order: 1, cls: 'layer-cap',  icon: '🔵', label: 'Capabilities' },
        'Business Processes':     { order: 2, cls: 'layer-proc', icon: '🟢', label: 'Business Processes' },
        'Business Roles':         { order: 2, cls: 'layer-proc', icon: '🟢', label: 'Business Roles' },
        'Services':               { order: 3, cls: 'layer-svc',  icon: '🟠', label: 'Services' },
        'COI Services':           { order: 3, cls: 'layer-svc',  icon: '🟠', label: 'COI Services' },
        'Core Services':          { order: 3, cls: 'layer-svc',  icon: '🟠', label: 'Core Services' },
        'Applications':           { order: 4, cls: 'layer-app',  icon: '🟣', label: 'Applications' },
        'User Applications':      { order: 4, cls: 'layer-app',  icon: '🟣', label: 'User Applications' },
        'Information Products':   { order: 5, cls: 'layer-info', icon: '🔷', label: 'Information Products' },
        'Communications Services':{ order: 6, cls: 'layer-comm', icon: '🔴', label: 'Communications Services' },
        // 2-letter taxonomy-root prefixes (returned by RequirementArchitectureViewService)
        'CP':                     { order: 1, cls: 'layer-cap',  icon: '🔵', label: 'Capabilities' },
        'BP':                     { order: 2, cls: 'layer-proc', icon: '🟢', label: 'Business Processes' },
        'BR':                     { order: 2, cls: 'layer-proc', icon: '🟢', label: 'Business Roles' },
        'CI':                     { order: 3, cls: 'layer-svc',  icon: '🟠', label: 'COI Services' },
        'CO':                     { order: 6, cls: 'layer-comm', icon: '🔴', label: 'Communications Services' },
        'CR':                     { order: 3, cls: 'layer-svc',  icon: '🟠', label: 'Core Services' },
        'IP':                     { order: 5, cls: 'layer-info', icon: '🔷', label: 'Information Products' },
        'UA':                     { order: 4, cls: 'layer-app',  icon: '🟣', label: 'User Applications' }
    };

    // Minimum number of elements required to render the D3 force graph (otherwise show swimlane)
    var MIN_NODES_FOR_GRAPH = 3;

    // ── Utility ───────────────────────────────────────────────────────────────
    var escapeHtml = TaxonomyUtils.escapeHtml;

    // ── UI helpers ────────────────────────────────────────────────────────────
    function setAnalyzing(on) {
        const btn = document.getElementById('analyzeBtn');
        const spinner = document.getElementById('analyzeSpinner');
        btn.disabled = on;
        spinner.classList.toggle('d-none', !on);
        btn.textContent = on ? ' ' + t('scoring.analyzing') : t('scoring.analyze.btn');
        if (on) btn.prepend(spinner);
    }

    function clearAnalysisLog() {
        const log = document.getElementById('analysisLog');
        if (log) { log.style.display = 'none'; }
        const logContent = document.getElementById('analysisLogContent');
        if (logContent) { logContent.innerHTML = ''; }
        // Also hide architecture view
        const archPanel = document.getElementById('architectureViewPanel');
        if (archPanel) { archPanel.style.display = 'none'; }
        // Also hide suggested relations
        const sugPanel = document.getElementById('suggestedRelationsPanel');
        if (sugPanel) { sugPanel.style.display = 'none'; }
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

    // ── Analysis ──────────────────────────────────────────────────────────────
    function runAnalysis() {
        const text = document.getElementById('businessText').value.trim();
        if (!text) {
            B().showStatus('warning', t('scoring.enter.requirement'));
            return;
        }

        console.log('[Taxonomy] Starting analysis with text:', text.substring(0, 100) + '...');
        const analysisStart = new Date();

        setAnalyzing(true);
        B().clearStatus();
        clearAnalysisLog();
        document.getElementById('businessText').classList.remove('stale-results');

        var providerSelect = document.getElementById('providerSelect');
        var provider = providerSelect ? providerSelect.value : '';

        var requestBody = {
            businessText: text,
            includeArchitectureView: document.getElementById('includeArchitectureView').checked
        };
        if (provider && provider !== 'MANUAL') {
            requestBody.provider = provider;
        }

        fetch('/api/analyze', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(requestBody)
        })
            .then(r => {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(result => {
                setAnalyzing(false);
                S.taxonomyData = result.tree;
                S.currentScores = result.scores;
                S.currentDiscrepancies = result.discrepancies || [];
                S.lastAnalyzedText = text;
                B().renderView(S.taxonomyData, S.currentScores);

                console.log('[Taxonomy] Analysis result:', result);
                console.log('[Taxonomy] Scores:', result.scores);
                const matchedEntries = Object.entries(result.scores).filter(([k, v]) => v > 0);
                console.log('[Taxonomy] Matched nodes:', matchedEntries);

                const matchedCount = Object.values(result.scores).filter(v => v > 0).length;

                if (result.status === 'SUCCESS') {
                    if (matchedCount === 0) {
                        B().showStatus('warning', t('analyze.zero.matches'));
                    } else {
                        B().showStatus('success', t('analyze.complete', matchedCount));
                    }
                } else if (result.status === 'PARTIAL') {
                    B().showStatus('warning',
                        t('analyze.partial', (result.errorMessage || t('analyze.incomplete')), matchedCount));
                } else if (result.status === 'ERROR') {
                    B().showStatus('danger',
                        '❌ ' + t('scoring.analysis.failed', result.errorMessage || 'Unknown error.'));
                } else {
                    if (matchedCount === 0) {
                        B().showStatus('warning', t('analyze.zero.matches'));
                    } else {
                        B().showStatus('success', t('analyze.complete', matchedCount));
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
                // Render suggested relationships from provisional relations
                renderSuggestedRelations(result.provisionalRelations);

                // Render ViewContext provenance info
                if (window.TaxonomyViewContext) {
                    window.TaxonomyViewContext.renderFromResponse('analyzeViewContext', result);
                }

                // Store architecture view and show summary button
                if (result.architectureView) {
                    S.currentArchView = result.architectureView;
                    var summaryBtn = document.getElementById('viewSummary');
                    if (summaryBtn) summaryBtn.style.display = '';
                    // Auto-switch to summary view
                    B().switchView('summary');
                    // Add quick action to navigate to Architecture tab
                    var statusArea = document.getElementById('statusArea');
                    if (statusArea && window.navigateToPage) {
                        var archLink = document.createElement('div');
                        archLink.className = 'mt-2';
                        var archBtn = document.createElement('button');
                        archBtn.className = 'btn btn-sm btn-outline-primary';
                        archBtn.textContent = '\uD83C\uDFDB\uFE0F ' + t('scoring.view.architecture');
                        archBtn.addEventListener('click', function () {
                            window.navigateToPage('architecture');
                        });
                        archLink.appendChild(archBtn);
                        statusArea.appendChild(archLink);
                    }
                }
            })
            .catch(err => {
                setAnalyzing(false);
                B().showStatus('danger', t('scoring.analysis.error', err.message));
                updateAnalysisLog({
                    timestamp: analysisStart,
                    totalNodes: 0,
                    matchedEntries: [],
                    warnings: [t('scoring.error', err.message)],
                    status: 'ERROR'
                });
            });
    }

    // ── Interactive analysis (stores text, renders tree without LLM calls) ─────
    function runInteractiveAnalysis() {
        const text = document.getElementById('businessText').value.trim();
        if (!text) {
            B().showStatus('warning', t('scoring.enter.requirement'));
            return;
        }

        // Reset interactive state
        S.storedBusinessText = text;
        S.lastAnalyzedText = text;
        S.evaluatedNodes = new Set();
        S.currentScores = {};
        S.currentReasons = {};
        document.getElementById('businessText').classList.remove('stale-results');

        // Render the tree without scores; mark all expandable nodes as unevaluated
        B().renderView(S.taxonomyData, null);
        document.querySelectorAll('.tax-node').forEach(function (el) {
            if (el.querySelector(':scope > .tax-children')) {
                el.classList.add('tax-has-unevaluated');
            }
        });

        B().showStatus('info', t('analyze.interactive.info'));
    }

    // ── Streaming analysis (list / tabs views) ────────────────────────────────
    function runStreamingAnalysis() {
        const text = document.getElementById('businessText').value.trim();
        if (!text) {
            B().showStatus('warning', t('scoring.enter.requirement'));
            return;
        }

        setAnalyzing(true);
        B().clearStatus();
        clearAnalysisLog();
        var llmCommLogEl = document.getElementById('llmCommLogContent');
        if (llmCommLogEl) {
            llmCommLogEl.innerHTML = '';
        }
        S.currentScores = {};
        S.currentReasons = {};
        document.getElementById('businessText').classList.remove('stale-results');

        // Render a clean tree without scores first
        B().renderView(S.taxonomyData, null);

        var providerSelect = document.getElementById('providerSelect');
        var provider = providerSelect ? providerSelect.value : '';

        var url = '/api/analyze-stream?businessText=' + encodeURIComponent(text);
        if (provider && provider !== 'MANUAL') {
            url += '&provider=' + encodeURIComponent(provider);
        }
        const eventSource = new EventSource(url);

        eventSource.addEventListener('phase', function (e) {
            const data = JSON.parse(e.data);
            B().showStatus('info', data.message);
        });

        eventSource.addEventListener('scores', function (e) {
            const data = JSON.parse(e.data);
            Object.assign(S.currentScores, data.scores);
            if (data.reasons) { Object.assign(S.currentReasons, data.reasons); }
            Object.entries(data.scores).forEach(function ([code, pct]) {
                applyScoreToNode(code, pct, data.reasons ? data.reasons[code] : null);
            });
            if (data.prompt !== undefined || data.rawResponse !== undefined) {
                appendLlmLogEntry(
                    data.message || data.description || 'streaming',
                    data.scores,
                    {
                        prompt: data.prompt || '',
                        rawResponse: data.rawResponse || '',
                        provider: data.provider || '',
                        durationMs: data.durationMs || 0,
                        reasons: data.reasons || {},
                        error: data.error || null
                    }
                );
            }
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
            S.currentScores = data.totalScores;
            S.currentDiscrepancies = data.discrepancies || [];
            S.lastAnalyzedText = text;
            const matchedCount = Object.values(data.totalScores).filter(v => v > 0).length;
            let statusMsg = t('analyze.complete', matchedCount);
            if (S.currentDiscrepancies.length > 0) {
                statusMsg += ' ⚠️ ' + S.currentDiscrepancies.length + ' scoring discrepanc'
                    + (S.currentDiscrepancies.length === 1 ? 'y' : 'ies') + ' detected.';
                console.log('[Taxonomy] Discrepancies:', S.currentDiscrepancies);
            }
            B().showStatus('success', statusMsg);
            B().updateExportGroupVisibility();
        });

        eventSource.addEventListener('error', function (e) {
            if (e.data) {
                try {
                    const data = JSON.parse(e.data);
                    eventSource.close();
                    setAnalyzing(false);
                    B().showStatus('warning', '⚠️ ' + data.errorMessage);
                } catch (parseErr) {
                    console.error('Failed to parse SSE error event:', parseErr);
                }
            }
        });

        eventSource.onerror = function () {
            eventSource.close();
            setAnalyzing(false);
            B().showStatus('danger', t('analyze.connection.lost'));
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
            // Contrast-safe text color (WCAG 1.4.3)
            if (pct >= 75) { header.style.color = '#fff'; }
            else { header.style.color = '#1a1a1a'; }

            let badge = header.querySelector('.tax-pct');
            if (!badge) {
                badge = document.createElement('span');
                badge.className = 'tax-pct';
                badge.setAttribute('aria-hidden', 'true');
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

        // Update ARIA label with score info (WCAG 4.1.2)
        var nameEl = header.querySelector('.tax-name');
        var ariaLabel = code + ' ' + (nameEl ? nameEl.textContent : '');
        if (pct > 0) {
            ariaLabel += ', Score: ' + pct + ' percent';
            if (reason) { ariaLabel += ', Reason: ' + reason; }
        } else {
            ariaLabel += ', Score: 0 percent';
        }
        el.setAttribute('aria-label', ariaLabel);
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
            el.setAttribute('aria-expanded', 'true');
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
                    pNode.setAttribute('aria-expanded', 'true');
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
        highlightScoringPaths(scores);
    }

    /**
     * Highlight the scoring path from root through intermediate nodes to
     * scored leaves.  Any ancestor of a scored node that itself has a score
     * receives the CSS class {@code tax-scoring-path}, which renders a
     * coloured left-border so readers can trace the hierarchical narrowing.
     */
    function highlightScoringPaths(scores) {
        // Remove previous highlights
        document.querySelectorAll('.tax-scoring-path').forEach(function (el) {
            el.classList.remove('tax-scoring-path');
        });
        if (!scores) return;

        // Build a local index of taxonomy nodes by data-code to avoid
        // repeated global querySelector calls per scored entry.
        var codeToNode = {};
        document.querySelectorAll('.tax-node[data-code]').forEach(function (node) {
            var code = node.dataset.code;
            if (code) {
                codeToNode[code] = node;
            }
        });

        // For every scored leaf (code containing '-'), walk up to root and
        // mark each ancestor that also carries a score.
        Object.entries(scores).forEach(function (entry) {
            var code = entry[0];
            var pct  = entry[1];
            if (pct <= 0 || !code.includes('-')) return;

            var el = codeToNode[code];
            if (!el) return;

            var parent = el.parentElement;
            while (parent) {
                if (parent.classList.contains('tax-node')) {
                    var parentCode = parent.dataset.code;
                    if (parentCode && scores[parentCode] > 0) {
                        parent.classList.add('tax-scoring-path');
                    }
                }
                parent = parent.parentElement;
            }
        });
    }

    // ── Leaf Justification ────────────────────────────────────────────────────
    function requestLeafJustification(nodeCode, btnEl) {
        if (!S.storedBusinessText) {
            B().showStatus('warning', t('analyze.no.text'));
            return;
        }
        const originalText = btnEl.textContent;
        btnEl.disabled = true;
        btnEl.textContent = t('scoring.generating');

        fetch('/api/justify-leaf', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                nodeCode: nodeCode,
                businessText: S.storedBusinessText,
                scores: S.currentScores || {},
                reasons: S.currentReasons || {}
            })
        })
            .then(r => {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(data => {
                btnEl.disabled = false;
                btnEl.textContent = originalText;
                showLeafJustificationModal(nodeCode, data.justification || t('scoring.no.justification'));
            })
            .catch(err => {
                btnEl.disabled = false;
                btnEl.textContent = originalText;
                console.error('[Taxonomy] Failed to get leaf justification for', nodeCode, err);
                B().showStatus('danger', t('scoring.justify.failed', nodeCode, err.message));
            });
    }

    /** Displays the leaf justification in the modal. */
    function showLeafJustificationModal(nodeCode, justification) {
        const modal = document.getElementById('leafJustificationModal');
        const titleEl = document.getElementById('leafJustificationModalTitle');
        const bodyEl = document.getElementById('leafJustificationModalBody');
        if (!modal || !titleEl || !bodyEl) return;
        titleEl.textContent = t('scoring.justification.title', nodeCode);
        bodyEl.textContent = justification;
        bodyEl.setAttribute('data-modal-loaded', 'true');
        const bsModal = new bootstrap.Modal(modal);
        bsModal.show();
    }

    // ── Architecture View (Impact Map) ──────────────────────────────────────

    /** Returns a Bootstrap badge color class for a NodeOrigin or RelationOrigin enum name. */
    function originBadgeColor(origin) {
        if (!origin) return 'light';
        switch (origin) {
            case 'DIRECT_SCORED': return 'success';
            case 'TRACE_INTERMEDIATE': return 'secondary';
            case 'PROPAGATED': return 'info';
            case 'SEED_CONTEXT': return 'warning';
            case 'ENRICHED_LEAF': return 'primary';
            case 'IMPACT_SELECTED': return 'danger';
            case 'TAXONOMY_SEED': return 'warning';
            case 'PROPAGATED_TRACE': return 'secondary';
            case 'IMPACT_DERIVED': return 'danger';
            case 'SUGGESTED_CANDIDATE': return 'info';
            case 'LLM_SUPPORTED': return 'primary';
            default: return 'light';
        }
    }

    function renderArchitectureView(view) {
        const panel = document.getElementById('architectureViewPanel');
        const content = document.getElementById('architectureViewContent');
        const placeholder = document.getElementById('architecturePlaceholder');
        if (!panel || !content) return;

        if (!view) {
            panel.style.display = 'none';
            if (placeholder) { placeholder.style.display = ''; }
            return;
        }

        let html = '';

        // Notes
        if (view.notes && view.notes.length > 0) {
            html += '<div class="alert alert-info py-1 px-2 small mb-2">' +
                view.notes.map(n => escapeHtml(n)).join('<br>') + '</div>';
        }

        const elements = view.includedElements || [];
        const relationships = view.includedRelationships || [];
        const anchors = view.anchors || [];

        // ── Build element-to-sheet lookup ──
        const elByCode = {};
        elements.forEach(el => { elByCode[el.nodeCode] = el; });

        // ── Detect change hotspots ──
        // A hotspot is an anchor with ≥2 outgoing relationships, or a node reached from ≥2 different anchors
        const outCount = {};
        const reachingAnchors = {};
        relationships.forEach(r => {
            outCount[r.sourceCode] = (outCount[r.sourceCode] || 0) + 1;
            // track which anchor reaches each target
            const srcEl = elByCode[r.sourceCode];
            if (srcEl && srcEl.anchor) {
                if (!reachingAnchors[r.targetCode]) reachingAnchors[r.targetCode] = new Set();
                reachingAnchors[r.targetCode].add(r.sourceCode);
            }
        });
        const hotspotCodes = new Set();
        const hotspotReasons = {};
        elements.forEach(el => {
            const reasons = [];
            if (el.anchor && (outCount[el.nodeCode] || 0) >= 2) {
                hotspotCodes.add(el.nodeCode);
                reasons.push('anchor with ' + outCount[el.nodeCode] + ' outgoing relations');
            }
            if (reachingAnchors[el.nodeCode] && reachingAnchors[el.nodeCode].size >= 2) {
                hotspotCodes.add(el.nodeCode);
                reasons.push('reached from ' + reachingAnchors[el.nodeCode].size + ' anchors');
            }
            if (reasons.length > 0) {
                hotspotReasons[el.nodeCode] = reasons.join(', ');
            }
        });

        // ── Group elements by taxonomy sheet ──
        const groups = {};
        elements.forEach(el => {
            const sheet = el.taxonomySheet || 'Unknown';
            if (!groups[sheet]) groups[sheet] = [];
            groups[sheet].push(el);
        });
        const sortedSheets = Object.keys(groups).sort((a, b) => {
            const oa = LAYER_CONFIG[a] ? LAYER_CONFIG[a].order : 99;
            const ob = LAYER_CONFIG[b] ? LAYER_CONFIG[b].order : 99;
            return oa - ob;
        });

        // ── Part 1: Impact Summary Bar ──
        html += '<div class="impact-summary-bar">';
        html += '<span class="impact-kpi">🎯 ' + anchors.length + ' direct matches</span>';
        html += '<span class="impact-kpi">📦 ' + elements.length + ' affected elements</span>';
        html += '<span class="impact-kpi">🔗 ' + relationships.length + ' relations</span>';
        html += '<span class="impact-kpi">🏗️ ' + sortedSheets.length + ' layers</span>';
        if (hotspotCodes.size > 0) {
            // Show concrete hotspot element names, not just a count
            var hotspotNames = [];
            hotspotCodes.forEach(function (code) {
                var el = elByCode[code];
                var name = el && el.title ? el.title : code;
                hotspotNames.push(name);
            });
            html += '<span class="impact-kpi impact-kpi-hotspot">⚠️ Hotspots: ' +
                escapeHtml(hotspotNames.join(', ')) + '</span>';
        }
        html += '</div>';

        // ── Part 2: Interactive Impact Network Graph + Swimlane Toggle ──
        if (elements.length > 0) {
            // Collect relationship types between layers for swimlane edge labels
            const layerRelations = {};
            relationships.forEach(r => {
                const srcSheet = elByCode[r.sourceCode] ? elByCode[r.sourceCode].taxonomySheet : null;
                const tgtSheet = elByCode[r.targetCode] ? elByCode[r.targetCode].taxonomySheet : null;
                if (srcSheet && tgtSheet && srcSheet !== tgtSheet) {
                    const key = srcSheet + '→' + tgtSheet;
                    if (!layerRelations[key]) layerRelations[key] = new Set();
                    layerRelations[key].add(r.relationType);
                }
            });

            // Toggle buttons
            html += '<div class="impact-graph-toggle">';
            html += '<button class="btn btn-sm btn-primary impact-view-btn" data-mode="graph">🔗 Network Graph</button>';
            html += '<button class="btn btn-sm btn-outline-secondary impact-view-btn" data-mode="swimlane">🏗️ Layer View</button>';
            html += '</div>';

            // Graph container (shown by default)
            html += '<div id="impactGraphView"></div>';

            // Swimlane fallback (hidden by default)
            html += '<div id="impactSwimView" style="display:none;">';
            html += '<div class="impact-map">';
            for (let i = 0; i < sortedSheets.length; i++) {
                const sheet = sortedSheets[i];
                const cfg = LAYER_CONFIG[sheet] || { order: 99, cls: '', icon: '⬜', label: sheet };
                const layerElements = groups[sheet];

                // Edge between swimlanes
                if (i > 0) {
                    const prevSheet = sortedSheets[i - 1];
                    const key = prevSheet + '→' + sheet;
                    const rKey = sheet + '→' + prevSheet;
                    const relTypes = layerRelations[key] || layerRelations[rKey] || new Set();
                    html += '<div class="impact-edge">│';
                    if (relTypes.size > 0) {
                        Array.from(relTypes).forEach(rt => {
                            html += ' <span class="impact-edge-label">' + escapeHtml(rt) + '</span>';
                        });
                    }
                    html += '<br>▼</div>';
                }

                html += '<div class="impact-swimlane ' + cfg.cls + '">';
                html += '<div class="impact-swimlane-title">' + cfg.icon + ' ' + escapeHtml(cfg.label) +
                    ' <span class="badge bg-secondary" style="font-size:0.7rem;">' + layerElements.length + '</span></div>';

                html += '<div class="impact-swimlane-nodes">';
                layerElements.sort(function(a, b) {
                    var aIsLeaf = a.nodeCode.includes('-') ? 0 : 1;
                    var bIsLeaf = b.nodeCode.includes('-') ? 0 : 1;
                    if (aIsLeaf !== bIsLeaf) return aIsLeaf - bIsLeaf;
                    return b.relevance - a.relevance;
                });
                layerElements.forEach(el => {
                    const pct = (el.relevance * 100).toFixed(0);
                    let nodeClasses = 'impact-node';
                    if (el.anchor) nodeClasses += ' impact-node-anchor';
                    if (hotspotCodes.has(el.nodeCode)) nodeClasses += ' impact-node-hotspot';
                    const opacity = 0.6 + (el.relevance * 0.4);
                    let titleParts = el.nodeCode + ' ' + (el.title || '') + ' — ' + (el.includedBecause || '');
                    if (hotspotCodes.has(el.nodeCode)) {
                        titleParts += ' | ⚠️ hotspot: ' + (hotspotReasons[el.nodeCode] || t('analyze.hotspot.risk'));
                    }
                    html += '<span class="' + nodeClasses + '" style="opacity:' + opacity.toFixed(2) + '"' +
                        ' title="' + escapeHtml(titleParts) + '">';
                    if (el.title) {
                        html += '<strong>' + escapeHtml(el.title.substring(0, 40)) + '</strong>';
                        html += ' <span class="impact-node-code">' + escapeHtml(el.nodeCode) + '</span>';
                    } else {
                        html += escapeHtml(el.nodeCode);
                    }
                    if (el.anchor) {
                        html += ' <span class="impact-badge">★ ' + pct + '%</span>';
                    } else {
                        html += ' <span class="impact-badge">' + pct + '%</span>';
                    }
                    if (hotspotCodes.has(el.nodeCode)) {
                        html += ' <span class="impact-badge impact-badge-hotspot">⚠️ ' +
                            escapeHtml((hotspotReasons[el.nodeCode] || 'hotspot').substring(0, 40)) + '</span>';
                    }
                    html += '</span>';
                });
                html += '</div></div>';
            }
            html += '</div>';
            html += '</div>'; // end impactSwimView
        }

        // ── Part 3: Detail Tables (collapsible) ──
        const hasElements = elements.length > 0;
        const hasRelationships = relationships.length > 0;
        if (hasElements || hasRelationships) {
            html += '<details class="impact-details" open>';
            html += '<summary>📋 Detail: ' + elements.length + ' Elements, ' + relationships.length + ' Relationships</summary>';

            // Elements table
            if (hasElements) {
                html += '<h6 class="mb-1 mt-2">' + t('archview.col.origin') + ' &amp; Elements</h6>';
                html += '<div class="table-responsive"><table class="table table-sm table-bordered small mb-2">';
                html += '<thead><tr><th>Code</th><th>Title</th><th>' + t('archview.col.scoring.path') + '</th><th>Sheet</th><th>Relevance</th><th>' + t('archview.col.llm.score') + '</th><th>' + t('archview.col.origin') + '</th><th>' + t('archview.col.impact') + '</th></tr></thead><tbody>';
                elements.forEach(e => {
                    const rowClass = e.selectedForImpact ? 'table-warning' : (e.anchor ? 'table-success' : '');
                    const sheetCfg = LAYER_CONFIG[e.taxonomySheet];
                    const sheetLabel = sheetCfg ? sheetCfg.label : (e.taxonomySheet || '');
                    const pathLabel = e.scoringPath || e.hierarchyPath || e.nodeCode;
                    const originKey = e.origin ? 'node.origin.' + e.origin.replace(/_/g, '.').toLowerCase() : '';
                    const originLabel = originKey ? t(originKey) : (e.includedBecause || '');
                    const originBadge = e.origin ? '<span class="badge bg-' + originBadgeColor(e.origin) + ' text-dark">' + escapeHtml(originLabel) + '</span>' : escapeHtml(e.includedBecause || '');
                    html += '<tr class="' + rowClass + '">' +
                        '<td>' + escapeHtml(e.nodeCode) + '</td>' +
                        '<td>' + escapeHtml(e.title || '') + '</td>' +
                        '<td class="text-muted small">' + escapeHtml(pathLabel) + '</td>' +
                        '<td>' + escapeHtml(sheetLabel) + '</td>' +
                        '<td>' + (e.relevance * 100).toFixed(1) + '%</td>' +
                        '<td>' + (e.directLlmScore || 0) + '</td>' +
                        '<td>' + originBadge + '</td>' +
                        '<td>' + (e.selectedForImpact ? '🎯' : '') +
                        (e.anchor ? ' ★' : '') +
                        (hotspotCodes.has(e.nodeCode) ? ' ⚠️' : '') + '</td>' +
                        '</tr>';
                });
                html += '</tbody></table></div>';
            }

            // Relationships table — split into impact and trace
            if (hasRelationships) {
                var impactRels = relationships.filter(function(r) { return r.relationCategory === 'impact'; });
                var traceRels = relationships.filter(function(r) { return r.relationCategory !== 'impact'; });

                if (impactRels.length > 0) {
                    html += '<h6 class="mb-1">' + t('archview.impact.relations.title') + ' <span class="badge bg-primary">' + impactRels.length + '</span></h6>';
                    html += '<div class="table-responsive"><table class="table table-sm table-bordered small mb-2">';
                    html += '<thead><tr><th>' + t('archview.impact.col.source') + '</th><th>\u2192</th><th>' + t('archview.impact.col.target') + '</th><th>' + t('archview.impact.col.type') + '</th><th>' + t('archview.impact.col.relevance') + '</th><th>' + t('archview.col.confidence') + '</th><th>' + t('archview.col.origin') + '</th><th>' + t('archview.col.derivation') + '</th></tr></thead><tbody>';
                    impactRels.forEach(r => {
                        const relOriginKey = r.origin ? 'relation.origin.' + r.origin.replace(/_/g, '.').toLowerCase() : '';
                        const relOriginLabel = relOriginKey ? '<span class="badge bg-' + originBadgeColor(r.origin) + ' text-dark">' + escapeHtml(t(relOriginKey)) + '</span>' : '';
                        html += '<tr class="table-info">' +
                            '<td>' + escapeHtml(r.sourceCode) + '</td>' +
                            '<td>\u2192</td>' +
                            '<td>' + escapeHtml(r.targetCode) + '</td>' +
                            '<td>' + escapeHtml(r.relationType) + '</td>' +
                            '<td>' + (r.propagatedRelevance * 100).toFixed(1) + '%</td>' +
                            '<td>' + (r.confidence ? (r.confidence * 100).toFixed(0) + '%' : '') + '</td>' +
                            '<td>' + relOriginLabel + '</td>' +
                            '<td class="small text-muted">' + escapeHtml(r.derivationReason || r.includedBecause || '') + '</td>' +
                            '</tr>';
                    });
                    html += '</tbody></table></div>';
                }

                if (traceRels.length > 0) {
                    html += '<h6 class="mb-1">' + t('archview.trace.relations.title') + ' <span class="badge bg-secondary">' + traceRels.length + '</span></h6>';
                    html += '<div class="table-responsive"><table class="table table-sm table-bordered small mb-0">';
                    html += '<thead><tr><th>' + t('archview.trace.col.source') + '</th><th>\u2192</th><th>' + t('archview.trace.col.target') + '</th><th>' + t('archview.trace.col.type') + '</th><th>' + t('archview.trace.col.relevance') + '</th><th>' + t('archview.trace.col.hops') + '</th><th>' + t('archview.col.origin') + '</th><th>' + t('archview.trace.col.reason') + '</th></tr></thead><tbody>';
                    traceRels.forEach(r => {
                        const traceOriginKey = r.origin ? 'relation.origin.' + r.origin.replace(/_/g, '.').toLowerCase() : '';
                        const traceOriginLabel = traceOriginKey ? '<span class="badge bg-' + originBadgeColor(r.origin) + ' text-dark">' + escapeHtml(t(traceOriginKey)) + '</span>' : '';
                        html += '<tr>' +
                            '<td>' + escapeHtml(r.sourceCode) + '</td>' +
                            '<td>\u2192</td>' +
                            '<td>' + escapeHtml(r.targetCode) + '</td>' +
                            '<td>' + escapeHtml(r.relationType) + '</td>' +
                            '<td>' + (r.propagatedRelevance * 100).toFixed(1) + '%</td>' +
                            '<td>' + r.hopDistance + '</td>' +
                            '<td>' + traceOriginLabel + '</td>' +
                            '<td class="small text-muted">' + escapeHtml(r.derivationReason || r.includedBecause || '') + '</td>' +
                            '</tr>';
                    });
                    html += '</tbody></table></div>';
                }
            }

            html += '</details>';
        }

        if (!html) {
            html = '<p class="text-muted small mb-0">Architecture view is empty.</p>';
        }

        content.innerHTML = html;
        panel.style.display = '';
        if (placeholder) { placeholder.style.display = 'none'; }

        // ── Render D3 impact graph if available ──
        var graphContainer = document.getElementById('impactGraphView');
        if (graphContainer && elements.length >= MIN_NODES_FOR_GRAPH &&
            typeof TaxonomyGraph !== 'undefined' && TaxonomyGraph.renderImpactForceGraph) {

            var graphNodes = elements.map(function (el) {
                return {
                    nodeCode: el.nodeCode,
                    title: el.title || el.nodeCode,
                    taxonomySheet: el.taxonomySheet,
                    hopDistance: el.hopDistance,
                    relevance: el.relevance,
                    anchor: el.anchor,
                    includedBecause: el.includedBecause
                };
            });

            var graphEdges = relationships.map(function (r) {
                return {
                    sourceCode: r.sourceCode,
                    targetCode: r.targetCode,
                    relationType: r.relationType,
                    propagatedRelevance: r.propagatedRelevance
                };
            });

            TaxonomyGraph.renderImpactForceGraph(graphContainer, graphNodes, graphEdges, {
                anchorCodes: new Set(anchors.map(function (a) { return a.nodeCode; })),
                hotspotCodes: hotspotCodes,
                hotspotReasons: hotspotReasons,
                layerConfig: LAYER_CONFIG
            });
        } else if (graphContainer && elements.length < MIN_NODES_FOR_GRAPH) {
            // Too few nodes for a meaningful graph — show swimlane view instead
            graphContainer.style.display = 'none';
            var swimView = document.getElementById('impactSwimView');
            if (swimView) swimView.style.display = '';
            // Update toggle button states
            content.querySelectorAll('.impact-view-btn').forEach(function (btn) {
                btn.classList.toggle('btn-primary', btn.dataset.mode === 'swimlane');
                btn.classList.toggle('btn-outline-secondary', btn.dataset.mode !== 'swimlane');
            });
        }

        // ── Toggle button handlers ──
        content.querySelectorAll('.impact-view-btn').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var mode = btn.dataset.mode;
                var gView = document.getElementById('impactGraphView');
                var sView = document.getElementById('impactSwimView');
                if (gView) gView.style.display = mode === 'graph' ? '' : 'none';
                if (sView) sView.style.display = mode === 'swimlane' ? '' : 'none';
                content.querySelectorAll('.impact-view-btn').forEach(function (b) {
                    b.classList.toggle('btn-primary', b.dataset.mode === mode);
                    b.classList.toggle('btn-outline-secondary', b.dataset.mode !== mode);
                });
            });
        });
    }

    /**
     * Renders the Suggested Relationships panel from provisional relation hypotheses.
     */
    function renderSuggestedRelations(provisionalRelations) {
        var panel = document.getElementById('suggestedRelationsPanel');
        var content = document.getElementById('suggestedRelationsContent');
        var badge = document.getElementById('suggestedRelationsBadge');
        if (!panel || !content) return;

        if (!provisionalRelations || provisionalRelations.length === 0) {
            panel.style.display = 'none';
            return;
        }

        if (badge) badge.textContent = provisionalRelations.length;

        var html = '';
        html += '<div class="d-flex justify-content-between align-items-center mb-2">';
        html += '<small class="text-muted">AI-generated relationship suggestions based on analysis scores</small>';
        html += '<button class="btn btn-sm btn-outline-success" onclick="window._acceptAllHighConfidence()" title="Accept all suggestions with confidence ≥ 80%" aria-label="Accept all suggestions with confidence 80% or higher">✅ Accept all ≥80%</button>';
        html += '</div>';
        html += '<div class="table-responsive"><table class="table table-sm table-bordered small mb-0">';
        html += '<thead><tr><th>Source</th><th>→</th><th>Target</th><th>Type</th><th>Confidence</th><th>Reasoning</th><th>Actions</th></tr></thead><tbody>';

        provisionalRelations.forEach(function (h, idx) {
            var confPct = (h.confidence * 100).toFixed(0);
            var confClass = h.confidence >= 0.8 ? 'text-success fw-bold' : (h.confidence >= 0.5 ? 'text-warning' : 'text-danger');
            html += '<tr id="suggested-row-' + idx + '">' +
                '<td>' + escapeHtml(h.sourceCode) + (h.sourceName ? '<br><small class="text-muted">' + escapeHtml(h.sourceName) + '</small>' : '') + '</td>' +
                '<td>→</td>' +
                '<td>' + escapeHtml(h.targetCode) + (h.targetName ? '<br><small class="text-muted">' + escapeHtml(h.targetName) + '</small>' : '') + '</td>' +
                '<td><span class="badge bg-secondary">' + escapeHtml(h.relationType) + '</span></td>' +
                '<td class="' + confClass + '">' + confPct + '%</td>' +
                '<td><small>' + escapeHtml(h.reasoning || '') + '</small></td>' +
                '<td class="text-nowrap">' +
                '<button class="btn btn-sm btn-outline-success me-1" onclick="window._acceptHypothesis(' + idx + ')" title="Accept permanently" aria-label="Accept relationship ' + escapeHtml(h.sourceCode) + ' to ' + escapeHtml(h.targetCode) + '">✅</button>' +
                '<button class="btn btn-sm btn-outline-info me-1" onclick="window._applyForSession(' + idx + ')" title="Apply for this analysis only" aria-label="Apply relationship ' + escapeHtml(h.sourceCode) + ' to ' + escapeHtml(h.targetCode) + ' for this session">📌</button>' +
                '<button class="btn btn-sm btn-outline-danger" onclick="window._rejectHypothesis(' + idx + ')" title="Dismiss" aria-label="Dismiss relationship ' + escapeHtml(h.sourceCode) + ' to ' + escapeHtml(h.targetCode) + '">❌</button>' +
                '</td></tr>';
        });

        html += '</tbody></table></div>';
        content.innerHTML = html;
        panel.style.display = '';

        // Store for action handlers
        window._currentProvisionalRelations = provisionalRelations;
    }

    /**
     * Accept a single hypothesis: create proposal and auto-accept.
     */
    window._acceptHypothesis = function (idx) {
        var h = window._currentProvisionalRelations && window._currentProvisionalRelations[idx];
        if (!h) return;
        var row = document.getElementById('suggested-row-' + idx);
        if (row) row.style.opacity = '0.5';

        fetch('/api/proposals/from-hypothesis', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                sourceCode: h.sourceCode,
                targetCode: h.targetCode,
                relationType: h.relationType,
                confidence: h.confidence,
                rationale: h.reasoning
            })
        })
        .then(function (r) {
            if (r.status === 409) {
                // Proposal already exists, mark as already processed
                if (row) { row.classList.add('table-info'); row.style.opacity = '1'; }
                var actions = row && row.querySelector('td:last-child');
                if (actions) actions.innerHTML = '<span class="badge bg-info">' + escapeHtml(t('scoring.badge.already.exists')) + '</span>';
                B().showStatus('info', t('analyze.proposal.exists', h.sourceCode, h.targetCode));
                return null;
            }
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(function (proposal) {
            if (proposal === null) return; // already handled (409)
            if (proposal && proposal.id) {
                // Auto-accept the created proposal
                return fetch('/api/proposals/' + proposal.id + '/accept', { method: 'POST' });
            }
        })
        .then(function () {
            if (row) { row.classList.add('table-success'); row.style.opacity = '1'; }
            var actions = row && row.querySelector('td:last-child');
            if (actions) actions.innerHTML = '<span class="badge bg-success">' + escapeHtml(t('scoring.badge.accepted')) + '</span>';
            B().showStatus('success', t('scoring.relation.accepted', h.sourceCode, h.targetCode));
        })
        .catch(function (err) {
            if (row) row.style.opacity = '1';
            B().showStatus('danger', t('scoring.relation.accept.failed', err.message));
        });
    };

    /**
     * Dismiss a hypothesis (just hides the row — no server action needed).
     */
    window._rejectHypothesis = function (idx) {
        var row = document.getElementById('suggested-row-' + idx);
        if (row) { row.classList.add('table-danger'); row.style.opacity = '0.4'; }
        var actions = row && row.querySelector('td:last-child');
        if (actions) actions.innerHTML = '<span class="badge bg-secondary">' + escapeHtml(t('scoring.badge.dismissed')) + '</span>';
    };

    /**
     * Apply a hypothesis for the current analysis session only.
     * Marks the hypothesis as "applied in current analysis" without permanently
     * accepting it. The relationship is used in the current Architecture View
     * and exports but is not persisted as a TaxonomyRelation.
     */
    window._applyForSession = function (idx) {
        var h = window._currentProvisionalRelations && window._currentProvisionalRelations[idx];
        if (!h) return;
        var row = document.getElementById('suggested-row-' + idx);

        // Mark as applied in current analysis via API
        if (h.hypothesisId) {
            fetch('/api/dsl/hypotheses/' + h.hypothesisId + '/apply-session', { method: 'POST' })
                .catch(function () { /* best effort */ });
        }

        // Mark in UI
        if (row) { row.classList.add('table-info'); }
        var actions = row && row.querySelector('td:last-child');
        if (actions) actions.innerHTML = '<span class="badge bg-info">' + escapeHtml(t('scoring.badge.session.only')) + '</span>';
        h.appliedInCurrentAnalysis = true;
        B().showStatus('info', '📌 Applied for this session: ' + h.sourceCode + ' → ' + h.targetCode);
    };

    /**
     * Accept all hypotheses with confidence >= 80%.
     */
    window._acceptAllHighConfidence = function () {
        var relations = window._currentProvisionalRelations;
        if (!relations) return;
        relations.forEach(function (h, idx) {
            if (h.confidence >= 0.8) {
                window._acceptHypothesis(idx);
            }
        });
    };

    // ── Summary View ──────────────────────────────────────────────────────────
    function renderSummaryView(data, scores) {
        var container = document.getElementById('taxonomyTree');
        if (!container) return;

        var view = S.currentArchView;
        if (!view || !view.includedElements || view.includedElements.length === 0) {
            container.innerHTML = '<div class="summary-view"><p class="text-muted">' + escapeHtml(t('scoring.no.archview')) + '</p></div>';
            return;
        }

        var bt = document.getElementById('businessText');
        var requirement = bt ? bt.value.trim() : '';

        // Group elements by taxonomy sheet
        var groups = {};
        view.includedElements.forEach(function (el) {
            var sheet = el.taxonomySheet || 'Unknown';
            if (!groups[sheet]) groups[sheet] = [];
            groups[sheet].push(el);
        });

        // Sort groups by layer order
        var sortedSheets = Object.keys(groups).sort(function (a, b) {
            var oa = LAYER_CONFIG[a] ? LAYER_CONFIG[a].order : 99;
            var ob = LAYER_CONFIG[b] ? LAYER_CONFIG[b].order : 99;
            return oa - ob;
        });

        // Collect relationship types between layers for arrow labels
        var layerRelations = {};
        if (view.includedRelationships) {
            view.includedRelationships.forEach(function (r) {
                var srcSheet = null, tgtSheet = null;
                view.includedElements.forEach(function (el) {
                    if (el.nodeCode === r.sourceCode) srcSheet = el.taxonomySheet;
                    if (el.nodeCode === r.targetCode) tgtSheet = el.taxonomySheet;
                });
                if (srcSheet && tgtSheet && srcSheet !== tgtSheet) {
                    var key = srcSheet + '→' + tgtSheet;
                    if (!layerRelations[key]) layerRelations[key] = new Set();
                    layerRelations[key].add(r.relationType);
                }
            });
        }

        var html = '<div class="summary-view">';
        html += '<div class="summary-header">📋 Architecture Summary</div>';
        if (requirement) {
            html += '<div class="summary-requirement">"' + escapeHtml(requirement.substring(0, 200)) +
                (requirement.length > 200 ? '…' : '') + '"</div>';
        }

        for (var i = 0; i < sortedSheets.length; i++) {
            var sheet = sortedSheets[i];
            var cfg = LAYER_CONFIG[sheet] || { order: 99, cls: '', icon: '⬜', label: sheet };
            var elements = groups[sheet];

            // Arrow between groups
            if (i > 0) {
                var prevSheet = sortedSheets[i - 1];
                var key = prevSheet + '→' + sheet;
                var rKey = sheet + '→' + prevSheet;
                var relTypes = layerRelations[key] || layerRelations[rKey] || new Set();
                var arrowLabel = relTypes.size > 0 ? Array.from(relTypes).join(', ') : '';
                html += '<div class="summary-arrow">│<br>';
                if (arrowLabel) html += '<span class="arrow-label">' + escapeHtml(arrowLabel) + '</span><br>';
                html += '▼</div>';
            }

            html += '<div class="summary-layer ' + cfg.cls + '">';
            html += '<div class="summary-layer-title">' + cfg.icon + ' ' + escapeHtml(cfg.label) +
                ' <span class="badge bg-secondary" style="font-size:0.7rem;">' + elements.length + '</span></div>';

            elements.sort(function (a, b) { return b.relevance - a.relevance; });
            elements.forEach(function (el) {
                var pct = (el.relevance * 100).toFixed(0);
                // Use full hierarchy path from backend (e.g. "CP > CP-1000 > CP-1023")
                var path = el.hierarchyPath || el.nodeCode;
                var titleParts = [path, el.title || '', el.includedBecause || '']
                    .filter(function (p) { return p.length > 0; });
                html += '<span class="summary-layer-element" data-code="' + escapeHtml(el.nodeCode) +
                    '" title="' + escapeHtml(titleParts.join(' — ')) + '">';
                html += escapeHtml(el.nodeCode);
                if (el.title) html += ' \u2013 ' + escapeHtml(el.title.substring(0, 50));
                html += ' <span class="summary-pct">[' + pct + '%]</span>';
                if (el.anchor) html += ' ★';
                html += '</span>';
            });

            html += '</div>';
        }

        // Stats footer
        var anchorCount = view.anchors ? view.anchors.length : 0;
        var elemCount = view.includedElements ? view.includedElements.length : 0;
        var relCount = view.includedRelationships ? view.includedRelationships.length : 0;
        html += '<div class="summary-stats">';
        html += anchorCount + ' Anchors · ' + elemCount + ' Elements · ' + relCount + ' Relations';
        html += ' · ' + sortedSheets.length + ' Layers';
        html += '</div>';
        html += '</div>';

        container.innerHTML = html;

        // Click handler: navigate to node in list view
        container.querySelectorAll('.summary-layer-element').forEach(function (el) {
            el.addEventListener('click', function () {
                var code = this.dataset.code;
                if (code) {
                    B().switchView('list');
                    setTimeout(function () {
                        var nodeEl = document.querySelector('[data-node-code="' + code + '"]');
                        if (nodeEl) {
                            nodeEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
                            nodeEl.classList.add('search-highlight');
                            setTimeout(function () { nodeEl.classList.remove('search-highlight'); }, 2000);
                        }
                    }, 200);
                }
            });
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────
    window.TaxonomyScoring = {
        runAnalysis: runAnalysis,
        runInteractiveAnalysis: runInteractiveAnalysis,
        runStreamingAnalysis: runStreamingAnalysis,
        applyScoreToNode: applyScoreToNode,
        markNodeAsEvaluating: markNodeAsEvaluating,
        expandMatched: expandMatched,
        highlightScoringPaths: highlightScoringPaths,
        expandNodeByCode: expandNodeByCode,
        setAnalyzing: setAnalyzing,
        clearAnalysisLog: clearAnalysisLog,
        updateAnalysisLog: updateAnalysisLog,
        appendLlmLogEntry: appendLlmLogEntry,
        requestLeafJustification: requestLeafJustification,
        showLeafJustificationModal: showLeafJustificationModal,
        renderArchitectureView: renderArchitectureView,
        renderSuggestedRelations: renderSuggestedRelations,
        renderSummaryView: renderSummaryView,
        escapeHtml: escapeHtml
    };

})();
