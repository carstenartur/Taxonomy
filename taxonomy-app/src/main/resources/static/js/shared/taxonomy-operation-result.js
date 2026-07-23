/**
 * taxonomy-operation-result.js — Operation Result Toast Notifications
 *
 * Provides a reusable toast notification system for Git operations
 * (merge, cherry-pick, revert, etc.) to give visual feedback
 * instead of silent refreshes.
 *
 * @module TaxonomyOperationResult
 */
window.TaxonomyOperationResult = (function () {
    'use strict';

    /**
     * Show a success toast notification.
     *
     * @param {string} title — short title, e.g. "Merge Successful"
     * @param {string} message — detail message
     */
    function showSuccess(title, message) {
        showToast(title, message, 'success');
    }

    /**
     * Show an error toast notification.
     *
     * @param {string} title — short title, e.g. "Merge Failed"
     * @param {string} message — detail message
     */
    function showError(title, message) {
        showToast(title, message, 'danger');
    }

    /**
     * Show a warning toast notification.
     *
     * @param {string} title — short title
     * @param {string} message — detail message
     */
    function showWarning(title, message) {
        showToast(title, message, 'warning');
    }

    function markVisible(toastEl) {
        toastEl.hidden = false;
        toastEl.removeAttribute('aria-hidden');
        toastEl.dataset.toastVisible = 'true';
        // Inline visibility is intentional: operation feedback must remain perceivable
        // even when reduced-motion or a browser-specific Bootstrap transition reports
        // the element as hidden while the `show` class is already present.
        toastEl.style.display = 'block';
        toastEl.style.visibility = 'visible';
        toastEl.style.opacity = '1';
    }

    function clearVisibilityOverride(toastEl) {
        toastEl.dataset.toastVisible = 'false';
        toastEl.style.removeProperty('display');
        toastEl.style.removeProperty('visibility');
        toastEl.style.removeProperty('opacity');
    }

    /**
     * Show a toast notification.
     *
     * @param {string} title — toast header title
     * @param {string} message — toast body message
     * @param {string} type — Bootstrap colour: success, danger, warning, info
     */
    function showToast(title, message, type) {
        var toastEl = document.getElementById('operationToast');
        var titleEl = document.getElementById('operationToastTitle');
        var bodyEl = document.getElementById('operationToastBody');

        if (!toastEl || !titleEl || !bodyEl) return;

        var icon = type === 'success' ? '\u2705 ' :
                   type === 'danger' ? '\u274C ' :
                   type === 'warning' ? '\u26A0\uFE0F ' : '\u2139\uFE0F ';
        titleEl.textContent = icon + title;
        bodyEl.textContent = message;

        var header = toastEl.querySelector('.toast-header');
        if (header) {
            header.className = 'toast-header';
            if (type === 'success') header.classList.add('bg-success', 'text-white');
            else if (type === 'danger') header.classList.add('bg-danger', 'text-white');
            else if (type === 'warning') header.classList.add('bg-warning', 'text-dark');
        }

        markVisible(toastEl);

        if (typeof bootstrap !== 'undefined' && bootstrap.Toast) {
            toastEl.addEventListener('hidden.bs.toast', function () {
                clearVisibilityOverride(toastEl);
            }, { once: true });
            bootstrap.Toast.getOrCreateInstance(toastEl, { delay: 5000 }).show();
        } else {
            toastEl.classList.add('show');
            window.setTimeout(function () {
                toastEl.classList.remove('show');
                clearVisibilityOverride(toastEl);
            }, 5000);
        }
    }

    return {
        showSuccess: showSuccess,
        showError: showError,
        showWarning: showWarning,
        showToast: showToast
    };
}());
