/* taxonomy-onboarding.js – Welcome overlay & progressive disclosure for first-time users */

(function () {
    'use strict';
    var t = TaxonomyI18n.t;

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
            '  <h2>' + t('onboarding.title') + '</h2>' +
            '  <p>' + t('onboarding.intro') + '</p>' +
            '  <div class="steps">' +
            '    <div class="step-item"><span class="step-number">1</span><span>' + t('onboarding.step1') + '</span></div>' +
            '    <div class="step-item"><span class="step-number">2</span><span>' + t('onboarding.step2') + '</span></div>' +
            '    <div class="step-item"><span class="step-number">3</span><span>' + t('onboarding.step3') + '</span></div>' +
            '  </div>' +
            '  <button id="onboardingDismiss" class="btn btn-primary">' + t('onboarding.dismiss') + '</button>' +
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
