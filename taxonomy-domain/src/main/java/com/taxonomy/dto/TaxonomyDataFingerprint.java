package com.taxonomy.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Stable fingerprint of the hierarchy fields that determine analysis-score semantics. */
public final class TaxonomyDataFingerprint {

    private static final String VERSION = "taxonomy-data-v2-score-semantics";

    private TaxonomyDataFingerprint() {
    }

    /**
     * Includes parent identity and {@code analysisRole} in addition to the historical catalogue
     * fields. Thus a category becoming a concrete PRODUCT, or moving to another family, changes
     * the fingerprint even when its title and description remain unchanged.
     */
    public static String sha256(List<TaxonomyNodeDto> roots) {
        MessageDigest digest = sha256Digest();
        update(digest, VERSION);
        evidence(roots).forEach(node -> update(digest, node.semanticLine()));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Reproduces the pre-score-semantics catalogue digest so historical snapshots remain
     * verifiable after the stronger fingerprint algorithm is introduced.
     */
    public static String legacySha256(List<TaxonomyNodeDto> roots) {
        StringBuilder canonical = new StringBuilder();
        evidence(roots).forEach(node -> canonical.append(node.legacyLine()));
        return rawSha256(canonical.toString());
    }

    public static boolean matchesRecorded(
            String recordedFingerprint,
            List<TaxonomyNodeDto> roots) {
        if (recordedFingerprint == null || recordedFingerprint.isBlank()) {
            return false;
        }
        String normalized = recordedFingerprint.strip();
        return normalized.equalsIgnoreCase(sha256(roots))
                || normalized.equalsIgnoreCase(legacySha256(roots));
    }

    private static List<NodeEvidence> evidence(List<TaxonomyNodeDto> roots) {
        Map<String, NodeEvidence> byCode = new LinkedHashMap<>();
        Set<TaxonomyNodeDto> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (roots != null) {
            for (TaxonomyNodeDto root : roots) {
                collect(root, null, byCode, visited);
            }
        }
        return byCode.values().stream()
                .sorted(Comparator.comparing(NodeEvidence::code))
                .toList();
    }

    private static void collect(
            TaxonomyNodeDto node,
            String inheritedParentCode,
            Map<String, NodeEvidence> byCode,
            Set<TaxonomyNodeDto> visited) {
        if (node == null || !visited.add(node)
                || node.getCode() == null || node.getCode().isBlank()) {
            return;
        }
        String code = node.getCode().strip();
        NodeEvidence current = new NodeEvidence(
                code,
                safe(node.getNameEn()),
                safe(node.getDescriptionEn()),
                safe(node.getTaxonomyRoot()),
                node.getLevel(),
                safe(firstNonBlank(node.getParentCode(), inheritedParentCode)),
                normalizedRole(node.getAnalysisRole()));
        NodeEvidence previous = byCode.putIfAbsent(code, current);
        if (previous != null && !previous.equals(current)) {
            throw new IllegalArgumentException(
                    "Taxonomy tree contains conflicting definitions for node " + code);
        }

        List<TaxonomyNodeDto> children = node.getChildren() == null
                ? List.of() : new ArrayList<>(node.getChildren());
        children.sort(Comparator.comparing(
                child -> child == null || child.getCode() == null ? "" : child.getCode()));
        for (TaxonomyNodeDto child : children) {
            collect(child, code, byCode, visited);
        }
    }

    private static String normalizedRole(String role) {
        return role == null || role.isBlank()
                ? "CATEGORY" : role.strip().toUpperCase(Locale.ROOT);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        return second == null || second.isBlank() ? null : second.strip();
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private static String rawSha256(String value) {
        MessageDigest digest = sha256Digest();
        return HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8)));
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

    private record NodeEvidence(
            String code,
            String nameEn,
            String descriptionEn,
            String taxonomyRoot,
            int level,
            String parentCode,
            String analysisRole) {

        private String semanticLine() {
            return String.join("\u001f",
                    code,
                    nameEn,
                    descriptionEn,
                    taxonomyRoot,
                    Integer.toString(level),
                    parentCode,
                    analysisRole);
        }

        private String legacyLine() {
            return String.join("\u001f",
                    code,
                    nameEn,
                    descriptionEn,
                    taxonomyRoot,
                    Integer.toString(level)) + "\n";
        }
    }
}
