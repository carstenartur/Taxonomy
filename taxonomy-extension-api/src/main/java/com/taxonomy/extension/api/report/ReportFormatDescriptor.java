package com.taxonomy.extension.api.report;

import java.io.Serializable;

/** Serializable descriptor for a report renderer format. */
public record ReportFormatDescriptor(
        String id,
        String displayName,
        String fileExtension,
        String contentType,
        boolean binary) implements Serializable {
}
