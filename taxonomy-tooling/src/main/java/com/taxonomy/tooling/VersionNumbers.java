package com.taxonomy.tooling;

import java.util.Objects;
import java.util.regex.Pattern;

final class VersionNumbers {

    static final Pattern RELEASE = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");
    static final Pattern DEVELOPMENT = Pattern.compile(
            "^[0-9]+\\.[0-9]+\\.[0-9]+-SNAPSHOT$");

    private VersionNumbers() {
    }

    static String normalizeRelease(Object value, String field) {
        return normalize(value, field, false, RELEASE, "X.Y.Z");
    }

    static String normalizeDevelopment(
            Object value,
            String field,
            boolean optional) {
        return normalize(value, field, optional, DEVELOPMENT, "X.Y.Z-SNAPSHOT");
    }

    private static String normalize(
            Object value,
            String field,
            boolean optional,
            Pattern pattern,
            String expected) {
        if (value == null && optional) {
            return "";
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        String normalized = text.strip();
        if (optional && normalized.isEmpty()) {
            return "";
        }
        if (normalized.startsWith("${")) {
            throw new IllegalArgumentException(
                    field + " was not supplied. Pass -D" + field
                            + " with an explicit version.");
        }
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must use " + expected + ", got '" + normalized + "'");
        }
        return normalized;
    }

    static SemanticVersion semantic(String version) {
        Objects.requireNonNull(version, "version");
        String normalized = version.endsWith("-SNAPSHOT")
                ? version.substring(0, version.length() - "-SNAPSHOT".length())
                : version;
        String[] components = normalized.split("\\.", -1);
        if (components.length != 3) {
            throw new IllegalArgumentException("Invalid semantic version: " + version);
        }
        try {
            return new SemanticVersion(
                    Integer.parseInt(components[0]),
                    Integer.parseInt(components[1]),
                    Integer.parseInt(components[2]));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Invalid semantic version: " + version, error);
        }
    }

    static void requireNewer(String releaseVersion, String nextVersion) {
        if (nextVersion == null || nextVersion.isBlank()) {
            return;
        }
        if (semantic(nextVersion).compareTo(semantic(releaseVersion)) <= 0) {
            throw new IllegalArgumentException(
                    "next development version " + nextVersion
                            + " must be newer than release " + releaseVersion);
        }
    }

    record SemanticVersion(int major, int minor, int patch)
            implements Comparable<SemanticVersion> {
        @Override
        public int compareTo(SemanticVersion other) {
            int majorOrder = Integer.compare(major, other.major);
            if (majorOrder != 0) {
                return majorOrder;
            }
            int minorOrder = Integer.compare(minor, other.minor);
            if (minorOrder != 0) {
                return minorOrder;
            }
            return Integer.compare(patch, other.patch);
        }
    }
}
