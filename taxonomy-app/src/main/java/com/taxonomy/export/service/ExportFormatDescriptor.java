package com.taxonomy.export.service;

import java.io.Serializable;

/**
 * Serializable descriptor for a diagram export format.
 *
 * @param id          unique format ID (e.g. {@code mermaid}, {@code archimate}, {@code visio})
 * @param displayName human-readable format name
 * @param fileExtension output file extension without dot (e.g. {@code mmd}, {@code xml})
 * @param contentType HTTP content type used for downloads
 * @param binary      {@code true} if the output is binary (e.g. Visio); {@code false} for text
 */
public record ExportFormatDescriptor(
        String id,
        String displayName,
        String fileExtension,
        String contentType,
        boolean binary) implements Serializable {
}
