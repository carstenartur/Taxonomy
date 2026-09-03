package com.taxonomy.export.artifact;

/** Shared validation for identity-bearing artifact metadata. */
final class ArchitectureArtifactText {

    private ArchitectureArtifactText() {
    }

    static String requireSafeText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.codePoints()
                .anyMatch(codePoint -> Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException(
                    field + " contains ISO control characters");
        }
        return normalized;
    }
}
