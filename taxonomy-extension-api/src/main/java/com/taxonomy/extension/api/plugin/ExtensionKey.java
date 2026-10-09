package com.taxonomy.extension.api.plugin;

import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

/** Case-insensitive extension identity within its kind. Report families own their formats. */
public record ExtensionKey(ExtensionKind kind, String id) implements Serializable, Comparable<ExtensionKey> {
    public ExtensionKey {
        Objects.requireNonNull(kind, "kind");
        if (id == null || id.isBlank() || id.length() > 257)
            throw new IllegalArgumentException("Extension ID must not be blank or exceed 257 characters");
        id = id.trim().toLowerCase(Locale.ROOT);
    }
    public static ExtensionKey report(String family, String format) {
        String f = reportPart(family, "report type ID"), t = reportPart(format, "report format ID");
        return new ExtensionKey(ExtensionKind.REPORT_RENDERER,
                ReportRendererExtension.DEFAULT_REPORT_TYPE_ID.equals(f) ? t : f + ":" + t);
    }
    private static String reportPart(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        if (value.contains(":")) throw new IllegalArgumentException(name + " must not contain ':'");
        return value.trim().toLowerCase(Locale.ROOT);
    }
    @Override public int compareTo(ExtensionKey other) {
        int compared = kind.compareTo(other.kind);
        return compared == 0 ? id.compareTo(other.id) : compared;
    }
}
