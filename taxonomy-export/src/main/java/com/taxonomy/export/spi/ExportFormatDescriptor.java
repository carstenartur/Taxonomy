package com.taxonomy.export.spi;

import java.io.Serializable;

/**
 * Serializable descriptor for a diagram export format.
 *
 * @param id unique stable format ID
 * @param displayName human-readable format name
 * @param fileExtension output extension without a leading dot
 * @param contentType HTTP media type used for downloads
 * @param binary whether the result is binary rather than text
 */
public record ExportFormatDescriptor(
        String id,
        String displayName,
        String fileExtension,
        String contentType,
        boolean binary) implements Serializable {
}
