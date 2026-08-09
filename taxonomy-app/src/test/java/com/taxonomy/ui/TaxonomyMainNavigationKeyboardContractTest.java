package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TaxonomyMainNavigationKeyboardContractTest {

    @Test
    void arrowActivationSynchronizesAriaBeforeReturning() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/resources/static/js/shared/taxonomy-utils.js"));
        String handler = between(source,
                "    function installMainNavigationKeyboardSupport() {",
                "    // ── Discoverable responsive primary navigation");

        int click = handler.indexOf("tabs[next].click();");
        int ariaSync = handler.indexOf("syncMainNavigation();", click);
        int responsiveSync = handler.indexOf("syncResponsiveMainNavigation();", click);
        assertThat(click).isGreaterThanOrEqualTo(0);
        assertThat(ariaSync).isGreaterThan(click);
        assertThat(responsiveSync).isGreaterThan(ariaSync);
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(startIndex).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
