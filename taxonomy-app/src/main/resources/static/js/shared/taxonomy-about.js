/**
 * About Modal — fetches /api/about and displays version, build, commit info,
 * copyright, and third-party notices.
 */
window.TaxonomyAbout = (function () {
    'use strict';

    var noticesLoaded = false;

    function el(id) { return document.getElementById(id); }

    var escapeHtml = TaxonomyUtils.escapeHtml;
    var t = TaxonomyI18n.t;

    function formatDate(val) {
        if (!val) return '—';
        try { return new Date(val).toLocaleString(); } catch (e) { return String(val); }
    }

    function loadAboutInfo() {
        fetch('/api/about')
            .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
            .then(function (data) {
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
            })
            .catch(function () {
                // silently fail — info not critical
            });
    }

    function loadThirdPartyNotices() {
        if (noticesLoaded) return;
        noticesLoaded = true;
        var contentEl = el('aboutThirdPartyContent');
        if (!contentEl) return;
        fetch('/api/about/third-party')
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
                tabs.forEach(function (t) { t.classList.remove('active'); });
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

        // Update navbar version on page load
        loadAboutInfo();
    });

    return {
        loadAboutInfo: loadAboutInfo,
        loadThirdPartyNotices: loadThirdPartyNotices
    };
}());
