package com.taxonomy.architecture.decision;

import com.taxonomy.templates.DocumentTemplateService.TemplateFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Exact render-time identity of the Word template used for one decision report. */
public record DecisionReportTemplateProvenance(
        String templateId,
        String commitId,
        String packageSha256,
        int schemaVersion) {

    public static final String METADATA_TEMPLATE_ID = "taxonomy.template.id";
    public static final String METADATA_TEMPLATE_COMMIT = "taxonomy.template.commit";
    public static final String METADATA_TEMPLATE_SHA256 = "taxonomy.template.sha256";
    public static final String METADATA_TEMPLATE_SCHEMA_VERSION =
            "taxonomy.template.schema-version";

    private static final Pattern TEMPLATE_ID =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,79}");
    private static final Pattern COMMIT_ID = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final List<String> METADATA_KEYS = List.of(
            METADATA_TEMPLATE_ID,
            METADATA_TEMPLATE_COMMIT,
            METADATA_TEMPLATE_SHA256,
            METADATA_TEMPLATE_SCHEMA_VERSION);

    public DecisionReportTemplateProvenance {
        templateId = requireMatch(templateId, TEMPLATE_ID, "template ID");
        commitId = requireMatch(commitId, COMMIT_ID, "template commit");
        packageSha256 = requireMatch(
                packageSha256, SHA256, "template package SHA-256");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException(
                    "Template schema version must be positive");
        }
    }

    public static DecisionReportTemplateProvenance from(TemplateFile template) {
        Objects.requireNonNull(template, "template");
        return new DecisionReportTemplateProvenance(
                template.manifest().templateId(),
                template.commitId(),
                template.manifest().packageSha256(),
                template.manifest().schemaVersion());
    }

    public Map<String, String> artifactMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_TEMPLATE_ID, templateId);
        metadata.put(METADATA_TEMPLATE_COMMIT, commitId);
        metadata.put(METADATA_TEMPLATE_SHA256, packageSha256);
        metadata.put(
                METADATA_TEMPLATE_SCHEMA_VERSION,
                Integer.toString(schemaVersion));
        return Map.copyOf(metadata);
    }

    /**
     * Parse the complete allow-listed template identity from renderer metadata.
     * No matching key means that the rendered format did not use a Word template;
     * a partial or malformed identity fails closed.
     */
    public static Optional<DecisionReportTemplateProvenance> fromArtifactMetadata(
            Map<String, String> metadata) {
        Objects.requireNonNull(metadata, "metadata");
        long present = METADATA_KEYS.stream().filter(metadata::containsKey).count();
        if (present == 0) {
            return Optional.empty();
        }
        if (present != METADATA_KEYS.size()) {
            throw new IllegalStateException(
                    "Incomplete decision-report template provenance metadata");
        }
        try {
            return Optional.of(new DecisionReportTemplateProvenance(
                    metadata.get(METADATA_TEMPLATE_ID),
                    metadata.get(METADATA_TEMPLATE_COMMIT),
                    metadata.get(METADATA_TEMPLATE_SHA256),
                    Integer.parseInt(
                            metadata.get(METADATA_TEMPLATE_SCHEMA_VERSION))));
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Invalid decision-report template provenance metadata",
                    exception);
        }
    }

    private static String requireMatch(
            String value,
            Pattern pattern,
            String label) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return value;
    }
}
