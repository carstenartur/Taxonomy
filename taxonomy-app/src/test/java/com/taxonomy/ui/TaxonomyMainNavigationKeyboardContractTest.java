package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class TaxonomyMainNavigationKeyboardContractTest {

    @Test
    void arrowActivationSynchronizesAriaBeforeReturning() throws Exception {
        String source;
        try (InputStream input = Objects.requireNonNull(
                getClass().getResourceAsStream(
                        "/static/js/shared/taxonomy-utils.js"),
                "taxonomy-utils.js classpath resource")) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String handler = between(source,
                "function installMainNavigationKeyboardSupport() {",
                "// ── Discoverable responsive primary navigation");

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
