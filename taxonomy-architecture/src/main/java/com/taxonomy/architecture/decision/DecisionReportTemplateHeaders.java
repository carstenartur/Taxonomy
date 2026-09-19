package com.taxonomy.architecture.decision;

import com.taxonomy.extension.api.report.ReportRenderResult;
import org.springframework.http.ResponseEntity;

import java.util.Objects;

/** Allow-listed HTTP representation of exact decision-report template provenance. */
public final class DecisionReportTemplateHeaders {

    public static final String HEADER_TEMPLATE_ID = "X-Taxonomy-Template-Id";
    public static final String HEADER_TEMPLATE_COMMIT =
            "X-Taxonomy-Template-Commit";
    public static final String HEADER_TEMPLATE_SHA256 =
            "X-Taxonomy-Template-SHA256";
    public static final String HEADER_TEMPLATE_SCHEMA_VERSION =
            "X-Taxonomy-Template-Schema-Version";

    private DecisionReportTemplateHeaders() {
    }

    /**
     * Add only the four validated template fields to a response. Other renderer
     * metadata can never become a response header through this boundary.
     */
    public static ResponseEntity.BodyBuilder apply(
            ResponseEntity.BodyBuilder response,
            ReportRenderResult rendered) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(rendered, "rendered");
        DecisionReportTemplateProvenance.fromArtifactMetadata(
                        rendered.artifactMetadata())
                .ifPresent(provenance -> response
                        .header(HEADER_TEMPLATE_ID, provenance.templateId())
                        .header(HEADER_TEMPLATE_COMMIT, provenance.commitId())
                        .header(HEADER_TEMPLATE_SHA256, provenance.packageSha256())
                        .header(
                                HEADER_TEMPLATE_SCHEMA_VERSION,
                                Integer.toString(provenance.schemaVersion())));
        return response;
    }
}
