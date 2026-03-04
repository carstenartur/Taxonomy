/* taxonomy.js – frontend logic for NATO NC3T Taxonomy Browser */

(function () {
    'use strict';

    let taxonomyData = [];

    // ── Bootstrap ─────────────────────────────────────────────────────────────
    document.addEventListener('DOMContentLoaded', function () {
        loadTaxonomy();
        document.getElementById('analyzeBtn').addEventListener('click', runAnalysis);
        document.getElementById('expandAll').addEventListener('click', expandAll);
        document.getElementById('collapseAll').addEventListener('click', collapseAll);
    });

    // ── Load taxonomy tree from API ───────────────────────────────────────────
    function loadTaxonomy() {
        fetch('/api/taxonomy')
            .then(r => r.json())
            .then(data => {
                taxonomyData = data;
                renderTree(data, null);
            })
            .catch(err => {
                document.getElementById('taxonomyTree').innerHTML =
                    '<div class="alert alert-danger">Failed to load taxonomy: ' + err + '</div>';
            });
    }

    // ── Render tree ───────────────────────────────────────────────────────────
    function renderTree(nodes, scores) {
        const container = document.getElementById('taxonomyTree');
        container.innerHTML = '';
        nodes.forEach(node => container.appendChild(buildNodeEl(node, scores)));
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
                renderTree(result.tree, result.scores);
                expandMatched(result.scores);
                const matchedCount = Object.values(result.scores).filter(v => v > 0).length;
                showStatus('success',
                    'Analysis complete. ' + matchedCount + ' node(s) matched.');
            })
            .catch(err => {
                setAnalyzing(false);
                showStatus('danger', 'Analysis failed: ' + err.message);
            });
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
        btn.textContent = on ? ' Analyzing…' : 'Analyze with Gemini';
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
