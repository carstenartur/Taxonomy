package com.taxonomy.build;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Evaluates the packaged CycloneDX dependency set against the PDF/export
 * dependency contract.
 *
 * <p>Maven Enforcer rejects known forbidden dependency families before the
 * reactor is built. This post-package policy independently inspects the actual
 * aggregate SBOM, proves the packaged PDFBox line, rejects historical converter
 * families, and publishes stable human-readable evidence.</p>
 */
final class PackagedDependencyHygienePolicy {

    private static final String PDFBOX_GROUP = "org.apache.pdfbox";
    private static final String PDFBOX_ARTIFACT = "pdfbox";
    private static final String LEGACY_XMPBOX = "xmpbox";
    private static final String FLEXMARK_GROUP = "com.vladsch.flexmark";
    private static final String FLEXMARK_PDF_CONVERTER = "flexmark-pdf-converter";
    private static final String OPENHTML_GROUP = "com.openhtmltopdf";

    private final ObjectMapper objectMapper = new ObjectMapper();

    Inspection inspect(Path sbomPath, String expectedPdfboxVersion) {
        if (expectedPdfboxVersion == null || expectedPdfboxVersion.isBlank()) {
            throw new IllegalArgumentException("Expected PDFBox version must not be blank");
        }
        Path sbom = sbomPath.toAbsolutePath().normalize();
        JsonNode root = readSbom(sbom);
        JsonNode components = root.path("components");
        if (!components.isArray()) {
            throw new IllegalArgumentException(
                    "CycloneDX SBOM must contain a components array: " + sbom);
        }

        Map<Coordinate, Set<String>> versions = new TreeMap<>();
        for (JsonNode component : components) {
            Coordinate coordinate = coordinate(component);
            if (coordinate == null) {
                continue;
            }
            String version = requiredText(component, "version", coordinate.toString());
            versions.computeIfAbsent(coordinate, ignored -> new TreeSet<>())
                    .add(version);
        }

        List<String> violations = new ArrayList<>();
        Coordinate pdfbox = new Coordinate(PDFBOX_GROUP, PDFBOX_ARTIFACT);
        Set<String> rootPdfboxVersions = versions.getOrDefault(pdfbox, Set.of());
        if (rootPdfboxVersions.isEmpty()) {
            violations.add("Packaged SBOM does not contain org.apache.pdfbox:pdfbox");
        }

        versions.forEach((coordinate, resolvedVersions) -> {
            if (PDFBOX_GROUP.equals(coordinate.group())) {
                if (LEGACY_XMPBOX.equals(coordinate.artifact())) {
                    violations.add("Legacy org.apache.pdfbox:xmpbox is packaged at "
                            + formatVersions(resolvedVersions));
                }
                if (!resolvedVersions.equals(Set.of(expectedPdfboxVersion))) {
                    violations.add(coordinate + " resolved to "
                            + formatVersions(resolvedVersions)
                            + ", expected only " + expectedPdfboxVersion);
                }
            }
            if (FLEXMARK_GROUP.equals(coordinate.group())
                    && FLEXMARK_PDF_CONVERTER.equals(coordinate.artifact())) {
                violations.add("Forbidden flexmark PDF converter is packaged at "
                        + formatVersions(resolvedVersions));
            }
            if (OPENHTML_GROUP.equals(coordinate.group())
                    && coordinate.artifact().toLowerCase(Locale.ROOT).contains("pdfbox")) {
                violations.add("Forbidden OpenHTML PDFBox adapter " + coordinate
                        + " is packaged at " + formatVersions(resolvedVersions));
            }
        });

        Map<Coordinate, Set<String>> relevant = new LinkedHashMap<>();
        versions.forEach((coordinate, resolvedVersions) -> {
            if (PDFBOX_GROUP.equals(coordinate.group())
                    || (FLEXMARK_GROUP.equals(coordinate.group())
                        && FLEXMARK_PDF_CONVERTER.equals(coordinate.artifact()))
                    || (OPENHTML_GROUP.equals(coordinate.group())
                        && coordinate.artifact().toLowerCase(Locale.ROOT)
                            .contains("pdfbox"))) {
                relevant.put(coordinate, Collections.unmodifiableSet(
                        new LinkedHashSet<>(resolvedVersions)));
            }
        });

        return new Inspection(
                violations.isEmpty(),
                expectedPdfboxVersion.strip(),
                Collections.unmodifiableMap(relevant),
                List.copyOf(violations));
    }

    String report(Path sbomPath, Inspection inspection) {
        StringBuilder report = new StringBuilder()
                .append("Packaged dependency hygiene\n\n")
                .append("Source: ").append(sbomPath.toAbsolutePath().normalize()).append('\n')
                .append("Expected PDFBox version: ")
                .append(inspection.expectedPdfboxVersion()).append("\n\n")
                .append("Relevant packaged components:\n");
        if (inspection.relevantComponents().isEmpty()) {
            report.append("- none\n");
        } else {
            inspection.relevantComponents().forEach((coordinate, versions) ->
                    report.append("- ").append(coordinate).append(" = ")
                            .append(formatVersions(versions)).append('\n'));
        }
        report.append('\n').append("Result: ")
                .append(inspection.passed() ? "PASS" : "FAIL").append('\n');
        inspection.violations().forEach(violation ->
                report.append("- ").append(violation).append('\n'));
        return report.toString();
    }

    void writeReport(Path output, String report) {
        try {
            Path absolute = output.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            Files.writeString(absolute, report, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Cannot write packaged dependency hygiene report " + output,
                    error);
        }
    }

    private JsonNode readSbom(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Packaged SBOM not found: " + path);
        }
        try {
            return objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException(
                    "Cannot read packaged SBOM " + path + ": " + error.getMessage(),
                    error);
        }
    }

    private static Coordinate coordinate(JsonNode component) {
        String group = optionalText(component, "group");
        String artifact = optionalText(component, "name");
        if (group != null && artifact != null) {
            return new Coordinate(group, artifact);
        }

        String purl = optionalText(component, "purl");
        if (purl == null || !purl.startsWith("pkg:maven/")) {
            return null;
        }
        String identity = purl.substring("pkg:maven/".length());
        int at = identity.indexOf('@');
        if (at >= 0) {
            identity = identity.substring(0, at);
        }
        int query = identity.indexOf('?');
        if (query >= 0) {
            identity = identity.substring(0, query);
        }
        String[] parts = identity.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        return new Coordinate(parts[0], parts[1]);
    }

    private static String requiredText(
            JsonNode node,
            String field,
            String component) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new IllegalArgumentException(
                    "CycloneDX component " + component
                            + " has no non-blank " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().strip();
    }

    private static String formatVersions(Collection<String> versions) {
        return String.join(", ", new TreeSet<>(versions));
    }

    record Inspection(
            boolean passed,
            String expectedPdfboxVersion,
            Map<Coordinate, Set<String>> relevantComponents,
            List<String> violations) {
    }

    record Coordinate(String group, String artifact)
            implements Comparable<Coordinate> {
        Coordinate {
            if (group == null || group.isBlank()
                    || artifact == null || artifact.isBlank()) {
                throw new IllegalArgumentException(
                        "Dependency coordinate must contain group and artifact");
            }
            group = group.strip();
            artifact = artifact.strip();
        }

        @Override
        public int compareTo(Coordinate other) {
            int groupOrder = group.compareTo(other.group);
            return groupOrder != 0
                    ? groupOrder : artifact.compareTo(other.artifact);
        }

        @Override
        public String toString() {
            return group + ":" + artifact;
        }
    }
}
