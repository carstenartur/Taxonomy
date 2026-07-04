package com.taxonomy.architecture.report;

import java.io.Serializable;

/**
 * Serializable descriptor for a report renderer format.
 *
 * @param id unique format ID (e.g. markdown, html, docx, json)
 * @param displayName human-readable format name
 * @param fileExtension output file extension without dot
 * @param contentType HTTP content type used for downloads
 * @param binary whether output is binary
 */
public record ReportFormatDescriptor(
        String id,
        String displayName,
        String fileExtension,
        String contentType,
        boolean binary) implements Serializable {
}
