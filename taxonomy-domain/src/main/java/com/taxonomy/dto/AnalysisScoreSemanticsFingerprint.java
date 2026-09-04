package com.taxonomy.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;

/** Creates a stable SHA-256 digest of the complete typed analysis-score model. */
public final class AnalysisScoreSemanticsFingerprint {

    private static final String VERSION = "analysis-score-semantics-v1";

    private AnalysisScoreSemanticsFingerprint() {
    }

    /**
     * Hashes node identity, score kind, raw evidence, effective relevance and parent evidence.
     * Map iteration order has no effect on the result.
     */
    public static String sha256(Map<String, AnalysisScoreDetail> scoreDetails) {
        MessageDigest digest = sha256Digest();
        update(digest, VERSION);
        if (scoreDetails != null) {
            scoreDetails.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> updateDetail(digest, entry.getKey(), entry.getValue()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Extends an existing analysis digest with the typed score contract. Legacy reports without
     * score details retain their original digest; typed reports receive a deterministic digest
     * that changes whenever any score meaning or evidence changes.
     */
    public static String extend(
            String baseFingerprint,
            Map<String, AnalysisScoreDetail> scoreDetails) {
        if (scoreDetails == null || scoreDetails.isEmpty()) {
            return baseFingerprint;
        }
        MessageDigest digest = sha256Digest();
        update(digest, VERSION);
        update(digest, safe(baseFingerprint));
        update(digest, sha256(scoreDetails));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateDetail(
            MessageDigest digest,
            String mapCode,
            AnalysisScoreDetail detail) {
        String canonical = String.join("\u001f",
                safe(mapCode).strip(),
                safe(detail.nodeCode()),
                detail.kind().name(),
                Integer.toString(detail.rawScore()),
                Integer.toString(detail.effectiveRelevance()),
                safe(detail.parentCode()),
                detail.parentScore() == null ? "" : detail.parentScore().toString());
        update(digest, canonical);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
