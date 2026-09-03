package com.taxonomy.export.artifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Creates deterministic snapshot-bound architecture artifact envelopes. */
public final class ArchitectureArtifactEnvelopeFactory {

    private static final String IDENTITY_VERSION =
            "taxonomy-architecture-artifact-envelope-v1";

    public ArchitectureArtifactEnvelope create(
            ArchitectureArtifactFormat format,
            ArchitectureArtifactSource source,
            String payload,
            ArchitectureArtifactLossManifest lossManifest) {
        Objects.requireNonNull(format, "format");
        return create(
                format,
                source,
                format.defaultFileName(),
                payload,
                lossManifest);
    }

    public ArchitectureArtifactEnvelope create(
            ArchitectureArtifactFormat format,
            ArchitectureArtifactSource source,
            String fileName,
            String payload,
            ArchitectureArtifactLossManifest lossManifest) {
        format = Objects.requireNonNull(format, "format");
        source = Objects.requireNonNull(source, "source");
        fileName = ArchitectureArtifactEnvelope.requireFileName(
                fileName,
                format);
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
        lossManifest = Objects.requireNonNull(lossManifest, "lossManifest");

        String payloadSha256 = payloadSha256(payload);
        String artifactId = artifactId(
                format,
                source,
                fileName,
                payloadSha256,
                lossManifest);

        return new ArchitectureArtifactEnvelope(
                ArchitectureArtifactEnvelope.SCHEMA_VERSION,
                artifactId,
                format,
                source,
                format.mediaType(),
                fileName,
                payloadSha256,
                lossManifest,
                payload);
    }

    static String artifactId(
            ArchitectureArtifactFormat format,
            ArchitectureArtifactSource source,
            String fileName,
            String payloadSha256,
            ArchitectureArtifactLossManifest lossManifest) {
        MessageDigest digest = sha256Digest();
        putText(digest, IDENTITY_VERSION);
        putText(digest, ArchitectureArtifactEnvelope.SCHEMA_VERSION);
        putText(digest, format.name());
        putText(digest, source.snapshotId());
        putText(digest, source.snapshotRevision());
        putText(digest, source.workspaceId());
        putText(digest, source.branchName());
        putText(digest, format.mediaType());
        putText(digest, fileName);
        putText(digest, payloadSha256);
        putText(digest, lossManifest.profileVersion());
        putInt(digest, lossManifest.losses().size());
        for (ArchitectureArtifactLoss loss : lossManifest.losses()) {
            putText(digest, loss.code());
            putText(digest, loss.sourcePath());
            putText(digest, loss.disposition().name());
            putText(digest, loss.detail());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String payloadSha256(String payload) {
        return HexFormat.of().formatHex(
                sha256Digest().digest(
                        payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
        }
    }

    private static void putText(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        putInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void putInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
