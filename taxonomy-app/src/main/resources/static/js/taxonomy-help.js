/**
 * taxonomy-help.js — In-app Help tab functionality.
 *
 * Handles:
 * - Loading the TOC from GET /help
 * - Rendering documents from GET /help/{docName}
 * - Client-side text filtering of the TOC
 * - F1 keyboard shortcut to open Help
 * - Deep-linking via hash: #help/DOC_NAME
 * - Auto-loading User Guide on first visit
 */
(function () {
    'use strict';

    var tocLoaded = false;
    var currentDoc = null;
    var allDocs = [];

    // ── Expose global API ─────────────────────────────────────────────────────

    window.loadHelpDoc = loadHelpDoc;

    // ── Initialise once the DOM is ready ─────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        // Wire up the search input
        var searchInput = document.getElementById('helpSearchInput');
        if (searchInput) {
            searchInput.addEventListener('input', function () {
                filterToc(this.value.trim().toLowerCase());
            });
        }

        // Wire up the Back-to-Top button
        var backBtn = document.getElementById('helpBackToTop');
        var contentEl = document.getElementById('helpDocContent');
        if (backBtn && contentEl) {
            contentEl.addEventListener('scroll', function () {
                backBtn.classList.toggle('d-none', contentEl.scrollTop < 200);
            });
            backBtn.addEventListener('click', function () {
                contentEl.scrollTop = 0;
            });
        }

        // F1 keyboard shortcut → open Help tab
        document.addEventListener('keydown', function (e) {
            if (e.key === 'F1') {
                e.preventDefault();
                if (window.navigateToPage) { window.navigateToPage('help'); }
            }
        });

        // Deep-link support: watch for hash changes like #help/USER_GUIDE
        window.addEventListener('hashchange', handleHash);
        handleHash();
    });

    // ── Called when the Help nav tab is activated ─────────────────────────────

    // Hook into the page navigation to load TOC on first activation
    var _origNavigate = null;
    document.addEventListener('DOMContentLoaded', function () {
        // Poll until navigateToPage is defined, then wrap it
        var attempts = 0;
        var interval = setInterval(function () {
            attempts++;
            if (window.navigateToPage) {
                clearInterval(interval);
                _origNavigate = window.navigateToPage;
                window.navigateToPage = function (page) {
                    if (_origNavigate) { _origNavigate(page); }
                    if (page === 'help') { onHelpActivated(); }
                };
            } else if (attempts > 50) {
                clearInterval(interval);
            }
        }, 100);
    });

    // ── Core functions ────────────────────────────────────────────────────────

    function onHelpActivated() {
        if (!tocLoaded) {
            loadToc();
        }
    }

    function loadToc() {
        fetch('/help')
            .then(function (r) { return r.json(); })
            .then(function (docs) {
                allDocs = docs;
                renderToc(docs);
                tocLoaded = true;
                // Auto-load User Guide on first visit
                if (!currentDoc) {
                    var firstDoc = docs.find(function (d) { return d.filename === 'USER_GUIDE'; })
                                   || docs[0];
                    if (firstDoc) { loadHelpDoc(firstDoc.filename); }
                }
            })
            .catch(function (err) {
                console.error('Help: failed to load TOC', err);
                var toc = document.getElementById('helpToc');
                if (toc) {
                    toc.innerHTML = '<div class="text-danger small p-2">Failed to load documentation list.</div>';
                }
            });
    }

    function renderToc(docs) {
        var toc = document.getElementById('helpToc');
        if (!toc) { return; }
        toc.innerHTML = '';
        docs.forEach(function (doc) {
            var btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'list-group-item list-group-item-action';
            btn.setAttribute('data-doc', doc.filename);
            btn.setAttribute('data-title', doc.title);
            btn.textContent = doc.icon + ' ' + doc.title;
            btn.title = doc.audience;
            btn.addEventListener('click', function () {
                loadHelpDoc(doc.filename);
            });
            toc.appendChild(btn);
        });
    }

    function loadHelpDoc(docName) {
        currentDoc = docName;

        // Highlight active item
        var toc = document.getElementById('helpToc');
        if (toc) {
            toc.querySelectorAll('.list-group-item').forEach(function (item) {
                item.classList.toggle('active', item.getAttribute('data-doc') === docName);
            });
        }

        // Show spinner
        var content = document.getElementById('helpDocContent');
        var title = document.getElementById('helpDocTitle');
        if (content) {
            content.innerHTML = '<div class="text-center py-5"><div class="spinner-border text-primary" role="status"></div><div class="mt-2 text-muted small">Loading…</div></div>';
        }

        // Update title
        var docMeta = allDocs.find(function (d) { return d.filename === docName; });
        if (title) {
            title.textContent = docMeta ? docMeta.icon + ' ' + docMeta.title : docName;
        }

        fetch('/help/' + encodeURIComponent(docName))
            .then(function (r) {
                if (!r.ok) { throw new Error('HTTP ' + r.status); }
                return r.text();
            })
            .then(function (html) {
                if (content) {
                    content.innerHTML = html;
                    content.scrollTop = 0;
                }
            })
            .catch(function (err) {
                console.error('Help: failed to load doc', docName, err);
                if (content) {
                    content.innerHTML = '<div class="alert alert-danger">Failed to load documentation: ' + docName + '</div>';
                }
            });
    }

    function filterToc(query) {
        var toc = document.getElementById('helpToc');
        if (!toc) { return; }
        toc.querySelectorAll('.list-group-item').forEach(function (item) {
            var text = (item.getAttribute('data-title') || item.textContent).toLowerCase();
            item.style.display = (!query || text.includes(query)) ? '' : 'none';
        });
    }

    function handleHash() {
        var hash = window.location.hash.replace('#', '');
        if (hash.startsWith('help/')) {
            var docName = hash.substring(5);
            if (window.navigateToPage) { window.navigateToPage('help'); }
            if (docName) {
                // Wait for TOC to load before loading the doc
                if (tocLoaded) {
                    loadHelpDoc(docName);
                } else {
                    var maxAttempts = 100;
                    var attempts = 0;
                    var interval = setInterval(function () {
                        attempts++;
                        if (tocLoaded) {
                            clearInterval(interval);
                            loadHelpDoc(docName);
                        } else if (attempts >= maxAttempts) {
                            clearInterval(interval);
                        }
                    }, 100);
                }
            }
        }
    }

}());
