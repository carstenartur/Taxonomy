/**
 * About Modal — fetches /api/about and displays version, build, commit info,
 * packaged legal resources, third-party notices and supply-chain evidence.
 */
window.TaxonomyAbout = (function () {
    'use strict';

    var noticesLoaded = false;
    var aboutInfo = null;

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
        link.href = url;
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

    function loadAboutInfo() {
        fetch('/api/about')
            .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
            .then(function (data) {
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
            })
            .catch(function () {
                // Build information is useful but not required for the main UI.
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
                loadAboutInfo();
                modal.show();
            });
        }

        initTabs();

        // Update navbar version and legal-resource links on page load.
        loadAboutInfo();
    });

    return {
        loadAboutInfo: loadAboutInfo,
        loadThirdPartyNotices: loadThirdPartyNotices
    };
}());
