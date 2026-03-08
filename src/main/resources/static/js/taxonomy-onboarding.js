/* taxonomy-onboarding.js – Welcome overlay & progressive disclosure for first-time users */

(function () {
    'use strict';

    var STORAGE_KEY = 'taxonomy_onboarded';

    /**
     * Show the welcome overlay if the user has not dismissed it before.
     */
    function init() {
        if (localStorage.getItem(STORAGE_KEY)) {
            return; // User already dismissed the overlay
        }

        var overlay = document.createElement('div');
        overlay.className = 'onboarding-overlay';
        overlay.id = 'onboardingOverlay';
        overlay.innerHTML =
            '<div class="onboarding-card">' +
            '  <h2>&#127756; Welcome to the NATO NC3T Taxonomy Browser</h2>' +
            '  <p>Map your business requirements to the NATO C3 Taxonomy in three simple steps:</p>' +
            '  <div class="steps">' +
            '    <div class="step-item"><span class="step-number">1</span><span>Describe your requirement in the text area on the right</span></div>' +
            '    <div class="step-item"><span class="step-number">2</span><span>Click <strong>Analyze with AI</strong> to find matching taxonomy nodes</span></div>' +
            '    <div class="step-item"><span class="step-number">3</span><span>Explore the results in different views, export diagrams, and browse dependencies</span></div>' +
            '  </div>' +
            '  <button id="onboardingDismiss" class="btn btn-primary">Got it &mdash; let\'s start!</button>' +
            '</div>';

        document.body.appendChild(overlay);

        var dismissBtn = document.getElementById('onboardingDismiss');
        if (dismissBtn) {
            dismissBtn.addEventListener('click', dismiss);
        }

        // Also dismiss on clicking the overlay backdrop
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) {
                dismiss();
            }
        });
    }

    function dismiss() {
        localStorage.setItem(STORAGE_KEY, '1');
        var overlay = document.getElementById('onboardingOverlay');
        if (overlay) {
            overlay.style.opacity = '0';
            overlay.style.transition = 'opacity 0.3s ease';
            setTimeout(function () { overlay.remove(); }, 300);
        }
    }

    /**
     * Reset onboarding (for testing or admin use).
     */
    function reset() {
        localStorage.removeItem(STORAGE_KEY);
    }

    // Auto-init on DOMContentLoaded
    document.addEventListener('DOMContentLoaded', init);

    // Public API
    window.TaxonomyOnboarding = {
        init: init,
        dismiss: dismiss,
        reset: reset
    };
})();
