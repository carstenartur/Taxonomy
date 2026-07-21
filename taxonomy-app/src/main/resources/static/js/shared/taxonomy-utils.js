/**
 * Shared utility and accessibility functions for the Taxonomy UI.
 *
 * Include this script before the feature modules so escaping, consistent
 * dialogs, navigation semantics and dynamic ARIA synchronization are available
 * application-wide.
 */
window.TaxonomyUtils = (function () {
    'use strict';

    function escapeHtml(s) {
        if (!s) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function stripHtml(s) {
        if (!s) return '';
        var doc = new DOMParser().parseFromString(String(s), 'text/html');
        return doc.body.textContent || '';
    }

    function currentLanguage() {
        return (document.documentElement.lang || 'en').toLowerCase().startsWith('de') ? 'de' : 'en';
    }

    function loadErgonomicsStyles() {
        if (document.querySelector('link[data-taxonomy-ergonomics]')) return;
        var link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = '/css/taxonomy-ergonomics.css';
        link.dataset.taxonomyErgonomics = 'true';
        document.head.appendChild(link);
    }

    // ── Bootstrap lifecycle attributes ────────────────────────────────────
    document.addEventListener('shown.bs.modal', function (e) {
        e.target.setAttribute('data-modal-visible', 'true');
    }, true);
    document.addEventListener('hidden.bs.modal', function (e) {
        e.target.setAttribute('data-modal-visible', 'false');
    }, true);
    document.addEventListener('shown.bs.toast', function (e) {
        e.target.setAttribute('data-toast-visible', 'true');
    }, true);
    document.addEventListener('hidden.bs.toast', function (e) {
        e.target.setAttribute('data-toast-visible', 'false');
    }, true);

    // ── Main navigation semantics ─────────────────────────────────────────
    function syncMainNavigation() {
        var tabList = document.getElementById('mainNavTabs');
        if (!tabList) return;
        tabList.setAttribute('role', 'tablist');
        tabList.setAttribute('aria-label', currentLanguage() === 'de'
            ? 'Hauptbereiche' : 'Main sections');

        var links = Array.from(tabList.querySelectorAll('.nav-link[data-page]'));
        links.forEach(function (link) {
            var page = link.getAttribute('data-page');
            var active = link.classList.contains('active');
            link.setAttribute('role', 'tab');
            link.setAttribute('aria-selected', active ? 'true' : 'false');
            link.setAttribute('aria-controls', 'tab-' + page);
            link.setAttribute('tabindex', active ? '0' : '-1');
            var pane = document.getElementById('tab-' + page);
            if (pane) {
                pane.setAttribute('role', 'tabpanel');
                pane.setAttribute('aria-labelledby', link.id || 'main-tab-' + page);
                if (!link.id) link.id = 'main-tab-' + page;
            }
        });
    }

    function installMainNavigationKeyboardSupport() {
        var tabList = document.getElementById('mainNavTabs');
        if (!tabList || tabList.dataset.keyboardReady) return;
        tabList.dataset.keyboardReady = 'true';
        tabList.addEventListener('keydown', function (event) {
            if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
            var tabs = Array.from(tabList.querySelectorAll('.nav-link[data-page]'))
                .filter(function (tab) { return tab.offsetParent !== null; });
            if (!tabs.length) return;
            var current = tabs.indexOf(document.activeElement);
            if (current < 0) return;
            event.preventDefault();
            var next;
            if (event.key === 'Home') next = 0;
            else if (event.key === 'End') next = tabs.length - 1;
            else if (event.key === 'ArrowRight') next = (current + 1) % tabs.length;
            else next = (current - 1 + tabs.length) % tabs.length;
            tabs[next].focus();
            tabs[next].click();
        });

        new MutationObserver(syncMainNavigation).observe(tabList, {
            subtree: true,
            attributes: true,
            attributeFilter: ['class']
        });
    }

    // ── Dynamic tree accessibility ────────────────────────────────────────
    function syncTreeItemAccessibility(item) {
        if (!item || item.getAttribute('role') !== 'treeitem') return;
        var code = item.querySelector(':scope > .tax-node-header .tax-code');
        var name = item.querySelector(':scope > .tax-node-header .tax-name');
        var pct = item.querySelector(':scope > .tax-node-header .tax-pct');
        var reason = item.querySelector(':scope > .tax-node-header .tax-reason-icon');
        var parts = [];
        if (code) parts.push(code.textContent.trim());
        if (name) parts.push(name.textContent.trim());
        if (pct) parts.push((currentLanguage() === 'de' ? 'Relevanz ' : 'Relevance ') + pct.textContent.trim());
        if (reason && reason.title) {
            parts.push((currentLanguage() === 'de' ? 'Begründung: ' : 'Reason: ') + reason.title);
        }
        if (parts.length) item.setAttribute('aria-label', parts.join(', '));
    }

    function installTreeAccessibilityObserver() {
        var tree = document.getElementById('taxonomyTree');
        if (!tree || tree.dataset.a11yObserved) return;
        tree.dataset.a11yObserved = 'true';
        var syncAll = function () {
            tree.querySelectorAll('[role="treeitem"]').forEach(syncTreeItemAccessibility);
        };
        new MutationObserver(function (mutations) {
            mutations.forEach(function (mutation) {
                var item = mutation.target.nodeType === Node.ELEMENT_NODE
                    ? mutation.target.closest('[role="treeitem"]')
                    : mutation.target.parentElement && mutation.target.parentElement.closest('[role="treeitem"]');
                if (item) syncTreeItemAccessibility(item);
                mutation.addedNodes.forEach(function (node) {
                    if (node.nodeType !== Node.ELEMENT_NODE) return;
                    if (node.matches && node.matches('[role="treeitem"]')) syncTreeItemAccessibility(node);
                    if (node.querySelectorAll) node.querySelectorAll('[role="treeitem"]').forEach(syncTreeItemAccessibility);
                });
            });
        }).observe(tree, { subtree: true, childList: true, characterData: true, attributes: true });
        document.addEventListener('taxonomy:view-rendered', syncAll);
        syncAll();
    }

    // ── Accessible application dialogs ────────────────────────────────────
    function ensureDialog() {
        var dialog = document.getElementById('taxonomyAccessibleDialog');
        if (dialog) return dialog;
        dialog = document.createElement('dialog');
        dialog.id = 'taxonomyAccessibleDialog';
        dialog.className = 'taxonomy-accessible-dialog';
        dialog.setAttribute('aria-labelledby', 'taxonomyAccessibleDialogTitle');
        dialog.innerHTML =
            '<form method="dialog">' +
            '<h2 id="taxonomyAccessibleDialogTitle" class="h5"></h2>' +
            '<div id="taxonomyAccessibleDialogMessage" class="mb-3"></div>' +
            '<div id="taxonomyAccessibleDialogField" class="mb-3 d-none">' +
            '<label id="taxonomyAccessibleDialogLabel" for="taxonomyAccessibleDialogInput" class="form-label"></label>' +
            '<input id="taxonomyAccessibleDialogInput" class="form-control" type="number" min="0" max="100" step="1">' +
            '<div id="taxonomyAccessibleDialogError" class="text-danger small mt-1" role="alert"></div>' +
            '</div>' +
            '<div class="d-flex justify-content-end gap-2">' +
            '<button id="taxonomyAccessibleDialogCancel" value="cancel" class="btn btn-outline-secondary"></button>' +
            '<button id="taxonomyAccessibleDialogConfirm" value="confirm" class="btn btn-primary"></button>' +
            '</div></form>';
        document.body.appendChild(dialog);
        return dialog;
    }

    function showMessage(message, title) {
        var dialog = ensureDialog();
        dialog.querySelector('#taxonomyAccessibleDialogTitle').textContent = title ||
            (currentLanguage() === 'de' ? 'Hinweis' : 'Notice');
        dialog.querySelector('#taxonomyAccessibleDialogMessage').textContent = String(message || '');
        dialog.querySelector('#taxonomyAccessibleDialogField').classList.add('d-none');
        dialog.querySelector('#taxonomyAccessibleDialogCancel').classList.add('d-none');
        var confirm = dialog.querySelector('#taxonomyAccessibleDialogConfirm');
        confirm.textContent = 'OK';
        dialog.showModal();
        confirm.focus();
    }

    function requestScore(code) {
        return new Promise(function (resolve) {
            var dialog = ensureDialog();
            var lang = currentLanguage();
            var input = dialog.querySelector('#taxonomyAccessibleDialogInput');
            var error = dialog.querySelector('#taxonomyAccessibleDialogError');
            var cancel = dialog.querySelector('#taxonomyAccessibleDialogCancel');
            var confirm = dialog.querySelector('#taxonomyAccessibleDialogConfirm');
            dialog.querySelector('#taxonomyAccessibleDialogTitle').textContent = lang === 'de'
                ? 'Relevanz manuell setzen' : 'Set relevance manually';
            dialog.querySelector('#taxonomyAccessibleDialogMessage').textContent = code;
            dialog.querySelector('#taxonomyAccessibleDialogLabel').textContent = lang === 'de'
                ? 'Relevanzwert von 0 bis 100' : 'Relevance score from 0 to 100';
            dialog.querySelector('#taxonomyAccessibleDialogField').classList.remove('d-none');
            cancel.classList.remove('d-none');
            cancel.textContent = lang === 'de' ? 'Abbrechen' : 'Cancel';
            confirm.textContent = lang === 'de' ? 'Übernehmen' : 'Apply';
            input.value = '';
            error.textContent = '';

            var closeHandler = function () {
                dialog.removeEventListener('close', closeHandler);
                if (dialog.returnValue !== 'confirm') {
                    resolve(null);
                    return;
                }
                var value = Number.parseInt(input.value, 10);
                if (!Number.isInteger(value) || value < 0 || value > 100) {
                    error.textContent = lang === 'de'
                        ? 'Bitte einen ganzzahligen Wert zwischen 0 und 100 eingeben.'
                        : 'Enter an integer between 0 and 100.';
                    dialog.showModal();
                    input.focus();
                    dialog.addEventListener('close', closeHandler, { once: true });
                    return;
                }
                resolve(value);
            };
            dialog.addEventListener('close', closeHandler, { once: true });
            dialog.showModal();
            input.focus();
        });
    }

    function installManualScoreDialog() {
        document.addEventListener('click', function (event) {
            var button = event.target.closest && event.target.closest('.tax-manual-btn');
            if (!button) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            var item = button.closest('[role="treeitem"]');
            var code = item && item.dataset ? item.dataset.code : null;
            if (!code) return;
            requestScore(code).then(function (score) {
                if (score === null) return;
                var state = window.TaxonomyState;
                if (state) {
                    state.currentScores = state.currentScores || {};
                    state.currentReasons = state.currentReasons || {};
                    state.currentScores[code] = score;
                    state.currentReasons[code] = currentLanguage() === 'de'
                        ? 'Manuell durch den Benutzer gesetzt'
                        : 'Set manually by the user';
                }
                if (typeof window._renderViewWithCurrentScores === 'function') {
                    window._renderViewWithCurrentScores();
                }
                var status = document.getElementById('a11yStatus');
                if (status) status.textContent = code + ': ' + score + '%';
            });
        }, true);
    }

    function initializeAccessibilityAndErgonomics() {
        loadErgonomicsStyles();
        syncMainNavigation();
        installMainNavigationKeyboardSupport();
        installTreeAccessibilityObserver();
        installManualScoreDialog();
        // Replace blocking browser alerts with the same accessible application dialog.
        window.alert = function (message) { showMessage(message); };
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initializeAccessibilityAndErgonomics);
    } else {
        initializeAccessibilityAndErgonomics();
    }

    return {
        escapeHtml: escapeHtml,
        stripHtml: stripHtml,
        showMessage: showMessage,
        requestScore: requestScore,
        syncTreeItemAccessibility: syncTreeItemAccessibility
    };
})();
