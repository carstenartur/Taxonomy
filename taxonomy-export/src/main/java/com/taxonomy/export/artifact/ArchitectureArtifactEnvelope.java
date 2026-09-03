package com.taxonomy.export.artifact;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministic envelope binding an export payload to one immutable snapshot and
 * one explicit loss profile.
 */
public record ArchitectureArtifactEnvelope(
        String schemaVersion,
        String artifactId,
        ArchitectureArtifactFormat format,
        ArchitectureArtifactSource source,
        String mediaType,
        String fileName,
        String payloadSha256,
        ArchitectureArtifactLossManifest lossManifest,
        String payload) {

    public static final String SCHEMA_VERSION = "1.0";

    private static final Pattern SHA256 =
            Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SAFE_FILE_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public ArchitectureArtifactEnvelope {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported schemaVersion");
        }
        artifactId = requireSha256(artifactId, "artifactId");
        format = Objects.requireNonNull(format, "format");
        source = Objects.requireNonNull(source, "source");
        if (!format.mediaType().equals(mediaType)) {
            throw new IllegalArgumentException(
                    "mediaType does not match format " + format);
        }
        fileName = requireFileName(fileName, format);
        payloadSha256 = requireSha256(payloadSha256, "payloadSha256");
        lossManifest = Objects.requireNonNull(lossManifest, "lossManifest");
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
        String expectedPayloadSha256 =
                ArchitectureArtifactEnvelopeFactory.payloadSha256(payload);
        if (!payloadSha256.equals(expectedPayloadSha256)) {
            throw new IllegalArgumentException(
                    "payloadSha256 does not match payload");
        }
        String expectedArtifactId =
                ArchitectureArtifactEnvelopeFactory.artifactId(
                        format,
                        source,
                        fileName,
                        payloadSha256,
                        lossManifest);
        if (!artifactId.equals(expectedArtifactId)) {
            throw new IllegalArgumentException(
                    "artifactId does not match envelope identity");
        }
    }

    static String requireFileName(
            String fileName,
            ArchitectureArtifactFormat format) {
        if (fileName == null
                || !SAFE_FILE_NAME.matcher(fileName).matches()
                || !fileName.endsWith(format.fileSuffix())) {
            throw new IllegalArgumentException(
                    "Unsafe or format-incompatible fileName");
        }
        return fileName;
    }

    private static String requireSha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a lowercase SHA-256 value");
        }
        return value;
    }
}
