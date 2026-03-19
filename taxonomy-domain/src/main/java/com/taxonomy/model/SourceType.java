package com.taxonomy.model;

/**
 * Identifies the type of source material from which a requirement originates.
 *
 * <p>Every requirement must be traceable to its source.  Even a manually entered
 * requirement should have a source artifact such as {@link #MANUAL_ENTRY} or
 * {@link #BUSINESS_REQUEST}.
 */
public enum SourceType {

    /** Free-form business requirement entered by a user. */
    BUSINESS_REQUEST,

    /** Administrative or legal regulation (Verwaltungsvorschrift). */
    REGULATION,

    /** Entry from the German Federal Information Management (FIM) catalogue. */
    FIM_ENTRY,

    /** File uploaded directly (PDF, DOCX, etc.). */
    UPLOADED_DOCUMENT,

    /** Requirement derived from e-mail correspondence. */
    EMAIL,

    /** Requirement captured during a meeting. */
    MEETING_NOTE,

    /** Requirement derived from a web resource or URL. */
    WEB_RESOURCE,

    /** Requirement created manually in the system. */
    MANUAL_ENTRY,

    /** Requirement imported from a legacy system. */
    LEGACY_IMPORT
}
