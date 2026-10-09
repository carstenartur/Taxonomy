package com.taxonomy.templates.api;

import java.util.Objects;

/** A feature owns its seed, semantic validation and optional preview. */
public record TemplateContribution(String templateId, String displayName,
        TemplateContent content, DocumentTemplateContract contract, boolean required,
        DocumentTemplateReportPreview preview, String previewFileName) {

    public TemplateContribution(String templateId, String displayName, TemplateContent content,
            DocumentTemplateContract contract, boolean required) {
        this(templateId, displayName, content, contract, required, null, null);
    }

    public TemplateContribution {
        if (templateId == null || !templateId.matches("[a-z0-9][a-z0-9._-]{0,79}")) {
            throw new IllegalArgumentException("Template ID must match [a-z0-9][a-z0-9._-]{0,79}");
        }
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(contract, "contract");
        if (!templateId.equals(contract.templateId())) {
            throw new IllegalArgumentException("Template contribution and contract IDs must match");
        }
        if (preview != null && (previewFileName == null
                || !previewFileName.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.docx"))) {
            throw new IllegalArgumentException("A preview requires a safe DOCX download filename");
        }
        if (preview == null && previewFileName != null) {
            throw new IllegalArgumentException("A preview filename requires a preview");
        }
    }
}
