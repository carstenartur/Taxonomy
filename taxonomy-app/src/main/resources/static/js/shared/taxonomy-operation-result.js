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

    /** Show successful operation feedback. */
    function showSuccess(title, message) {
        showToast(title, message, 'success');
    }

    /** Show failed operation feedback. */
    function showError(title, message) {
        showToast(title, message, 'danger');
    }

    /** Show warning operation feedback. */
    function showWarning(title, message) {
        showToast(title, message, 'warning');
    }

    function ensureGlobalContainer(toastEl) {
        var container = toastEl.closest('.toast-container');
        if (!container) return null;
        // Operation feedback is global. Re-parenting protects it from accidentally
        // inheriting display:none when malformed or dynamically rearranged markup
        // leaves the container under a hidden tab or modal.
        if (container.parentElement !== document.body) {
            document.body.appendChild(container);
        }
        container.hidden = false;
        container.style.display = 'block';
        container.style.visibility = 'visible';
        container.style.opacity = '1';
        return container;
    }

    function markVisible(toastEl) {
        ensureGlobalContainer(toastEl);
        toastEl.hidden = false;
        toastEl.setAttribute('aria-hidden', 'false');
        toastEl.dataset.toastVisible = 'true';
        toastEl.classList.remove('hide');
        toastEl.classList.add('show');
        // Inline visibility is intentional: operation feedback must remain
        // perceivable with Reduced Motion and during Bootstrap transitions.
        toastEl.style.display = 'block';
        toastEl.style.visibility = 'visible';
        toastEl.style.opacity = '1';
        toastEl.style.minWidth = '18rem';
    }

    function clearVisibilityOverride(toastEl) {
        toastEl.dataset.toastVisible = 'false';
        toastEl.setAttribute('aria-hidden', 'true');
        toastEl.style.removeProperty('display');
        toastEl.style.removeProperty('visibility');
        toastEl.style.removeProperty('opacity');
        toastEl.style.removeProperty('min-width');
    }

    /**
     * Show an operation result. Errors and warnings remain until explicitly
     * dismissed; successful operations auto-hide after eight seconds.
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

        var autoHide = type === 'success';
        if (typeof bootstrap !== 'undefined' && bootstrap.Toast) {
            var existing = bootstrap.Toast.getInstance(toastEl);
            if (existing) existing.dispose();
            toastEl.addEventListener('shown.bs.toast', function () {
                markVisible(toastEl);
            }, { once: true });
            toastEl.addEventListener('hidden.bs.toast', function () {
                clearVisibilityOverride(toastEl);
            }, { once: true });
            new bootstrap.Toast(toastEl, { delay: 8000, autohide: autoHide }).show();
        } else if (autoHide) {
            window.setTimeout(function () {
                toastEl.classList.remove('show');
                clearVisibilityOverride(toastEl);
            }, 8000);
        }
    }

    return {
        showSuccess: showSuccess,
        showError: showError,
        showWarning: showWarning,
        showToast: showToast
    };
}());
