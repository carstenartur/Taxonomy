package com.taxonomy.templates.api;

/** Optional preview capability that renders the supplied validated, immutable template. */
public interface RevisionPinnedTemplateReportPreview extends DocumentTemplateReportPreview {

    byte[] renderPreview(TemplateFile template);
}
