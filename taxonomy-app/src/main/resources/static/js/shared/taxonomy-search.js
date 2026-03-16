/**
 * taxonomy-search.js — Search Panel module
 *
 * Exposes full-text, semantic, hybrid, and graph-semantic search
 * via the right-panel Search card. Also provides a "Find Similar"
 * action that can be triggered from any taxonomy node.
 */
(function () {
    'use strict';

    /* ------------------------------------------------------------------ */
    /*  State                                                              */
    /* ------------------------------------------------------------------ */
    let embeddingAvailable = false;

    /* ------------------------------------------------------------------ */
    /*  Bootstrap on DOMContentLoaded                                      */
    /* ------------------------------------------------------------------ */
    document.addEventListener('DOMContentLoaded', function () {
        const searchInput   = document.getElementById('searchInput');
        const searchBtn     = document.getElementById('searchBtn');
        const searchMode    = document.getElementById('searchModeSelect');
        const searchMax     = document.getElementById('searchMaxResults');
        const resultsArea   = document.getElementById('searchResultsArea');

        if (!searchBtn) return;               // panel not in DOM

        searchBtn.addEventListener('click', function () {
            performSearch(searchInput.value.trim(), searchMode.value, parseInt(searchMax.value, 10));
        });
        searchInput.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                searchBtn.click();
            }
        });

        /* Disable semantic modes until embedding status is known */
        checkEmbeddingStatus();
    });

    /* ------------------------------------------------------------------ */
    /*  Embedding status                                                   */
    /* ------------------------------------------------------------------ */
    function checkEmbeddingStatus() {
        fetch('/api/embedding/status')
            .then(function (r) { return r.json(); })
            .then(function (data) {
                embeddingAvailable = data.available === true;
                updateEmbeddingBadge(data);
                updateSearchModes();
            })
            .catch(function () {
                embeddingAvailable = false;
                updateSearchModes();
            });
    }

    function updateEmbeddingBadge(data) {
        var badge = document.getElementById('embeddingStatusBadge');
        if (!badge) return;
        badge.classList.remove('d-none');
        if (data.available) {
            badge.classList.remove('bg-secondary');
            badge.classList.add('bg-info', 'text-dark');
            badge.textContent = '\uD83E\uDDE0 Embeddings: ' + data.indexedNodes + ' nodes';
            badge.title = 'Semantic embeddings available — model: ' + (data.modelUrl || 'unknown');
        } else {
            badge.classList.remove('bg-info', 'text-dark');
            badge.classList.add('bg-secondary');
            badge.textContent = '\uD83E\uDDE0 Embeddings: unavailable';
            badge.title = 'Embeddings not loaded — semantic/hybrid/graph search disabled';
        }
    }

    function updateSearchModes() {
        var sel = document.getElementById('searchModeSelect');
        if (!sel) return;
        Array.from(sel.options).forEach(function (opt) {
            if (opt.value === 'semantic' || opt.value === 'hybrid' || opt.value === 'graph') {
                opt.disabled = !embeddingAvailable;
                if (!embeddingAvailable && opt.selected) {
                    sel.value = 'fulltext';
                }
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Search execution                                                   */
    /* ------------------------------------------------------------------ */
    function performSearch(query, mode, maxResults) {
        if (!query) return;

        var area = document.getElementById('searchResultsArea');
        area.style.display = 'block';
        area.innerHTML = '<div class="text-center text-muted py-2"><div class="spinner-border spinner-border-sm" role="status"></div> Searching\u2026</div>';

        var url;
        switch (mode) {
            case 'semantic':
                url = '/api/search/semantic?q=' + encodeURIComponent(query) + '&maxResults=' + maxResults;
                break;
            case 'hybrid':
                url = '/api/search/hybrid?q=' + encodeURIComponent(query) + '&maxResults=' + maxResults;
                break;
            case 'graph':
                url = '/api/search/graph?q=' + encodeURIComponent(query) + '&maxResults=' + maxResults;
                break;
            default:
                url = '/api/search?q=' + encodeURIComponent(query) + '&maxResults=' + maxResults;
        }

        fetch(url)
            .then(function (r) {
                if (!r.ok) throw new Error('Search failed (' + r.status + ')');
                return r.json();
            })
            .then(function (data) {
                if (mode === 'graph') {
                    renderGraphSearchResults(data);
                } else {
                    renderSearchResults(data);
                }
            })
            .catch(function (err) {
                area.innerHTML = '<div class="text-danger small p-2">\u26A0\uFE0F ' + escapeHtml(err.message) + '</div>';
            });
    }

    /* ------------------------------------------------------------------ */
    /*  Render helpers                                                     */
    /* ------------------------------------------------------------------ */
    function renderSearchResults(nodes) {
        var area = document.getElementById('searchResultsArea');
        if (!nodes || nodes.length === 0) {
            area.innerHTML = '<div class="text-muted small p-2">No results found.</div>';
            return;
        }
        var html = '<div class="small text-muted mb-1">' + nodes.length + ' result(s)</div>';
        html += '<div class="list-group list-group-flush search-results-list">';
        nodes.forEach(function (n) {
            var pct = (typeof n.matchPercentage === 'number') ? n.matchPercentage : '';
            var pctBadge = pct !== '' ? '<span class="badge bg-success ms-auto">' + pct + '%</span>' : '';
            html += '<a href="#" class="list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item" data-code="' + escapeHtml(n.code) + '">';
            html += '<span class="search-result-code fw-semibold me-1">' + escapeHtml(n.code) + '</span> ';
            html += '<span class="search-result-name text-truncate">' + escapeHtml(n.nameEn || '') + '</span>';
            html += pctBadge;
            html += '</a>';
        });
        html += '</div>';
        area.innerHTML = html;

        /* Click to highlight in taxonomy tree */
        area.querySelectorAll('.search-result-item').forEach(function (el) {
            el.addEventListener('click', function (e) {
                e.preventDefault();
                highlightNodeInTree(el.dataset.code);
            });
        });
    }

    function renderGraphSearchResults(data) {
        var area = document.getElementById('searchResultsArea');
        var html = '';
        if (data.summary) {
            html += '<div class="small fst-italic mb-2">' + escapeHtml(data.summary) + '</div>';
        }
        if (data.matchedNodes && data.matchedNodes.length > 0) {
            html += '<div class="small text-muted mb-1">' + data.matchedNodes.length + ' matched node(s)</div>';
            html += '<div class="list-group list-group-flush search-results-list">';
            data.matchedNodes.forEach(function (n) {
                html += '<a href="#" class="list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item" data-code="' + escapeHtml(n.code) + '">';
                html += '<span class="search-result-code fw-semibold me-1">' + escapeHtml(n.code) + '</span> ';
                html += '<span class="search-result-name text-truncate">' + escapeHtml(n.nameEn || '') + '</span>';
                html += '</a>';
            });
            html += '</div>';
        }
        if (data.topRelationTypes && Object.keys(data.topRelationTypes).length > 0) {
            html += '<div class="small text-muted mt-2 mb-1">Top relation types:</div>';
            html += '<div class="d-flex gap-1 flex-wrap">';
            Object.entries(data.topRelationTypes).forEach(function (entry) {
                html += '<span class="badge bg-secondary">' + escapeHtml(entry[0]) + ' (' + entry[1] + ')</span>';
            });
            html += '</div>';
        }
        area.innerHTML = html || '<div class="text-muted small p-2">No graph results found.</div>';

        area.querySelectorAll('.search-result-item').forEach(function (el) {
            el.addEventListener('click', function (e) {
                e.preventDefault();
                highlightNodeInTree(el.dataset.code);
            });
        });
    }

    function highlightNodeInTree(code) {
        /* Remove previous highlights */
        document.querySelectorAll('.search-highlight').forEach(function (el) {
            el.classList.remove('search-highlight');
        });
        /* Find the node header by code */
        var codeEl = document.querySelector('.tax-code[data-code="' + code + '"]');
        if (!codeEl) {
            /* Try finding by text content */
            document.querySelectorAll('.tax-code').forEach(function (el) {
                if (el.textContent.trim() === code) codeEl = el;
            });
        }
        if (codeEl) {
            var header = codeEl.closest('.tax-node-header');
            if (header) {
                header.classList.add('search-highlight');
                header.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Find Similar (triggered from taxonomy node buttons)                */
    /* ------------------------------------------------------------------ */
    function findSimilar(code) {
        var area = document.getElementById('searchResultsArea');
        var panel = document.getElementById('searchPanel');
        if (panel) {
            panel.open = true;
            panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
        if (area) {
            area.style.display = 'block';
            area.innerHTML = '<div class="text-center text-muted py-2"><div class="spinner-border spinner-border-sm" role="status"></div> Finding similar nodes\u2026</div>';
        }

        fetch('/api/search/similar/' + encodeURIComponent(code) + '?topK=10')
            .then(function (r) {
                if (!r.ok) throw new Error('Failed (' + r.status + ')');
                return r.json();
            })
            .then(function (nodes) { renderSearchResults(nodes); })
            .catch(function (err) {
                if (area) area.innerHTML = '<div class="text-danger small p-2">\u26A0\uFE0F ' + escapeHtml(err.message) + '</div>';
            });
    }

    /* ------------------------------------------------------------------ */
    /*  Utility                                                            */
    /* ------------------------------------------------------------------ */
    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    /* ------------------------------------------------------------------ */
    /*  Public API                                                         */
    /* ------------------------------------------------------------------ */
    window.TaxonomySearch = {
        findSimilar: findSimilar,
        performSearch: performSearch,
        checkEmbeddingStatus: checkEmbeddingStatus,
        isEmbeddingAvailable: function () { return embeddingAvailable; }
    };
})();
