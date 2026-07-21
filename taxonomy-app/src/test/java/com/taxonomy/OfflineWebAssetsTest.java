package com.taxonomy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Ensures that the primary browser application can be rendered without public CDNs. */
class OfflineWebAssetsTest {

    @Test
    void indexUsesOnlyPackagedBrowserLibraries() throws IOException {
        String html;
        try (var stream = getClass().getResourceAsStream("/templates/index.html")) {
            assertThat(stream).as("index.html must be available on the classpath").isNotNull();
            html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(html)
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("cdnjs.cloudflare.com")
                .doesNotContain("unpkg.com")
                .contains("/webjars/bootstrap/5.3.2/dist/css/bootstrap.min.css")
                .contains("/webjars/bootstrap/5.3.2/dist/js/bootstrap.bundle.min.js")
                .contains("/webjars/d3/7.9.0/dist/d3.min.js")
                .contains("/webjars/jspdf/4.2.0/dist/jspdf.umd.min.js")
                .contains("/webjars/svg2pdf.js/2.2.0/dist/svg2pdf.umd.min.js")
                .contains("/css/taxonomy-ergonomics.css");
    }
}
