package com.taxonomy.architecture.decision;

import com.taxonomy.extension.api.report.ReportRenderResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionReportTemplateHeadersTest {

    private static final DecisionReportTemplateProvenance PROVENANCE =
            new DecisionReportTemplateProvenance(
                    "decision-rationale-report",
                    "0123456789abcdef0123456789abcdef01234567",
                    "b".repeat(64),
                    1);

    @Test
    void exposesOnlyCompleteValidatedTemplateProvenance() {
        Map<String, String> metadata = new LinkedHashMap<>(
                PROVENANCE.artifactMetadata());
        metadata.put("untrusted.header", "must-not-be-forwarded");
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();

        DecisionReportTemplateHeaders.apply(
                builder, new ReportRenderResult(new byte[] {1}, metadata));
        ResponseEntity<Void> response = builder.build();

        assertThat(response.getHeaders().getFirst(
                DecisionReportTemplateHeaders.HEADER_TEMPLATE_ID))
                .isEqualTo(PROVENANCE.templateId());
        assertThat(response.getHeaders().getFirst(
                DecisionReportTemplateHeaders.HEADER_TEMPLATE_COMMIT))
                .isEqualTo(PROVENANCE.commitId());
        assertThat(response.getHeaders().getFirst(
                DecisionReportTemplateHeaders.HEADER_TEMPLATE_SHA256))
                .isEqualTo(PROVENANCE.packageSha256());
        assertThat(response.getHeaders().getFirst(
                DecisionReportTemplateHeaders.HEADER_TEMPLATE_SCHEMA_VERSION))
                .isEqualTo("1");
        assertThat(response.getHeaders().getFirst("untrusted.header"))
                .isNull();
    }

    @Test
    void omitsHeadersForFormatsWithoutAWordTemplate() {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();

        DecisionReportTemplateHeaders.apply(
                builder, new ReportRenderResult(new byte[] {1}));

        assertThat(builder.build().getHeaders().isEmpty()).isTrue();
    }

    @Test
    void failsClosedForPartialOrMalformedTemplateMetadata() {
        Map<String, String> partial = Map.of(
                DecisionReportTemplateProvenance.METADATA_TEMPLATE_ID,
                PROVENANCE.templateId());
        assertThatThrownBy(() -> DecisionReportTemplateHeaders.apply(
                ResponseEntity.ok(),
                new ReportRenderResult(new byte[] {1}, partial)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incomplete");

        Map<String, String> malformed = new LinkedHashMap<>(
                PROVENANCE.artifactMetadata());
        malformed.put(
                DecisionReportTemplateProvenance.METADATA_TEMPLATE_COMMIT,
                "not-a-commit");
        assertThatThrownBy(() -> DecisionReportTemplateHeaders.apply(
                ResponseEntity.ok(),
                new ReportRenderResult(new byte[] {1}, malformed)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid");
    }
}
