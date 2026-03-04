/* taxonomy.js – frontend logic for NATO NC3T Taxonomy Browser */

(function () {
    'use strict';

    let taxonomyData = [];
    let currentScores = null;
    let currentView = 'list'; // 'list' | 'tabs' | 'sunburst' | 'tree'

    // ── Bootstrap ─────────────────────────────────────────────────────────────
    document.addEventListener('DOMContentLoaded', function () {
        loadTaxonomy();
        checkAiStatus();
        document.getElementById('analyzeBtn').addEventListener('click', function () {
            if (currentView === 'list' || currentView === 'tabs') {
                runStreamingAnalysis();
            } else {
                runAnalysis();
            }
        });
        document.getElementById('expandAll').addEventListener('click', expandAll);
        document.getElementById('collapseAll').addEventListener('click', collapseAll);

        // View switcher buttons
        ['viewList', 'viewTabs', 'viewSunburst', 'viewTree'].forEach(function (id) {
            const btn = document.getElementById(id);
            if (btn) {
                btn.addEventListener('click', function () {
                    switchView(btn.dataset.view);
                });
            }
        });
    });

    // ── Check AI availability ─────────────────────────────────────────────────
    function checkAiStatus() {
        fetch('/api/ai-status')
            .then(r => r.json())
            .then(status => {
                const btn = document.getElementById('analyzeBtn');
                const infoEl = document.getElementById('aiProviderInfo');
                if (status.available) {
                    btn.disabled = false;
                    infoEl.textContent = 'Using: ' + status.provider;
                    infoEl.classList.remove('d-none');
                } else {
                    btn.disabled = true;
                    infoEl.classList.add('d-none');
                    showStatus('warning',
                        '⚠️ AI analysis is not available — no LLM API key is configured. ' +
                        'Set one of the following environment variables: GEMINI_API_KEY, ' +
                        'OPENAI_API_KEY, DEEPSEEK_API_KEY, DASHSCOPE_API_KEY, LLAMA_API_KEY, ' +
                        'or MISTRAL_API_KEY.');
                }
            })
            .catch(() => {
                // If the status check fails, leave the button enabled and don't show a warning.
            });
    }

    // ── Load taxonomy tree from API ───────────────────────────────────────────
    function loadTaxonomy() {
        fetch('/api/taxonomy')
            .then(r => r.json())
            .then(data => {
                taxonomyData = data;
                renderView(data, null);
            })
            .catch(err => {
                document.getElementById('taxonomyTree').innerHTML =
                    '<div class="alert alert-danger">Failed to load taxonomy: ' + err + '</div>';
            });
    }

    // ── View switching ────────────────────────────────────────────────────────
    function switchView(view) {
        currentView = view;

        // Update button active states
        const viewIds = { list: 'viewList', tabs: 'viewTabs', sunburst: 'viewSunburst', tree: 'viewTree' };
        Object.entries(viewIds).forEach(([v, id]) => {
            const btn = document.getElementById(id);
            if (!btn) { return; }
            btn.classList.toggle('btn-primary', v === view);
            btn.classList.toggle('btn-outline-secondary', v !== view);
        });

        // Show/hide Expand All / Collapse All only for list & tabs views
        const ecGroup = document.getElementById('expandCollapseGroup');
        if (ecGroup) {
            ecGroup.style.display = (view === 'sunburst' || view === 'tree') ? 'none' : '';
        }

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
                    window.TaxonomyViews.renderTreeDiagram(
                        document.getElementById('taxonomyTree'), data, scores);
                }
                break;
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

        return wrapper;
    }

    function toggleNode(wrapper, toggleEl) {
        const children = wrapper.querySelector(':scope > .tax-children');
        if (!children) return;
        const isHidden = children.style.display === 'none';
        children.style.display = isHidden ? '' : 'none';
        toggleEl.textContent = isHidden ? '▼' : '▶';
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

        setAnalyzing(true);
        clearStatus();

        fetch('/api/analyze', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ businessText: text })
        })
            .then(r => {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(result => {
                setAnalyzing(false);
                taxonomyData = result.tree;
                currentScores = result.scores;
                renderView(taxonomyData, currentScores);

                const matchedCount = Object.values(result.scores).filter(v => v > 0).length;

                if (result.status === 'SUCCESS') {
                    showStatus('success',
                        'Analysis complete. ' + matchedCount + ' node(s) matched.');
                } else if (result.status === 'PARTIAL') {
                    showStatus('warning',
                        '⚠️ Partial results — ' + (result.errorMessage || 'Analysis incomplete.') +
                        ' ' + matchedCount + ' node(s) matched so far.');
                } else if (result.status === 'ERROR') {
                    showStatus('danger',
                        '❌ Analysis failed: ' + (result.errorMessage || 'Unknown error.'));
                } else {
                    showStatus('success',
                        'Analysis complete. ' + matchedCount + ' node(s) matched.');
                }

                if (result.warnings && result.warnings.length > 0) {
                    const warningList = result.warnings
                        .map(w => '<li>' + escapeHtml(w) + '</li>')
                        .join('');
                    document.getElementById('statusArea').innerHTML +=
                        '<ul class="mb-0 mt-1 ps-3" style="font-size:0.9em">' + warningList + '</ul>';
                }
            })
            .catch(err => {
                setAnalyzing(false);
                showStatus('danger', 'Analysis failed: ' + err.message);
            });
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
            Object.entries(data.scores).forEach(function ([code, pct]) {
                applyScoreToNode(code, pct);
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
            const matchedCount = Object.values(data.totalScores).filter(v => v > 0).length;
            showStatus('success', '✅ Analysis complete. ' + matchedCount + ' node(s) matched.');
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

    /** Apply a score to a node already in the DOM without re-rendering the tree. */
    function applyScoreToNode(code, pct) {
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

    function escapeHtml(s) {
        return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

})();

