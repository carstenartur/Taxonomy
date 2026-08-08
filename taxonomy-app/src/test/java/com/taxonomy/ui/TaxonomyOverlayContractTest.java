package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the accessible modal and collision-safe transient overlay contract. */
class TaxonomyOverlayContractTest {

    @Test
    void onboardingUsesANativeModalLifecycle() throws Exception {
        String source = resource("/static/js/shared/taxonomy-onboarding.js");

        assertThat(source)
                .contains("document.createElement('dialog')")
                .contains("dialog.setAttribute('aria-modal', 'true')")
                .contains("dialog.setAttribute('aria-labelledby', 'onboardingTitle')")
                .contains("dialog.setAttribute('aria-describedby', 'onboardingIntro')")
                .contains("dialog.showModal()")
                .contains("dialog.addEventListener('cancel'")
                .contains("trapDialogFocus(dialog, event)")
                .contains("returnFocus.focus({ preventScroll: true })")
                .contains("var expertShortcutsInstalled = false;")
                .contains("if (expertShortcutsInstalled) {")
                .contains("expertShortcutsInstalled = true;")
                .doesNotContain("overlay.style.opacity = '0'");
    }

    @Test
    void transientNotificationsShareACollisionAwareSafeAreaLane() throws Exception {
        String source = resource("/static/js/shared/taxonomy-onboarding.js");
        String css = resource("/static/css/taxonomy-ergonomics.css");

        assertThat(source)
                .contains("function ensureOverlayLane()")
                .contains("function refreshOverlayLane()")
                .contains("document.elementFromPoint(x, y)")
                .contains("['bottom-end', 'bottom-start', 'top-end', 'top-start']")
                .contains("toast.setAttribute('aria-live', 'polite')")
                .contains("overlayLaneObserver.observe(document.body, { childList: true })")
                .contains("lane.dataset.refreshVersion = String(")
                .doesNotContain("overlayLaneObserver.observe(document.body, { childList: true, subtree: true })");

        assertThat(css)
                .contains("dialog.onboarding-overlay::backdrop")
                .contains("env(safe-area-inset-bottom)")
                .contains(".taxonomy-overlay-lane")
                .contains("min-width: 44px !important")
                .contains("min-height: 44px !important")
                .contains(".taxonomy-overlay-lane > .undo-toast > span")
                .contains("max-width: 7rem")
                .contains("text-overflow: ellipsis")
                .contains("white-space: nowrap")
                .contains("overscroll-behavior: contain");
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = TaxonomyOverlayContractTest.class.getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
