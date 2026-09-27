package com.taxonomy.extension.api.report;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportRenderResultTest {

    @Test
    void defensivelyCopiesContentAndArtifactMetadata() {
        byte[] content = {1, 2, 3};
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("artifact.identity", "exact");

        ReportRenderResult result = new ReportRenderResult(content, metadata);
        content[0] = 9;
        metadata.put("artifact.identity", "changed");

        assertThat(result.bytes()).containsExactly(1, 2, 3);
        assertThat(result.artifactMetadata())
                .containsExactly(Map.entry("artifact.identity", "exact"));

        byte[] accessorCopy = result.content();
        accessorCopy[0] = 8;
        assertThat(result.bytes()).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> result.artifactMetadata().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void oneArgumentConstructorRetainsTheOriginalEmptyMetadataContract() {
        ReportRenderResult result = new ReportRenderResult(new byte[] {4, 5});

        assertThat(result.bytes()).containsExactly(4, 5);
        assertThat(result.artifactMetadata()).isEmpty();
    }
}
