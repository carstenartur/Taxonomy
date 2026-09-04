package com.taxonomy.export.artifact;

/** Shared validation for identity-bearing artifact metadata. */
final class ArchitectureArtifactText {

    private ArchitectureArtifactText() {
    }

    static String requireSafeText(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.codePoints()
                .anyMatch(codePoint -> Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException(
                    field + " contains ISO control characters");
        }
        String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
