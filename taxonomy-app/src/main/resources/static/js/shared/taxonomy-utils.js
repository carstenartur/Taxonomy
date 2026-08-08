/**
 * Shared utility and accessibility functions for the Taxonomy UI.
 *
 * Loaded before feature modules so escaping, navigation semantics, application
 * dialogs, code suggestions and dynamic ARIA synchronization are available
 * application-wide.
 */
window.TaxonomyUtils = (function () {
    'use strict';

    function escapeHtml(value) {
        if (!value) return '';
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function stripHtml(value) {
        if (!value) return '';
        var doc = new DOMParser().parseFromString(String(value), 'text/html');
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

    document.addEventListener('shown.bs.modal', function (event) {
        event.target.setAttribute('data-modal-visible', 'true');
    }, true);
    document.addEventListener('hidden.bs.modal', function (event) {
        event.target.setAttribute('data-modal-visible', 'false');
    }, true);
    document.addEventListener('shown.bs.toast', function (event) {
        event.target.setAttribute('data-toast-visible', 'true');
    }, true);
    document.addEventListener('hidden.bs.toast', function (event) {
        event.target.setAttribute('data-toast-visible', 'false');
    }, true);

    // ── Main navigation semantics ─────────────────────────────────────────
    function syncMainNavigation() {
        var tabList = document.getElementById('mainNavTabs');
        if (!tabList) return;
        tabList.setAttribute('role', 'tablist');
        tabList.setAttribute('aria-label', currentLanguage() === 'de'
            ? 'Hauptbereiche' : 'Main sections');

        Array.from(tabList.querySelectorAll('.nav-link[data-page]')).forEach(function (link) {
            var page = link.getAttribute('data-page');
            var active = link.classList.contains('active');
            var wrapper = link.closest('li');
            if (wrapper) wrapper.setAttribute('role', 'presentation');
            if (!link.id) link.id = 'main-tab-' + page;
            link.setAttribute('role', 'tab');
            link.setAttribute('aria-selected', active ? 'true' : 'false');
            link.setAttribute('aria-controls', 'tab-' + page);
            link.setAttribute('tabindex', active ? '0' : '-1');
            var pane = document.getElementById('tab-' + page);
            if (pane) {
                pane.setAttribute('role', 'tabpanel');
                pane.setAttribute('aria-labelledby', link.id);
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
            var current = tabs.indexOf(document.activeElement);
            if (!tabs.length || current < 0) return;
            event.preventDefault();
            var next;
            if (event.key === 'Home') next = 0;
            else if (event.key === 'End') next = tabs.length - 1;
            else if (event.key === 'ArrowRight') next = (current + 1) % tabs.length;
            else next = (current - 1 + tabs.length) % tabs.length;
            tabs[next].focus();
            tabs[next].click();
            syncMainNavigation();
            syncResponsiveMainNavigation();
        });

        new MutationObserver(syncMainNavigation).observe(tabList, {
            subtree: true,
            attributes: true,
            attributeFilter: ['class']
        });
    }

    // ── Discoverable responsive primary navigation ───────────────────────
    function authorizedMainNavigationLinks() {
        var tabList = document.getElementById('mainNavTabs');
        if (!tabList) return [];
        return Array.from(tabList.querySelectorAll('.nav-link[data-page]')).filter(function (link) {
            var item = link.closest('.nav-item');
            return item && getComputedStyle(item).display !== 'none';
        });
    }

    function navigationLabel(link) {
        return (link.textContent || link.getAttribute('data-page') || '')
            .replace(/\s+/g, ' ').trim();
    }

    function syncResponsiveMainNavigation() {
        var select = document.getElementById('mobileMainNavigationSelect');
        if (!select) return;
        var links = authorizedMainNavigationLinks();
        var active = links.find(function (link) { return link.classList.contains('active'); });
        var selectedPage = active ? active.getAttribute('data-page') : select.value;
        var existing = Array.from(select.options).map(function (option) { return option.value; });
        var desired = links.map(function (link) { return link.getAttribute('data-page'); });
        if (existing.join('|') !== desired.join('|')) {
            select.replaceChildren();
            links.forEach(function (link) {
                var option = document.createElement('option');
                option.value = link.getAttribute('data-page');
                option.textContent = navigationLabel(link);
                select.appendChild(option);
            });
        }
        if (desired.includes(selectedPage)) select.value = selectedPage;
        select.disabled = desired.length === 0;
    }

    function focusCurrentTask() {
        if (typeof window.navigateToPage === 'function') {
            window.navigateToPage('analyze');
        } else {
            document.querySelector('#mainNavTabs [data-page="analyze"]')?.click();
        }
        requestAnimationFrame(function () {
            var nextAction = document.getElementById('taskNextAction');
            var input = document.getElementById('businessText');
            var target = nextAction && !nextAction.disabled ? nextAction : input;
            if (!target) return;
            target.scrollIntoView({ block: 'center', inline: 'nearest' });
            target.focus({ preventScroll: true });
        });
    }

    function installResponsiveMainNavigation() {
        var tabList = document.getElementById('mainNavTabs');
        if (!tabList || document.getElementById('mobileMainNavigation')) return;

        var wrapper = document.createElement('nav');
        wrapper.id = 'mobileMainNavigation';
        wrapper.className = 'mobile-main-navigation';
        wrapper.setAttribute('aria-label', currentLanguage() === 'de'
            ? 'Bereichsauswahl' : 'Section navigation');

        var label = document.createElement('label');
        label.htmlFor = 'mobileMainNavigationSelect';
        label.className = 'mobile-main-navigation-label';
        label.textContent = currentLanguage() === 'de' ? 'Bereich' : 'Section';

        var select = document.createElement('select');
        select.id = 'mobileMainNavigationSelect';
        select.className = 'form-select mobile-main-navigation-select';
        select.setAttribute('aria-label', currentLanguage() === 'de'
            ? 'Hauptbereich auswählen' : 'Choose main section');
        select.addEventListener('change', function () {
            var page = select.value;
            if (typeof window.navigateToPage === 'function') {
                window.navigateToPage(page);
            } else {
                document.querySelector('#mainNavTabs [data-page="' + CSS.escape(page) + '"]')?.click();
            }
            syncResponsiveMainNavigation();
        });

        var taskButton = document.createElement('button');
        taskButton.id = 'mobileCurrentTaskBtn';
        taskButton.type = 'button';
        taskButton.className = 'btn btn-primary mobile-current-task-button';
        taskButton.textContent = currentLanguage() === 'de' ? 'Aktuelle Aufgabe' : 'Current task';
        taskButton.addEventListener('click', focusCurrentTask);

        wrapper.append(label, select, taskButton);
        tabList.parentElement.insertBefore(wrapper, tabList);
        syncResponsiveMainNavigation();

        new MutationObserver(syncResponsiveMainNavigation).observe(tabList, {
            subtree: true,
            childList: true,
            attributes: true,
            attributeFilter: ['class', 'style', 'aria-selected']
        });
        new MutationObserver(syncResponsiveMainNavigation).observe(document.body, {
            attributes: true,
            attributeFilter: ['class']
        });
        window.addEventListener('hashchange', syncResponsiveMainNavigation);
    }

    // ── Responsive task reading and focus order ───────────────────────────
    function installResponsiveTaskOrder() {
        var leftPanel = document.getElementById('leftPanel');
        var rightPanel = document.getElementById('rightPanel');
        if (!leftPanel || !rightPanel || leftPanel.parentElement !== rightPanel.parentElement) return;

        var row = leftPanel.parentElement;
        // Bootstrap's lg columns become the desktop two-column layout at 992px.
        var narrowViewport = window.matchMedia('(max-width: 991.98px)');
        var synchronize = function () {
            if (narrowViewport.matches) {
                if (rightPanel.nextElementSibling !== leftPanel) {
                    row.insertBefore(rightPanel, leftPanel);
                }
                row.dataset.taskOrder = 'primary-first';
            } else {
                if (leftPanel.nextElementSibling !== rightPanel) {
                    row.insertBefore(leftPanel, rightPanel);
                }
                row.dataset.taskOrder = 'reference-first';
            }
        };

        synchronize();
        if (typeof narrowViewport.addEventListener === 'function') {
            narrowViewport.addEventListener('change', synchronize);
        } else {
            narrowViewport.addListener(synchronize);
        }
    }

    // ── Dynamic tree accessibility ────────────────────────────────────────
    function syncTreeItemAccessibility(item) {
        if (!item || item.getAttribute('role') !== 'treeitem') return;
        var code = item.querySelector(':scope > .tax-node-header .tax-code');
        var name = item.querySelector(':scope > .tax-node-header .tax-name');
        var score = item.querySelector(':scope > .tax-node-header .tax-pct');
        var reason = item.querySelector(':scope > .tax-node-header .tax-reason-icon');
        var parts = [];
        if (code) parts.push(code.textContent.trim());
        if (name) parts.push(name.textContent.trim());
        if (score) parts.push((currentLanguage() === 'de' ? 'Relevanz ' : 'Relevance ') + score.textContent.trim());
        if (reason && reason.title) {
            parts.push((currentLanguage() === 'de' ? 'Begründung: ' : 'Reason: ') + reason.title);
        }
        var label = parts.join(', ');
        if (label && item.getAttribute('aria-label') !== label) {
            item.setAttribute('aria-label', label);
        }
    }

    function syncTreeContainerSemantics(tree) {
        if (tree.querySelector('[role="treeitem"]')) {
            tree.setAttribute('role', 'tree');
            tree.removeAttribute('aria-busy');
        } else {
            tree.removeAttribute('role');
            tree.setAttribute('aria-busy', 'true');
        }
    }

    function installTreeAccessibilityObserver() {
        var tree = document.getElementById('taxonomyTree');
        if (!tree || tree.dataset.a11yObserved) return;
        tree.dataset.a11yObserved = 'true';
        var syncAll = function () {
            syncTreeContainerSemantics(tree);
            tree.querySelectorAll('[role="treeitem"]').forEach(syncTreeItemAccessibility);
            refreshNodeCodeSuggestions();
        };
        new MutationObserver(function (mutations) {
            mutations.forEach(function (mutation) {
                var element = mutation.target.nodeType === Node.ELEMENT_NODE
                    ? mutation.target
                    : mutation.target.parentElement;
                var item = element && element.closest ? element.closest('[role="treeitem"]') : null;
                if (item) syncTreeItemAccessibility(item);
                mutation.addedNodes.forEach(function (node) {
                    if (node.nodeType !== Node.ELEMENT_NODE) return;
                    if (node.matches('[role="treeitem"]')) syncTreeItemAccessibility(node);
                    node.querySelectorAll('[role="treeitem"]').forEach(syncTreeItemAccessibility);
                });
            });
            syncTreeContainerSemantics(tree);
            refreshNodeCodeSuggestions();
        }).observe(tree, {
            subtree: true,
            childList: true,
            characterData: true,
            attributes: true,
            attributeFilter: ['title', 'class']
        });
        document.addEventListener('taxonomy:view-rendered', syncAll);
        syncAll();
    }

    // ── Recognition instead of recall for taxonomy codes ─────────────────
    function ensureNodeCodeDatalist() {
        var list = document.getElementById('taxonomyNodeCodeOptions');
        if (list) return list;
        list = document.createElement('datalist');
        list.id = 'taxonomyNodeCodeOptions';
        document.body.appendChild(list);
        return list;
    }

    function refreshNodeCodeSuggestions() {
        var tree = document.getElementById('taxonomyTree');
        if (!tree) return;
        var entries = new Map();
        tree.querySelectorAll('.tax-node[data-code]').forEach(function (node) {
            var code = node.dataset.code;
            var name = node.querySelector(':scope > .tax-node-header .tax-name');
            if (code) entries.set(code, name ? name.textContent.trim() : '');
        });
        if (!entries.size) return;
        var list = ensureNodeCodeDatalist();
        list.replaceChildren();
        Array.from(entries.entries())
            .sort(function (left, right) { return left[0].localeCompare(right[0]); })
            .forEach(function (entry) {
                var option = document.createElement('option');
                option.value = entry[0];
                option.label = entry[1];
                list.appendChild(option);
            });
        ['newRelSource', 'newRelTarget', 'graphNodeInput'].forEach(function (id) {
            var input = document.getElementById(id);
            if (input) input.setAttribute('list', list.id);
        });
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
            '<form novalidate>' +
            '<h2 id="taxonomyAccessibleDialogTitle" class="h5"></h2>' +
            '<div id="taxonomyAccessibleDialogMessage" class="mb-3"></div>' +
            '<div id="taxonomyAccessibleDialogField" class="mb-3 d-none">' +
            '<label id="taxonomyAccessibleDialogLabel" for="taxonomyAccessibleDialogInput" class="form-label"></label>' +
            '<input id="taxonomyAccessibleDialogInput" class="form-control" type="number" min="0" max="100" step="1">' +
            '<div id="taxonomyAccessibleDialogError" class="text-danger small mt-1" role="alert"></div>' +
            '</div>' +
            '<div class="d-flex justify-content-end gap-2">' +
            '<button id="taxonomyAccessibleDialogCancel" type="button" class="btn btn-outline-secondary"></button>' +
            '<button id="taxonomyAccessibleDialogConfirm" type="submit" class="btn btn-primary"></button>' +
            '</div></form>';
        document.body.appendChild(dialog);
        return dialog;
    }

    function resetDialogHandlers(dialog) {
        var form = dialog.querySelector('form');
        var replacement = form.cloneNode(true);
        form.replaceWith(replacement);
        return replacement;
    }

    function showMessage(message, title) {
        var dialog = ensureDialog();
        var form = resetDialogHandlers(dialog);
        form.querySelector('#taxonomyAccessibleDialogTitle').textContent = title ||
            (currentLanguage() === 'de' ? 'Hinweis' : 'Notice');
        form.querySelector('#taxonomyAccessibleDialogMessage').textContent = String(message || '');
        form.querySelector('#taxonomyAccessibleDialogField').classList.add('d-none');
        form.querySelector('#taxonomyAccessibleDialogCancel').classList.add('d-none');
        var confirm = form.querySelector('#taxonomyAccessibleDialogConfirm');
        confirm.textContent = 'OK';
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            dialog.close('confirm');
        });
        dialog.showModal();
        confirm.focus();
    }

    function requestScore(code) {
        return new Promise(function (resolve) {
            var dialog = ensureDialog();
            var form = resetDialogHandlers(dialog);
            var lang = currentLanguage();
            var input = form.querySelector('#taxonomyAccessibleDialogInput');
            var error = form.querySelector('#taxonomyAccessibleDialogError');
            var cancel = form.querySelector('#taxonomyAccessibleDialogCancel');
            var confirm = form.querySelector('#taxonomyAccessibleDialogConfirm');
            form.querySelector('#taxonomyAccessibleDialogTitle').textContent = lang === 'de'
                ? 'Relevanz manuell setzen' : 'Set relevance manually';
            form.querySelector('#taxonomyAccessibleDialogMessage').textContent = code;
            form.querySelector('#taxonomyAccessibleDialogLabel').textContent = lang === 'de'
                ? 'Relevanzwert von 0 bis 100' : 'Relevance score from 0 to 100';
            form.querySelector('#taxonomyAccessibleDialogField').classList.remove('d-none');
            cancel.classList.remove('d-none');
            cancel.textContent = lang === 'de' ? 'Abbrechen' : 'Cancel';
            confirm.textContent = lang === 'de' ? 'Übernehmen' : 'Apply';
            input.value = '';
            error.textContent = '';

            cancel.addEventListener('click', function () {
                dialog.close('cancel');
                resolve(null);
            }, { once: true });
            form.addEventListener('submit', function (event) {
                event.preventDefault();
                var value = Number.parseInt(input.value, 10);
                if (!Number.isInteger(value) || value < 0 || value > 100) {
                    error.textContent = lang === 'de'
                        ? 'Bitte einen ganzzahligen Wert zwischen 0 und 100 eingeben.'
                        : 'Enter an integer between 0 and 100.';
                    input.setAttribute('aria-invalid', 'true');
                    input.focus();
                    return;
                }
                input.removeAttribute('aria-invalid');
                dialog.close('confirm');
                resolve(value);
            });
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
        installResponsiveMainNavigation();
        installResponsiveTaskOrder();
        installTreeAccessibilityObserver();
        installManualScoreDialog();
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
        syncTreeItemAccessibility: syncTreeItemAccessibility,
        refreshNodeCodeSuggestions: refreshNodeCodeSuggestions,
        syncResponsiveMainNavigation: syncResponsiveMainNavigation,
        focusCurrentTask: focusCurrentTask
    };
})();
