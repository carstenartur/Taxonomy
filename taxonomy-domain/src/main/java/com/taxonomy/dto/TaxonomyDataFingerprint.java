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
        Map<String, String> canonicalByCode = new LinkedHashMap<>();
        Set<TaxonomyNodeDto> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (roots != null) {
            for (TaxonomyNodeDto root : roots) {
                collect(root, null, canonicalByCode, visited);
            }
        }

        MessageDigest digest = sha256Digest();
        update(digest, VERSION);
        canonicalByCode.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> update(digest, entry.getValue()));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void collect(
            TaxonomyNodeDto node,
            String inheritedParentCode,
            Map<String, String> canonicalByCode,
            Set<TaxonomyNodeDto> visited) {
        if (node == null || !visited.add(node)
                || node.getCode() == null || node.getCode().isBlank()) {
            return;
        }
        String code = node.getCode().strip();
        String parentCode = firstNonBlank(node.getParentCode(), inheritedParentCode);
        String canonical = String.join("\u001f",
                code,
                safe(node.getNameEn()),
                safe(node.getDescriptionEn()),
                safe(node.getTaxonomyRoot()),
                Integer.toString(node.getLevel()),
                safe(parentCode),
                normalizedRole(node.getAnalysisRole()));
        String previous = canonicalByCode.putIfAbsent(code, canonical);
        if (previous != null && !previous.equals(canonical)) {
            throw new IllegalArgumentException(
                    "Taxonomy tree contains conflicting definitions for node " + code);
        }

        List<TaxonomyNodeDto> children = node.getChildren() == null
                ? List.of() : new ArrayList<>(node.getChildren());
        children.sort(Comparator.comparing(
                child -> child == null || child.getCode() == null ? "" : child.getCode()));
        for (TaxonomyNodeDto child : children) {
            collect(child, code, canonicalByCode, visited);
        }
    }

    private static String normalizedRole(String role) {
        return role == null || role.isBlank() ? "CATEGORY" : role.strip().toUpperCase();
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
