/**
 * About Modal — fetches /api/about and displays version, build, commit info,
 * packaged legal resources, third-party notices and supply-chain evidence.
 */
window.TaxonomyAbout = (function () {
    'use strict';

    var noticesLoaded = false;
    var aboutInfo = null;
    var aboutRequest = 0;
    // Resolve from this served application asset, not the current page path.
    // This preserves context paths and avoids /admin/admin/sessions on nested pages.
    var sourceScript = document.currentScript;
    var sessionsUrl = sourceScript && sourceScript.src
        ? new URL('../../admin/sessions', sourceScript.src) : null;

    function el(id) { return document.getElementById(id); }

    var escapeHtml = TaxonomyUtils.escapeHtml;
    var t = TaxonomyI18n.t;

    function formatDate(val) {
        if (!val) return '—';
        try { return new Date(val).toLocaleString(); } catch (e) { return String(val); }
    }

    function localized(english, german) {
        var language = (document.documentElement.lang || navigator.language || '').toLowerCase();
        return language.indexOf('de') === 0 ? german : english;
    }

    function appendResourceLink(container, label, url) {
        if (!url) return;
        var link = document.createElement('a');
        link.className = 'btn btn-sm btn-outline-secondary';
        link.href = TaxonomyI18n.resolveUrl(url);
        link.target = '_blank';
        link.rel = 'noopener';
        link.textContent = label;
        container.appendChild(link);
    }

    function renderLegalResources(data) {
        var contentEl = el('aboutThirdPartyContent');
        if (!contentEl || !contentEl.parentNode) return;

        var container = el('aboutLegalResources');
        if (!container) {
            container = document.createElement('div');
            container.id = 'aboutLegalResources';
            container.className = 'mb-3 p-2 border rounded bg-light';
            contentEl.parentNode.insertBefore(container, contentEl);
        }
        container.textContent = '';

        var heading = document.createElement('div');
        heading.className = 'fw-semibold mb-1';
        heading.textContent = localized('Legal and supply-chain evidence', 'Lizenz- und Lieferkettennachweise');
        container.appendChild(heading);

        var explanation = document.createElement('div');
        explanation.className = 'small text-muted mb-2';
        explanation.textContent = localized(
            'The runtime report and SBOM describe this exact build; the curated notices cover non-Maven and optional components.',
            'Der Runtime-Bericht und die SBOM beschreiben exakt diesen Build; die kuratierten Hinweise decken Nicht-Maven- und optionale Komponenten ab.'
        );
        container.appendChild(explanation);

        var links = document.createElement('div');
        links.className = 'd-flex flex-wrap gap-2';
        appendResourceLink(links, 'LICENSE', data.projectLicenseUrl);
        appendResourceLink(links, 'NOTICE', data.noticeUrl);
        appendResourceLink(links,
            localized('Curated notices', 'Kuratierte Hinweise'), data.thirdPartyNoticesUrl);
        appendResourceLink(links,
            localized('Runtime licenses', 'Runtime-Lizenzen'), data.runtimeThirdPartyLicensesUrl);
        appendResourceLink(links, 'CycloneDX JSON', data.sbomJsonUrl);
        appendResourceLink(links, 'CycloneDX XML', data.sbomXmlUrl);
        container.appendChild(links);
    }

    function setAiStatus(status) {
        var message = el('aboutAiStatus');
        if (!message) return;
        message.textContent = status ? message.getAttribute('data-' + status) : '';
        message.hidden = !status;
    }

    function translatedCode(field, value) {
        if (typeof value !== 'string' || !value) return null;
        var key = 'about.ai.' + field + '.' + value;
        var text = t(key);
        return text === key ? t('about.ai.unknown') : text;
    }

    function addHeading(section, tag, key) {
        var heading = document.createElement(tag);
        heading.className = 'h6';
        heading.textContent = t('about.ai.' + key);
        section.appendChild(heading);
    }

    function addDetails(section, rows) {
        var list = document.createElement('dl');
        list.className = 'row gx-0 gy-1 small mb-2';
        rows.forEach(function (row) {
            if (typeof row[1] !== 'string' || !row[1]) return;
            var name = document.createElement('dt');
            name.className = 'col-sm-4';
            name.textContent = t('about.ai.' + row[0]);
            var value = document.createElement('dd');
            value.className = 'col-sm-8 text-break';
            value.textContent = row[1];
            list.append(name, value);
        });
        if (list.firstChild) section.appendChild(list);
    }

    function upstreamDetails(model) {
        return model ? [
            ['model', model.modelId],
            ['origin', model.origin],
            ['license', model.license],
            ['revision', model.revision]
        ] : [];
    }

    function renderEmbedding(container, embedding) {
        addHeading(container, 'h4', 'embedding');
        addDetails(container, [['status', translatedCode('embedding.state', embedding.state)]]);
        var configured = embedding.configuredProfile;
        if (configured) {
            addHeading(container, 'h5', 'configured.profile');
            addDetails(container, [
                ['profile', configured.id],
                ['configured.source', translatedCode('embedding.source', configured.source)]
            ].concat(upstreamDetails(configured.upstream)));
        }
        var loaded = embedding.loadedArtifact;
        if (loaded) {
            addHeading(container, 'h5', 'loaded.artifact');
            addDetails(container, [
                ['provenance', translatedCode('embedding.provenance', loaded.provenance)]
            ].concat(upstreamDetails(loaded.upstream), [
                ['model.sha256', loaded.modelSha256],
                ['tokenizer.sha256', loaded.tokenizerSha256],
                ['inference.version', loaded.inferenceVersion],
                ['inference.identity', loaded.inferenceIdentity]
            ]));
        }
    }

    function renderAi(ai, request) {
        var details = el('aboutAiDetails');
        if (!details) return;
        if (!ai || (!ai.embedding && !ai.analysis)) {
            setAiStatus('unavailable');
            return;
        }
        return TaxonomyI18n.ready().then(function () {
            if (request !== aboutRequest) return;
            setAiStatus(null);
            if (ai.embedding) renderEmbedding(details, ai.embedding);
            if (ai.analysis) {
                addHeading(details, 'h4', 'analysis');
                addDetails(details, [
                    ['provider', ai.analysis.provider],
                    ['model', ai.analysis.model],
                    ['status', translatedCode('analysis.state', ai.analysis.state)],
                    ['execution.mode', translatedCode('analysis.mode', ai.analysis.mode)]
                ]);
            }
        });
    }

    function loadAboutInfo() {
        var request = ++aboutRequest;
        var details = el('aboutAiDetails');
        if (details) details.textContent = '';
        setAiStatus('loading');
        return window.TaxonomyReferenceApi.about()
            .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
            .then(function (data) {
                if (request !== aboutRequest) return;
                aboutInfo = data;

                // Update navbar version
                var navVer = el('navbarVersion');
                if (navVer && data.version && data.version !== 'unknown') {
                    navVer.textContent = 'v' + data.version;
                }

                // Update modal fields
                var versionEl = el('aboutVersion');
                if (versionEl) versionEl.textContent = data.version || '—';

                var buildEl = el('aboutBuildTime');
                if (buildEl) buildEl.textContent = formatDate(data.buildTime);

                var commitEl = el('aboutCommit');
                if (commitEl) {
                    if (data.commit && data.commit !== 'unknown') {
                        var link = document.createElement('a');
                        link.href = 'https://github.com/carstenartur/Taxonomy/commit/' + escapeHtml(data.commit);
                        link.target = '_blank';
                        link.rel = 'noopener';
                        link.textContent = data.commit;
                        commitEl.textContent = '';
                        commitEl.appendChild(link);
                        if (data.commitTime) {
                            var span = document.createElement('span');
                            span.className = 'text-muted ms-2 small';
                            span.textContent = formatDate(data.commitTime);
                            commitEl.appendChild(span);
                        }
                    } else {
                        commitEl.textContent = '—';
                    }
                }

                var branchEl = el('aboutBranch');
                if (branchEl) branchEl.textContent = data.branch || '—';
                renderLegalResources(data);
                return renderAi(data.ai, request);
            })
            .catch(function () {
                if (request === aboutRequest) {
                    if (details) details.textContent = '';
                    setAiStatus('error');
                }
            });
    }

    function loadThirdPartyNotices() {
        if (noticesLoaded) return;
        noticesLoaded = true;
        var contentEl = el('aboutThirdPartyContent');
        if (!contentEl) return;
        var noticesUrl = aboutInfo && aboutInfo.thirdPartyNoticesUrl
            ? aboutInfo.thirdPartyNoticesUrl : '/api/about/third-party';
        fetch(noticesUrl)
            .then(function (r) { return r.ok ? r.text() : Promise.reject(r.status); })
            .then(function (text) {
                contentEl.textContent = text;
            })
            .catch(function () {
                contentEl.textContent = t('about.third.party.error');
            });
    }

    function initTabs() {
        var tabs = document.querySelectorAll('#aboutModalTabs [data-about-tab]');
        tabs.forEach(function (tab) {
            tab.addEventListener('click', function (e) {
                e.preventDefault();
                tabs.forEach(function (candidate) { candidate.classList.remove('active'); });
                tab.classList.add('active');

                var target = tab.getAttribute('data-about-tab');
                var infoPane = el('about-info-pane');
                var noticesPane = el('about-notices-pane');

                if (target === 'info') {
                    if (infoPane) infoPane.classList.remove('d-none');
                    if (noticesPane) noticesPane.classList.add('d-none');
                } else if (target === 'notices') {
                    if (infoPane) infoPane.classList.add('d-none');
                    if (noticesPane) noticesPane.classList.remove('d-none');
                    loadThirdPartyNotices();
                }
            });
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        var aboutBtn = el('aboutBtn');
        var aboutModalEl = el('aboutModal');

        if (aboutBtn && aboutModalEl && window.bootstrap) {
            var modal = new window.bootstrap.Modal(aboutModalEl);

            aboutBtn.addEventListener('click', function () {
                modal.show();
            });
            aboutModalEl.addEventListener('show.bs.modal', loadAboutInfo);
            aboutModalEl.addEventListener('hidden.bs.modal', function () {
                var menu = aboutBtn.closest('details');
                var target = menu && !menu.open ? menu.querySelector('summary') : aboutBtn;
                window.TaxonomyAppChrome.restoreFocus(target);
            });
        }

        initTabs();

        // A navigation link only: no session inventory is fetched on page load.
        var health = el('healthDashboard');
        if (health && sessionsUrl && !el('browserSessionsLink')) {
            var sessions = document.createElement('a');
            sessions.id = 'browserSessionsLink';
            sessions.className = 'btn btn-sm btn-outline-secondary mt-3';
            var target = new URL(sessionsUrl.href);
            target.searchParams.set('lang', document.documentElement.lang || 'en');
            sessions.href = target.toString();
            sessions.textContent = localized('Signed-in sessions', 'Angemeldete Sitzungen');
            health.appendChild(sessions);
            TaxonomyI18n.ready().then(function () {
                sessions.textContent = t('sessions.title');
            }).catch(function () {
                // Keep the usable local-language fallback if translations fail.
            });
        }

        // Load administrator snapshot UI only on the page containing its existing health panel.
        if (el('healthDashboard') && !window.TaxonomySystemInformation) {
            var systemScript = document.createElement('script');
            systemScript.src = TaxonomyI18n.resolveUrl('/js/shared/taxonomy-system-information.js');
            document.head.appendChild(systemScript);
        }

        // Update navbar version and legal-resource links on page load.
        loadAboutInfo();
    });

    return {
        loadAboutInfo: loadAboutInfo,
        loadThirdPartyNotices: loadThirdPartyNotices
    };
}());
