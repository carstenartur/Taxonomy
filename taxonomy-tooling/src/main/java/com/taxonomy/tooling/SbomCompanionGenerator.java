package com.taxonomy.tooling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Generates a CycloneDX SBOM companion without vulnerability or exploitability
 * assertions. An empty vulnerabilities array explicitly means not assessed.
 */
public final class SbomCompanionGenerator {

    static final String GENERATOR_NAME = "taxonomy-sbom-companion-generator";
    static final String GENERATOR_VERSION = "2.0.0";
    static final String ASSESSMENT_POLICY =
            "No vulnerability scan or exploitability assessment is represented "
                    + "by this document. An empty vulnerabilities array is not "
                    + "evidence that the release has no known or exploitable "
                    + "vulnerabilities.";

    private SbomCompanionGenerator() {
    }

    public static Result generate(
            Path sbomPath,
            Path outputPath,
            Instant timestamp,
            UUID serialNumber) throws IOException {
        return generate(
                sbomPath,
                outputPath,
                timestamp,
                serialNumber,
                SbomCompanionGenerator::writeAtomically);
    }

    static Result generate(
            Path sbomPath,
            Path outputPath,
            Instant timestamp,
            UUID serialNumber,
            CompanionWriter writer) throws IOException {
        Path sbom = sbomPath.toAbsolutePath().normalize();
        Path output = outputPath.toAbsolutePath().normalize();
        if (sbom.equals(output)) {
            throw new IllegalArgumentException(
                    "SBOM companion output must differ from the source SBOM");
        }

        try {
            Instant generatedAt = Objects.requireNonNull(
                    timestamp, "timestamp").truncatedTo(ChronoUnit.SECONDS);
            UUID companionId = Objects.requireNonNull(
                    serialNumber, "serialNumber");
            CompanionWriter effectiveWriter = Objects.requireNonNull(
                    writer, "writer");
            if (!Files.isRegularFile(sbom)) {
                throw new IllegalArgumentException(
                        "CycloneDX SBOM file is missing: " + sbom);
            }

            Map<String, Object> source = FlatJson.parseObject(
                    Files.readString(sbom, StandardCharsets.UTF_8));
            String sourceSerial = scalarText(
                    source.get("serialNumber"), "unknown", "serialNumber");
            String sourceVersion = scalarText(
                    source.get("version"), "1", "version");
            int componentCount = componentCount(source.get("components"));

            LinkedHashMap<String, Object> companion = new LinkedHashMap<>();
            companion.put("bomFormat", "CycloneDX");
            companion.put("specVersion", "1.6");
            companion.put("version", 1L);
            companion.put("serialNumber", "urn:uuid:" + companionId);
            companion.put("metadata", metadata(
                    generatedAt, sourceSerial, sourceVersion));
            companion.put("vulnerabilities", List.of());

            effectiveWriter.write(
                    output, FlatJson.pretty(companion) + "\n");
            return new Result(
                    output,
                    generatedAt,
                    companionId,
                    sourceSerial,
                    sourceVersion,
                    componentCount);
        } catch (IOException | RuntimeException failure) {
            deleteStaleOutput(output, failure);
            throw failure;
        }
    }

    private static Map<String, Object> metadata(
            Instant timestamp,
            String sourceSerial,
            String sourceVersion) {
        LinkedHashMap<String, Object> tools = new LinkedHashMap<>();
        tools.put("components", List.of(toolComponent()));

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timestamp", timestamp.toString());
        metadata.put("tools", tools);
        metadata.put("component", applicationComponent());
        metadata.put("properties", List.of(
                property("taxonomy:sbom-ref", sourceSerial),
                property("taxonomy:sbom-version", sourceVersion),
                property("vex:assessment-status", "not-assessed"),
                property("vex:policy", ASSESSMENT_POLICY)));
        return metadata;
    }

    private static Map<String, Object> toolComponent() {
        LinkedHashMap<String, Object> component = new LinkedHashMap<>();
        component.put("type", "application");
        component.put("name", GENERATOR_NAME);
        component.put("version", GENERATOR_VERSION);
        component.put(
                "description",
                "Links Taxonomy release metadata to its SBOM without making "
                        + "vulnerability or exploitability assertions");
        return component;
    }

    private static Map<String, Object> applicationComponent() {
        LinkedHashMap<String, Object> supplier = new LinkedHashMap<>();
        supplier.put("name", "Carsten Hammer");
        supplier.put("url", List.of("https://github.com/carstenartur"));

        LinkedHashMap<String, Object> component = new LinkedHashMap<>();
        component.put("type", "application");
        component.put("name", "Taxonomy Architecture Analyzer");
        component.put("bom-ref", "taxonomy-app");
        component.put("purl", "pkg:github/carstenartur/Taxonomy");
        component.put("supplier", supplier);
        return component;
    }

    private static Map<String, Object> property(String name, String value) {
        LinkedHashMap<String, Object> property = new LinkedHashMap<>();
        property.put("name", name);
        property.put("value", value);
        return property;
    }

    private static String scalarText(
            Object value,
            String fallback,
            String field) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new IllegalArgumentException(
                "CycloneDX SBOM " + field + " must be a scalar value");
    }

    private static int componentCount(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof List<?> components) {
            return components.size();
        }
        throw new IllegalArgumentException(
                "CycloneDX SBOM components must be an array");
    }

    private static void deleteStaleOutput(Path output, Throwable failure) {
        try {
            Files.deleteIfExists(output);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void writeAtomically(Path output, String content)
            throws IOException {
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "SBOM companion output has no parent directory: " + output);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(
                parent, ".taxonomy-sbom-", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(
                        temporary,
                        output,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    @FunctionalInterface
    interface CompanionWriter {
        void write(Path output, String content) throws IOException;
    }

    public record Result(
            Path output,
            Instant timestamp,
            UUID serialNumber,
            String sourceSerialNumber,
            String sourceVersion,
            int componentCount) {
    }
}
