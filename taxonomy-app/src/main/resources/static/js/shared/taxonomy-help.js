/* ── taxonomy-help.js ──────────────────────────────────────────────────────
   In-app Help tab: loads TOC from /help, renders docs from /help/{docName}.
   Security: server-rendered HTML is inserted with innerHTML only after being
   sanitised through DOMParser — the browser parses it in a detached document
   so no scripts execute, then we import only safe element types.
   F1 keyboard shortcut opens the Help tab.
   Deep-linking: #help/DOC_NAME[/section] opens the document and optional section.
   ───────────────────────────────────────────────────────────────────────── */
(function () {
    'use strict';

    var t = TaxonomyI18n.t;

    /* ── DOM refs ──────────────────────────────────────────────────────── */
    var tocList     = document.getElementById('helpTocList');
    var searchInput = document.getElementById('helpSearchInput');
    var docBody     = document.getElementById('helpDocBody');
    var docTitle    = document.getElementById('helpDocTitle');
    var backToTop   = document.getElementById('helpBackToTop');

    if (!tocList) { return; } // tab not present

    var toc = [];
    var currentDoc = null;
    var loadedDoc = null;
    var requestSequence = 0;
    var loadingKey = null;
    var tocRequest = null;
    var requestedTarget = parseTarget(window.location.hash) || {doc: 'USER_GUIDE', section: ''};

    function updateBrowserHelp(view) {
        if (['list', 'tabs', 'sunburst', 'tree', 'decision', 'summary'].indexOf(view) === -1) return;
        var link = document.querySelector('#taxonomyBrowserHelp .context-help');
        if (link) link.setAttribute('href', link.getAttribute('href').split('#')[0]
            + '#help/USER_GUIDE/taxonomy-view-' + view);
    }
    document.addEventListener('taxonomy:view-rendered', function (event) {
        updateBrowserHelp(event.detail?.view);
    });
    document.addEventListener('click', function (event) {
        var button = event.target.closest('#diagramViewSelector [data-view]');
        if (button) updateBrowserHelp(button.getAttribute('data-view'));
    });

    function parseTarget(hash) {
        var match = /^#help\/([A-Za-z0-9_-]+)(?:\/([^/]+))?$/.exec(hash);
        if (!match) return null;
        try {
            var section = decodeURIComponent(match[2] || '');
            if (/[\s/#<>]/.test(section)) return null;
            return {doc: match[1], section: section};
        } catch (_) { return null; }
    }

    function targetHash(target) {
        return '#help/' + target.doc + (target.section ? '/' + encodeURIComponent(target.section) : '');
    }

    function helpUrl(path) {
        return TaxonomyI18n.resolveUrl(path) + '?lang=' + encodeURIComponent(TaxonomyI18n.getLocale());
    }

    function scrollToSection(section) {
        var area = docBody.closest('.help-content-area');
        if (area) area.scrollTop = 0;
        if (!section) return;
        var target = Array.from(docBody.querySelectorAll('[id]')).find(function (node) {
            return node.getAttribute('id') === section;
        });
        if (!target) return;
        // Stable explicit anchors precede translated headings.
        if (target.tagName === 'A') {
            var block = target.parentElement?.tagName === 'P' && !target.parentElement.textContent.trim()
                ? target.parentElement : target;
            if (block.nextElementSibling && /^H[1-6]$/.test(block.nextElementSibling.tagName)) {
                target = block.nextElementSibling;
            }
        }
        target.setAttribute('tabindex', '-1');
        target.focus({preventScroll: true});
        target.scrollIntoView({block: 'start'});
    }

    function selectTarget(target) {
        requestedTarget = target;
        history.replaceState(null, '', targetHash(target));
        loadToc();
        loadDoc(target);
    }

    function openTarget(target) {
        requestedTarget = target;
        if (typeof window.navigateToPage === 'function') window.navigateToPage('help');
        selectTarget(target);
    }

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
                    var value = attr.value;
                    if (attr.name === 'src' && value.startsWith('/help/images/')) {
                        value = TaxonomyI18n.resolveUrl(value);
                    }
                    el.setAttribute(attr.name, value);
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
        if (tocRequest) return tocRequest;
        tocRequest = fetch(helpUrl('/help'))
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (data) {
                toc = data;
                renderToc(data);
            })
            .catch(function (e) {
                tocRequest = null; // A subsequent help visit can retry.
                tocList.textContent = t('help.load.failed');
                console.error('Help TOC load error:', e);
            });
        return tocRequest;
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
                openTarget({doc: entry.filename, section: ''});
            });
            tocList.appendChild(li);
        });
        if (currentDoc) { highlightActive(currentDoc); }
        if (loadedDoc) { updateDocTitle(loadedDoc); }
    }

    function updateDocTitle(docName) {
        var entry = toc.find(function (d) { return d.filename === docName; });
        if (entry) {
            docTitle.textContent = entry.icon + ' ' + entry.title;
            docTitle.classList.remove('d-none');
        }
    }

    /* ── Document loading ──────────────────────────────────────────────── */
    function loadDoc(target) {
        var docName = target.doc;
        var key = targetHash(target);
        if (loadingKey === key) return;
        if (loadedDoc === docName && !loadingKey) {
            scrollToSection(target.section);
            return;
        }
        var sequence = ++requestSequence;
        loadingKey = key;
        loadedDoc = null;
        currentDoc = docName;
        highlightActive(docName);
        docTitle.classList.add('d-none');
        safeSetHtml(docBody, '<div class="text-center text-muted py-4">' +
            '<div class="spinner-border spinner-border-sm" role="status"></div> ' + TaxonomyUtils.escapeHtml(t('help.loading')) + '</div>');
        backToTop.classList.add('d-none');

        fetch(helpUrl('/help/' + encodeURIComponent(docName)))
            .then(function (r) {
                if (!r.ok) { throw new Error('HTTP ' + r.status); }
                return r.text();
            })
            .then(function (html) {
                if (sequence !== requestSequence) return;
                loadingKey = null;
                loadedDoc = docName;
                updateDocTitle(docName);
                safeSetHtml(docBody, html);
                backToTop.classList.remove('d-none');
                scrollToSection(target.section);
            })
            .catch(function (e) {
                if (sequence !== requestSequence) return;
                loadingKey = null;
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

    var escapeHtml = TaxonomyUtils.escapeHtml;

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

    /* Keep section links inside the help reader, including its table of contents. */
    docBody.addEventListener('click', function (event) {
        var link = event.target.closest('a[href]');
        if (!link || !docBody.contains(link) || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        var href = link.getAttribute('href');
        if (!href || !href.startsWith('#') || href.startsWith('#help/')) return;
        try {
            var section = decodeURIComponent(href.slice(1));
            if (!section) return;
            event.preventDefault();
            selectTarget({doc: currentDoc, section: section});
        } catch (_) { /* Leave malformed authored links to normal browser handling. */ }
    });

    window.addEventListener('hashchange', function () {
        var target = parseTarget(window.location.hash);
        if (target) openTarget(target);
    });

    // The navigation controller emits this after showing the pane. Its generic
    // #help hash must not erase a document/section captured before it initialized.
    document.addEventListener('taxonomy:page-activated', function (event) {
        if (event.detail?.page === 'help') selectTarget(parseTarget(window.location.hash) || requestedTarget);
    });
    var helpTab = document.querySelector('[data-page="help"]');
    if (helpTab && helpTab.classList.contains('active')) selectTarget(requestedTarget);
}());
