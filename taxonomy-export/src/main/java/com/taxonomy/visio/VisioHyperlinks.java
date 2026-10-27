package com.taxonomy.visio;

import java.net.URI;

/** The only external native link allowed by the export profile. Never evaluated as a formula. */
public final class VisioHyperlinks {
    private VisioHyperlinks() { }

    public static void validateRequirementUrl(VisioProperty value) {
        try {
            URI uri = URI.create(value.value());
            if (value.kind() != VisioProperty.Kind.STRING || !uri.isAbsolute()
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.normalize().equals(uri)
                    || value.value().codePoints().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))) {
                throw new IllegalArgumentException("Unsafe Visio requirement URL");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsafe Visio requirement URL", exception);
        }
    }
}
