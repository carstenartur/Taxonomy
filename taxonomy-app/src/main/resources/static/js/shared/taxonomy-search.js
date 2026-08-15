/**
 * taxonomy-search.js — Search Panel module
 *
 * Exposes full-text, semantic, hybrid, and graph-semantic search
 * via the right-panel Search card. Also provides a "Find Similar"
 * action that can be triggered from any taxonomy node.
 */
(function () {
    'use strict';
    var t = TaxonomyI18n.t;

    const RESULT_WINDOW_SIZE = 50;
    let embeddingAvailable = false;
    let resultState = emptyResultState();

    const orientationMessages = {
        en: {
            summary: function (total, from, to) {
                return total + ' results. Showing ' + from + '–' + to + '.';
            },
            active: function (query, mode) {
                return 'Active search: “' + query + '” · Mode: ' + mode;
            },
            pathNone: 'Current taxonomy path: no result selected.',
            path: function (path) { return 'Current taxonomy path: ' + path; },
            previous: 'Previous relevant result',
            next: 'Next relevant result',
            back: 'Return to result summary',
            position: function (position, total) {
                return 'Result ' + position + ' of ' + total + '.';
            },
            similar: 'similar nodes'
        },
        de: {
            summary: function (total, from, to) {
                return total + ' Ergebnisse. Angezeigt werden ' + from + '–' + to + '.';
            },
            active: function (query, mode) {
                return 'Aktive Suche: „' + query + '“ · Modus: ' + mode;
            },
            pathNone: 'Aktueller Taxonomiepfad: kein Ergebnis ausgewählt.',
            path: function (path) { return 'Aktueller Taxonomiepfad: ' + path; },
            previous: 'Vorheriges relevantes Ergebnis',
            next: 'Nächstes relevantes Ergebnis',
            back: 'Zur Ergebniszusammenfassung zurückkehren',
            position: function (position, total) {
                return 'Ergebnis ' + position + ' von ' + total + '.';
            },
            similar: 'ähnliche Knoten'
        }
    };

    document.addEventListener('DOMContentLoaded', function () {
        const searchInput = document.getElementById('searchInput');
        const searchBtn = document.getElementById('searchBtn');
        const searchMode = document.getElementById('searchModeSelect');
        const searchMax = document.getElementById('searchMaxResults');
        const resultArea = document.getElementById('searchResultsArea');

        if (!searchBtn) return;

        searchBtn.addEventListener('click', function () {
            performSearch(
                searchInput.value.trim(),
                searchMode.value,
                parseInt(searchMax.value, 10));
        });
        searchInput.addEventListener('keydown', function (event) {
            if (event.key === 'Enter') {
                event.preventDefault();
                searchBtn.click();
            }
        });
        if (resultArea) {
            resultArea.addEventListener('click', onResultAreaClick);
        }

        checkEmbeddingStatus();
    });

    function emptyResultState() {
        return {
            nodes: [],
            query: '',
            mode: '',
            currentIndex: -1,
            windowStart: 0,
            prefixHtml: '',
            suffixHtml: '',
            currentPath: null,
            originWindowY: null
        };
    }

    function orientationText(key) {
        var language = String(document.documentElement.lang || 'en').toLowerCase();
        var messages = language.startsWith('de')
            ? orientationMessages.de
            : orientationMessages.en;
        var value = messages[key];
        var args = Array.prototype.slice.call(arguments, 1);
        return typeof value === 'function' ? value.apply(null, args) : value;
    }

    function checkEmbeddingStatus() {
        fetch('/api/embedding/status')
            .then(function (response) { return response.json(); })
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
            badge.textContent = t('search.embeddings.available', data.indexedNodes);
            badge.title = t(
                'search.embeddings.available.title',
                data.modelUrl || 'unknown');
        } else {
            badge.classList.remove('bg-info', 'text-dark');
            badge.classList.add('bg-secondary');
            badge.textContent = t('search.embeddings.unavailable');
            badge.title = t('search.embeddings.unavailable.title');
        }
    }

    function updateSearchModes() {
        var select = document.getElementById('searchModeSelect');
        if (!select) return;
        Array.from(select.options).forEach(function (option) {
            if (option.value === 'semantic'
                    || option.value === 'hybrid'
                    || option.value === 'graph') {
                option.disabled = !embeddingAvailable;
                if (!embeddingAvailable && option.selected) {
                    select.value = 'fulltext';
                }
            }
        });
    }

    function performSearch(query, mode, maxResults) {
        if (!query) return;

        var area = document.getElementById('searchResultsArea');
        resetResultState();
        area.style.display = 'block';
        area.innerHTML = '<div class="text-center text-muted py-2">'
            + '<div class="spinner-border spinner-border-sm" role="status"></div> '
            + t('search.searching') + '</div>';

        var url;
        switch (mode) {
            case 'semantic':
                url = '/api/search/semantic?q=' + encodeURIComponent(query)
                    + '&maxResults=' + maxResults;
                break;
            case 'hybrid':
                url = '/api/search/hybrid?q=' + encodeURIComponent(query)
                    + '&maxResults=' + maxResults;
                break;
            case 'graph':
                url = '/api/search/graph?q=' + encodeURIComponent(query)
                    + '&maxResults=' + maxResults;
                break;
            default:
                url = '/api/search?q=' + encodeURIComponent(query)
                    + '&maxResults=' + maxResults;
        }

        fetch(url)
            .then(function (response) {
                if (!response.ok) {
                    throw new Error('Search failed (' + response.status + ')');
                }
                return response.json();
            })
            .then(function (data) {
                var context = { query: query, mode: mode, maxResults: maxResults };
                if (mode === 'graph') {
                    renderGraphSearchResults(data, context);
                } else {
                    renderSearchResults(data, context);
                }
            })
            .catch(function (error) {
                resetResultState();
                area.innerHTML = '<div class="text-danger small p-2">⚠️ '
                    + escapeHtml(error.message) + '</div>';
            });
    }

    function renderSearchResults(nodes, context) {
        var area = document.getElementById('searchResultsArea');
        if (!nodes || nodes.length === 0) {
            resetResultState();
            area.innerHTML = '<div class="text-muted small p-2">'
                + t('search.no.results') + '</div>';
            return;
        }
        renderResultWorkspace(nodes, context || {}, '', '');
    }

    function renderGraphSearchResults(data, context) {
        var area = document.getElementById('searchResultsArea');
        var prefix = '';
        var suffix = '';
        if (data.summary) {
            prefix += '<div class="small fst-italic mb-2">'
                + escapeHtml(data.summary) + '</div>';
        }
        if (data.topRelationTypes
                && Object.keys(data.topRelationTypes).length > 0) {
            suffix += '<div class="small text-muted mt-2 mb-1">'
                + t('search.top.relation.types') + '</div>';
            suffix += '<div class="d-flex gap-1 flex-wrap">';
            Object.entries(data.topRelationTypes).forEach(function (entry) {
                suffix += '<span class="badge bg-secondary">'
                    + escapeHtml(entry[0]) + ' (' + entry[1] + ')</span>';
            });
            suffix += '</div>';
        }
        if (!data.matchedNodes || data.matchedNodes.length === 0) {
            resetResultState();
            area.innerHTML = prefix + suffix
                || '<div class="text-muted small p-2">'
                    + t('search.no.graph.results') + '</div>';
            return;
        }
        renderResultWorkspace(data.matchedNodes, context || {}, prefix, suffix);
    }

    function renderResultWorkspace(nodes, context, prefixHtml, suffixHtml) {
        resultState = {
            nodes: nodes.slice(),
            query: String(context.query || ''),
            mode: String(context.mode || 'fulltext'),
            currentIndex: -1,
            windowStart: 0,
            prefixHtml: prefixHtml || '',
            suffixHtml: suffixHtml || '',
            currentPath: null,
            originWindowY: null
        };
        renderResultWindow();
    }

    function renderResultWindow() {
        var area = document.getElementById('searchResultsArea');
        var total = resultState.nodes.length;
        if (!area || total === 0) return;

        var maximumStart = Math.max(0,
            Math.floor((total - 1) / RESULT_WINDOW_SIZE) * RESULT_WINDOW_SIZE);
        resultState.windowStart = Math.min(
            Math.max(0, resultState.windowStart), maximumStart);
        var end = Math.min(total, resultState.windowStart + RESULT_WINDOW_SIZE);
        var filters = orientationText(
            'active',
            resultState.query,
            resultState.mode === 'similar'
                ? orientationText('similar')
                : resultState.mode);
        var path = resultState.currentPath
            ? orientationText('path', resultState.currentPath)
            : orientationText('pathNone');

        var html = resultState.prefixHtml;
        html += '<div class="search-result-orientation border rounded bg-body-tertiary p-2 mb-2">';
        html += '<div id="searchResultSummary" class="fw-semibold small" role="status" '
            + 'tabindex="-1">'
            + escapeHtml(orientationText(
                'summary', total, resultState.windowStart + 1, end)) + '</div>';
        html += '<div id="searchActiveFilters" class="small text-body-secondary">'
            + escapeHtml(filters) + '</div>';
        html += '<div id="searchCurrentPath" class="small text-body-secondary" '
            + 'aria-live="polite">' + escapeHtml(path) + '</div>';
        html += '<div class="d-flex flex-wrap gap-1 mt-2" role="group" '
            + 'aria-label="Search result navigation">';
        html += navigationButton('previous', orientationText('previous'), '←');
        html += navigationButton('next', orientationText('next'), '→');
        html += navigationButton('summary', orientationText('back'), '↩');
        html += '</div></div>';
        html += '<div class="list-group list-group-flush search-results-list" '
            + 'role="list" aria-label="Search results">';

        for (var index = resultState.windowStart; index < end; index += 1) {
            var node = resultState.nodes[index];
            var percentage = typeof node.matchPercentage === 'number'
                ? node.matchPercentage
                : '';
            var percentageBadge = percentage !== ''
                ? '<span class="badge bg-success ms-auto">'
                    + percentage + '%</span>'
                : '';
            var current = index === resultState.currentIndex;
            html += '<a href="#" class="list-group-item list-group-item-action '
                + 'py-1 px-2 d-flex align-items-center search-result-item" data-code="'
                + escapeHtml(node.code) + '" data-result-index="' + index + '" '
                + 'role="listitem" aria-posinset="' + (index + 1) + '" aria-setsize="'
                + total + '"' + (current ? ' aria-current="true"' : '') + '>';
            html += '<span class="search-result-code fw-semibold me-1">'
                + escapeHtml(node.code) + '</span> ';
            html += '<span class="search-result-name text-truncate">'
                + escapeHtml(node.nameEn || '') + '</span>';
            html += percentageBadge;
            html += '</a>';
        }
        html += '</div>' + resultState.suffixHtml;
        area.innerHTML = html;
        area.dataset.totalResults = String(total);
        area.dataset.renderedResults = String(end - resultState.windowStart);
        area.dataset.windowSize = String(RESULT_WINDOW_SIZE);
        area.dataset.currentIndex = String(resultState.currentIndex);
        updateNavigationButtons();
    }

    function navigationButton(action, label, symbol) {
        return '<button type="button" class="btn btn-sm btn-outline-secondary" '
            + 'data-search-result-nav="' + action + '" aria-label="'
            + escapeHtml(label) + '" title="' + escapeHtml(label) + '">'
            + symbol + ' <span class="d-none d-sm-inline">'
            + escapeHtml(label) + '</span></button>';
    }

    function updateNavigationButtons() {
        var area = document.getElementById('searchResultsArea');
        if (!area) return;
        var total = resultState.nodes.length;
        var previous = area.querySelector('[data-search-result-nav="previous"]');
        var next = area.querySelector('[data-search-result-nav="next"]');
        var summary = area.querySelector('[data-search-result-nav="summary"]');
        if (previous) previous.disabled = total === 0;
        if (next) next.disabled = total === 0;
        if (summary) summary.disabled = resultState.currentIndex < 0;
    }

    function onResultAreaClick(event) {
        var navigation = event.target.closest('[data-search-result-nav]');
        if (navigation) {
            event.preventDefault();
            navigateByAction(navigation.dataset.searchResultNav);
            return;
        }
        var item = event.target.closest('.search-result-item[data-result-index]');
        if (!item) return;
        event.preventDefault();
        navigateToResult(parseInt(item.dataset.resultIndex, 10), false);
    }

    function navigateByAction(action) {
        if (action === 'summary') {
            returnToSummary();
            return;
        }
        var total = resultState.nodes.length;
        if (total === 0) return;
        var index;
        if (resultState.currentIndex < 0) {
            index = action === 'previous' ? total - 1 : 0;
        } else {
            index = action === 'previous'
                ? Math.max(0, resultState.currentIndex - 1)
                : Math.min(total - 1, resultState.currentIndex + 1);
        }
        navigateToResult(index, true);
    }

    function navigateToResult(index, restoreResultFocus) {
        if (!Number.isInteger(index)
                || index < 0
                || index >= resultState.nodes.length) return;
        var area = document.getElementById('searchResultsArea');
        if (resultState.originWindowY === null) {
            resultState.originWindowY = window.scrollY;
        }
        resultState.currentIndex = index;
        var requiredStart = Math.floor(index / RESULT_WINDOW_SIZE) * RESULT_WINDOW_SIZE;
        if (requiredStart !== resultState.windowStart) {
            resultState.windowStart = requiredStart;
            renderResultWindow();
        } else {
            area.querySelectorAll('.search-result-item[aria-current]')
                .forEach(function (element) { element.removeAttribute('aria-current'); });
            var current = area.querySelector(
                '.search-result-item[data-result-index="' + index + '"]');
            if (current) current.setAttribute('aria-current', 'true');
            area.dataset.currentIndex = String(index);
            updateNavigationButtons();
        }

        var item = area.querySelector(
            '.search-result-item[data-result-index="' + index + '"]');
        var node = resultState.nodes[index];
        announceResultPosition(index);
        highlightNodeInTree(node.code, function (treeNode) {
            updateCurrentPath(treeNode, node.code);
            if (restoreResultFocus) {
                var refreshed = area.querySelector(
                    '.search-result-item[data-result-index="' + index + '"]');
                if (refreshed) {
                    refreshed.focus({ preventScroll: true });
                    refreshed.scrollIntoView({ block: 'nearest' });
                }
            }
        });
        if (item && restoreResultFocus) item.focus({ preventScroll: true });
    }

    function announceResultPosition(index) {
        var message = orientationText(
            'position', index + 1, resultState.nodes.length);
        var status = document.getElementById('a11yStatus');
        if (status) {
            status.textContent = '';
            requestAnimationFrame(function () { status.textContent = message; });
        }
    }

    function updateCurrentPath(node, fallbackCode) {
        var segments = [];
        var current = node;
        while (current) {
            if (current.dataset && current.dataset.code) {
                segments.unshift(current.dataset.code);
            }
            var parent = current.parentElement;
            current = parent ? parent.closest('.tax-node') : null;
        }
        resultState.currentPath = segments.length > 0
            ? segments.join(' › ')
            : fallbackCode;
        var target = document.getElementById('searchCurrentPath');
        if (target) {
            target.textContent = orientationText('path', resultState.currentPath);
        }
    }

    function returnToSummary() {
        var area = document.getElementById('searchResultsArea');
        var summary = document.getElementById('searchResultSummary');
        if (!area || !summary) return;
        area.scrollTop = 0;
        if (resultState.originWindowY !== null) {
            window.scrollTo({ top: resultState.originWindowY, behavior: 'auto' });
        }
        summary.focus({ preventScroll: true });
        summary.scrollIntoView({ block: 'nearest' });
    }

    function highlightNodeInTree(code, completed) {
        document.querySelectorAll('.search-highlight').forEach(function (element) {
            element.classList.remove('search-highlight');
        });

        var currentView = window.TaxonomyState
            ? window.TaxonomyState.currentView
            : null;
        if (currentView !== 'list'
                && window.TaxonomyBrowse
                && window.TaxonomyBrowse.switchView) {
            window.TaxonomyBrowse.switchView('list');
            window.requestAnimationFrame(function () {
                completed(revealNodeInTree(code));
            });
            return;
        }
        completed(revealNodeInTree(code));
    }

    function revealNodeInTree(code) {
        if (window.TaxonomyBrowse && window.TaxonomyBrowse.ensureNodeRendered) {
            window.TaxonomyBrowse.ensureNodeRendered(
                code,
                window.TaxonomyState ? window.TaxonomyState.currentScores : null);
        }
        var node = Array.from(document.querySelectorAll('.tax-node'))
            .find(function (candidate) {
                return candidate.dataset && candidate.dataset.code === code;
            });
        if (!node) return null;

        var ancestor = node.parentElement;
        while (ancestor) {
            if (ancestor.classList
                    && ancestor.classList.contains('tax-children')) {
                ancestor.style.display = '';
                var parentNode = ancestor.parentElement;
                if (parentNode) {
                    parentNode.setAttribute('aria-expanded', 'true');
                    var toggle = parentNode.querySelector(
                        ':scope > .tax-node-header > .tax-toggle');
                    if (toggle) toggle.textContent = '▼';
                }
            }
            ancestor = ancestor.parentElement;
        }

        var header = node.querySelector(':scope > .tax-node-header');
        if (!header) return node;
        header.classList.add('search-highlight');
        node.setAttribute('tabindex', '0');
        node.focus({ preventScroll: true });
        header.scrollIntoView({ behavior: 'smooth', block: 'center' });
        return node;
    }

    function findSimilar(code) {
        var area = document.getElementById('searchResultsArea');
        var panel = document.getElementById('searchPanel');
        if (panel) {
            panel.open = true;
            panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
        if (area) {
            resetResultState();
            area.style.display = 'block';
            area.innerHTML = '<div class="text-center text-muted py-2">'
                + '<div class="spinner-border spinner-border-sm" role="status"></div> '
                + t('search.finding.similar') + '</div>';
        }

        fetch('/api/search/similar/' + encodeURIComponent(code) + '?topK=10')
            .then(function (response) {
                if (!response.ok) throw new Error('Failed (' + response.status + ')');
                return response.json();
            })
            .then(function (nodes) {
                renderSearchResults(nodes, {
                    query: code,
                    mode: 'similar',
                    maxResults: 10
                });
            })
            .catch(function (error) {
                resetResultState();
                if (area) {
                    area.innerHTML = '<div class="text-danger small p-2">⚠️ '
                        + escapeHtml(error.message) + '</div>';
                }
            });
    }

    function resetResultState() {
        resultState = emptyResultState();
        var area = document.getElementById('searchResultsArea');
        if (area) {
            delete area.dataset.totalResults;
            delete area.dataset.renderedResults;
            delete area.dataset.windowSize;
            delete area.dataset.currentIndex;
        }
    }

    var escapeHtml = TaxonomyUtils.escapeHtml;

    window.TaxonomySearch = {
        findSimilar: findSimilar,
        performSearch: performSearch,
        checkEmbeddingStatus: checkEmbeddingStatus,
        isEmbeddingAvailable: function () { return embeddingAvailable; },
        resultWindowSize: RESULT_WINDOW_SIZE,
        resultDiagnostics: function () {
            var area = document.getElementById('searchResultsArea');
            return {
                total: resultState.nodes.length,
                rendered: area
                    ? area.querySelectorAll('.search-result-item').length
                    : 0,
                currentIndex: resultState.currentIndex,
                windowStart: resultState.windowStart
            };
        }
    };
})();
