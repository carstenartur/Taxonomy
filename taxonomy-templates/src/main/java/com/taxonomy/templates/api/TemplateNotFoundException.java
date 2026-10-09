package com.taxonomy.templates.api;

import java.io.IOException;

/** Public, repository-independent template contract. */
public final class TemplateNotFoundException extends IOException {
    public TemplateNotFoundException(String templateId, String revision) {
        super("Document template not found"
                + (templateId == null || templateId.isBlank()
                ? ""
                : ": " + templateId)
                + (revision == null || revision.isBlank()
                ? ""
                : " at " + revision));
    }
}
