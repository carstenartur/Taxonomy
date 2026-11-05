/* Menu shortcuts adapt existing feature controls; features own operations and state. */
(function () {
    'use strict';
    var observer, scheduled = false;
    function blocked(element) {
        if (!element || !element.isConnected || element.disabled || element.getAttribute('aria-disabled') === 'true') return true;
        for (var parent = element; parent && parent !== document.documentElement; parent = parent.parentElement) {
            if (parent.hidden || parent.getAttribute('aria-hidden') === 'true' || parent.classList.contains('d-none')
                    || window.getComputedStyle(parent).display === 'none') return true;
        }
        return false;
    }
    function target(control) {
        return String(control.dataset.appTarget || '').split(',').map(function (id) {
            return document.getElementById(id.trim());
        }).find(function (element) { return !blocked(element); });
    }
    function refresh() {
        scheduled = false;
        if (observer) observer.disconnect();
        var observed = new Set();
        document.querySelectorAll('[data-app-command][data-app-target]').forEach(function (control) {
            var available = target(control);
            control.disabled = !available;
            control.setAttribute('aria-disabled', String(!available));
            if (available && available.hasAttribute('aria-pressed')) control.setAttribute('aria-pressed', available.getAttribute('aria-pressed'));
            else control.removeAttribute('aria-pressed');
            var hint = available ? '' : window.TaxonomyI18n.t('app.command.unavailable');
            if (control.title !== hint) control.title = hint;
            String(control.dataset.appTarget).split(',').forEach(function (id) {
                for (var node = document.getElementById(id.trim()); node; node = node.parentElement) observed.add(node);
            });
        });
        if (!observer) observer = new MutationObserver(schedule);
        observed.forEach(function (node) {
            observer.observe(node, { attributes: true, attributeFilter: ['disabled', 'hidden', 'aria-disabled', 'aria-hidden', 'aria-pressed', 'class', 'style'] });
        });
    }
    function schedule() {
        if (scheduled) return;
        scheduled = true;
        window.requestAnimationFrame(refresh);
    }
    function execute(id) {
        var control = Array.from(document.querySelectorAll('[data-app-command]')).find(function (node) {
            return node.dataset.appCommand === id;
        });
        var owner = control && target(control);
        if (!owner) { refresh(); return false; }
        owner.click();
        schedule();
        return true;
    }
    document.addEventListener('click', function (event) {
        var control = event.target.closest?.('[data-app-command][data-app-target]');
        if (!control || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        execute(control.dataset.appCommand);
    });
    document.addEventListener('toggle', function (event) {
        if (event.target.closest?.('.taxonomy-app-chrome')) refresh();
    }, true);
    ['taxonomy:page-activated', 'taxonomy:roles-ready', 'taxonomy:architecture-source-changed', 'taxonomy-i18n-loaded'].forEach(function (name) {
        document.addEventListener(name, schedule);
    });
    function theme(value) {
        document.documentElement.setAttribute('data-bs-theme', value);
        var button = document.getElementById('darkModeToggle');
        if (button) {
            button.setAttribute('aria-pressed', String(value === 'dark'));
            button.textContent = window.TaxonomyI18n.t(value === 'dark' ? 'app.theme.light' : 'app.theme.dark');
        }
    }
    document.addEventListener('taxonomy-i18n-loaded', function () {
        theme(document.documentElement.getAttribute('data-bs-theme') || 'light');
    });
    function initialize() {
        var saved;
        try { saved = localStorage.getItem('taxonomy-theme'); } catch (ignored) { /* Storage is optional. */ }
        theme(saved === 'dark' || (!saved && window.matchMedia?.('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light');
        document.getElementById('darkModeToggle')?.addEventListener('click', function () {
            var next = document.documentElement.getAttribute('data-bs-theme') === 'dark' ? 'light' : 'dark';
            theme(next);
            try { localStorage.setItem('taxonomy-theme', next); } catch (ignored) { /* Retain this page's preference. */ }
        });
        refresh();
    }
    window.TaxonomyAppCommands = Object.freeze({ refresh: refresh, execute: execute });
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, { once: true });
    else initialize();
}());
