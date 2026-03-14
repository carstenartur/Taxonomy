/* ── taxonomy-help.js ──────────────────────────────────────────────────────
   In-app Help tab: loads TOC from /help, renders docs from /help/{docName}.
   Security: server-rendered HTML is inserted with innerHTML only after being
   sanitised through DOMParser — the browser parses it in a detached document
   so no scripts execute, then we import only safe element types.
   F1 keyboard shortcut opens the Help tab.
   Deep-linking: #help/DOC_NAME auto-loads that document.
   ───────────────────────────────────────────────────────────────────────── */
(function () {
    'use strict';

    /* ── DOM refs ──────────────────────────────────────────────────────── */
    var tocList     = document.getElementById('helpTocList');
    var searchInput = document.getElementById('helpSearchInput');
    var docBody     = document.getElementById('helpDocBody');
    var docTitle    = document.getElementById('helpDocTitle');
    var backToTop   = document.getElementById('helpBackToTop');

    if (!tocList) { return; } // tab not present

    var toc = [];
    var currentDoc = null;

    /* ── Safe HTML insertion ───────────────────────────────────────────── */
    /**
     * Inserts server-provided HTML into a container element safely.
     * Uses DOMParser to parse the HTML in a sandboxed document, then
     * walks the resulting DOM and copies only safe element/attribute
     * types to the target — preventing XSS from injected <script> tags,
     * event handlers, or dangerous href/src values.
     */
    function safeSetHtml(container, htmlString) {
        container.innerHTML = '';
        var doc = new DOMParser().parseFromString(htmlString, 'text/html');
        var body = doc.body;
        importSafeNodes(body, container);
    }

    var ALLOWED_TAGS = new Set([
        'DIV','SPAN','P','BR','HR','STRONG','EM','B','I','U','S','CODE','PRE',
        'H1','H2','H3','H4','H5','H6',
        'UL','OL','LI','DL','DT','DD',
        'TABLE','THEAD','TBODY','TFOOT','TR','TH','TD','CAPTION','COLGROUP','COL',
        'BLOCKQUOTE','FIGURE','FIGCAPTION',
        'A','IMG','PICTURE','SOURCE',
        'DETAILS','SUMMARY',
        'MARK','ABBR','CITE','Q','SUP','SUB','KBD','SAMP','VAR','TIME'
    ]);

    var ALLOWED_ATTRS = new Set([
        'class','id','style','title','alt','href','src','width','height',
        'target','rel','type','start','value','colspan','rowspan',
        'open','datetime','lang','dir','aria-label','aria-hidden',
        'data-bs-toggle','data-bs-target'
    ]);

    function importSafeNodes(src, dest) {
        src.childNodes.forEach(function (node) {
            if (node.nodeType === Node.TEXT_NODE) {
                dest.appendChild(document.createTextNode(node.textContent));
            } else if (node.nodeType === Node.ELEMENT_NODE) {
                var tag = node.tagName;
                if (!ALLOWED_TAGS.has(tag)) { return; }
                var el = document.createElement(tag);
                // Copy allowed attributes
                for (var i = 0; i < node.attributes.length; i++) {
                    var attr = node.attributes[i];
                    if (!ALLOWED_ATTRS.has(attr.name.toLowerCase())) { continue; }
                    // Block javascript: URLs
                    if ((attr.name === 'href' || attr.name === 'src') &&
                        /^\s*javascript:/i.test(attr.value)) { continue; }
                    el.setAttribute(attr.name, attr.value);
                }
                // Open external links in a new tab
                if (tag === 'A' && el.getAttribute('href') && !el.getAttribute('href').startsWith('#')) {
                    el.setAttribute('target', '_blank');
                    el.setAttribute('rel', 'noopener noreferrer');
                }
                importSafeNodes(node, el);
                dest.appendChild(el);
            }
        });
    }

    /* ── TOC loading ───────────────────────────────────────────────────── */
    function loadToc() {
        fetch('/help')
            .then(function (r) { return r.json(); })
            .then(function (data) {
                toc = data;
                renderToc(data);
                // Deep-link or auto-load User Guide
                var hash = window.location.hash.replace('#', '');
                if (hash.startsWith('help/')) {
                    var docName = hash.substring(5);
                    loadDoc(docName);
                } else if (currentDoc === null) {
                    loadDoc('USER_GUIDE');
                }
            })
            .catch(function (e) {
                tocList.innerHTML = '<li class="text-danger small p-2">Failed to load help index.</li>';
                console.error('Help TOC load error:', e);
            });
    }

    function renderToc(items) {
        tocList.innerHTML = '';
        items.forEach(function (entry) {
            var li = document.createElement('li');
            li.className = 'help-toc-item';
            li.dataset.filename = entry.filename;
            li.dataset.audience = entry.audience;
            var a = document.createElement('a');
            a.href = '#help/' + entry.filename;
            a.className = 'help-toc-link';
            a.textContent = entry.icon + ' ' + entry.title;
            var badge = document.createElement('span');
            badge.className = 'help-audience-badge';
            badge.textContent = entry.audience;
            li.appendChild(a);
            li.appendChild(badge);
            li.addEventListener('click', function (e) {
                e.preventDefault();
                loadDoc(entry.filename);
                history.replaceState(null, '', '#help/' + entry.filename);
            });
            tocList.appendChild(li);
        });
        if (currentDoc) { highlightActive(currentDoc); }
    }

    /* ── Document loading ──────────────────────────────────────────────── */
    function loadDoc(docName) {
        // Validate format before sending to server
        if (!/^[A-Za-z0-9_-]+$/.test(docName)) { return; }
        currentDoc = docName;
        highlightActive(docName);
        docTitle.classList.add('d-none');
        safeSetHtml(docBody, '<div class="text-center text-muted py-4">' +
            '<div class="spinner-border spinner-border-sm" role="status"></div> Loading&hellip;</div>');
        backToTop.classList.add('d-none');

        fetch('/help/' + encodeURIComponent(docName))
            .then(function (r) {
                if (!r.ok) { throw new Error('HTTP ' + r.status); }
                return r.text();
            })
            .then(function (html) {
                var entry = toc.find(function (d) { return d.filename === docName; });
                if (entry) {
                    docTitle.textContent = entry.icon + ' ' + entry.title;
                    docTitle.classList.remove('d-none');
                }
                safeSetHtml(docBody, html);
                backToTop.classList.remove('d-none');
                // Scroll content area back to top
                var area = docBody.closest('.help-content-area');
                if (area) { area.scrollTop = 0; }
            })
            .catch(function (e) {
                safeSetHtml(docBody, '<div class="alert alert-danger">Could not load document: ' +
                    escapeHtml(String(e.message)) + '</div>');
                console.error('Help doc load error:', e);
            });
    }

    function highlightActive(docName) {
        document.querySelectorAll('#helpTocList .help-toc-item').forEach(function (li) {
            li.classList.toggle('active', li.dataset.filename === docName);
        });
    }

    function escapeHtml(str) {
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    /* ── Search / filter ───────────────────────────────────────────────── */
    if (searchInput) {
        searchInput.addEventListener('input', function () {
            var q = this.value.trim().toLowerCase();
            document.querySelectorAll('#helpTocList .help-toc-item').forEach(function (li) {
                var title = (li.querySelector('.help-toc-link') || {}).textContent || '';
                var audience = li.dataset.audience || '';
                var match = !q || title.toLowerCase().includes(q) || audience.toLowerCase().includes(q);
                li.style.display = match ? '' : 'none';
            });
        });
    }

    /* ── Back to top ───────────────────────────────────────────────────── */
    if (backToTop) {
        backToTop.addEventListener('click', function () {
            var area = docBody.closest('.help-content-area');
            if (area) { area.scrollTop = 0; }
        });
    }

    /* ── F1 keyboard shortcut ──────────────────────────────────────────── */
    document.addEventListener('keydown', function (e) {
        if (e.key === 'F1' && !e.altKey && !e.ctrlKey && !e.metaKey) {
            e.preventDefault();
            if (typeof window.navigateToPage === 'function') {
                window.navigateToPage('help');
            }
        }
    });

    /* ── Deep-link via hashchange ──────────────────────────────────────── */
    window.addEventListener('hashchange', function () {
        var hash = window.location.hash.replace('#', '');
        if (hash.startsWith('help/')) {
            var docName = hash.substring(5);
            if (/^[A-Za-z0-9_-]+$/.test(docName)) {
                if (typeof window.navigateToPage === 'function') {
                    window.navigateToPage('help');
                }
                loadDoc(docName);
            }
        }
    });

    /* ── Activate on tab click ─────────────────────────────────────────── */
    document.querySelectorAll('#mainNavTabs .nav-link[data-page="help"]').forEach(function (link) {
        link.addEventListener('click', function () {
            if (toc.length === 0) { loadToc(); }
        });
    });

    /* ── Init ──────────────────────────────────────────────────────────── */
    // Load TOC when the Help tab is first activated
    var helpTab = document.querySelector('#mainNavTabs .nav-link[data-page="help"]');
    if (helpTab) {
        // Check if help tab is already active (e.g. from deep-link)
        if (helpTab.classList.contains('active')) {
            loadToc();
        } else {
            helpTab.addEventListener('click', function onFirstClick() {
                loadToc();
                helpTab.removeEventListener('click', onFirstClick);
            }, true);
        }
    }

    // Handle deep-link on page load
    var initialHash = window.location.hash.replace('#', '');
    if (initialHash.startsWith('help/')) {
        loadToc();
    }
}());
