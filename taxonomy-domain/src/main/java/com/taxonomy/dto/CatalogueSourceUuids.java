package com.taxonomy.dto;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Catalogue row identities recorded in a particular analysis, independent of exchange-tool IDs. */
public final class CatalogueSourceUuids {
    private static final Pattern COMPACT_UUID = Pattern.compile(
            "([0-9A-Fa-f]{8})([0-9A-Fa-f]{4})([0-9A-Fa-f]{4})([0-9A-Fa-f]{4})([0-9A-Fa-f]{12})");
    private CatalogueSourceUuids() { }

    public static String normalize(String raw) {
        if (raw == null) return null;
        String text = raw.strip();
        if (text.startsWith("{") && text.endsWith("}")) text = text.substring(1, text.length() - 1);
        if (text.length() == 32) text = COMPACT_UUID.matcher(text).replaceFirst("$1-$2-$3-$4-$5");
        if (text.length() != 36) return null;
        try {
            UUID parsed = UUID.fromString(text);
            if (parsed.getMostSignificantBits() == 0 && parsed.getLeastSignificantBits() == 0) return null;
            String canonical = parsed.toString();
            return canonical.equalsIgnoreCase(text) ? canonical : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Suppress ambiguous rows in either direction and ignore synthetic level-zero sheet roots. */
    public static Map<String, String> fromTree(List<TaxonomyNodeDto> roots) {
        if (roots == null || roots.isEmpty()) return Map.of();
        var pending = new ArrayDeque<TaxonomyNodeDto>();
        roots.stream().filter(java.util.Objects::nonNull).forEach(pending::addLast);
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<TaxonomyNodeDto, Boolean>());
        var byCode = new HashMap<String, String>();
        var byUuid = new HashMap<String, String>();
        var ambiguous = new HashSet<String>();
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            if (!visited.add(node)) continue;
            if (node.getChildren() != null)
                node.getChildren().stream().filter(java.util.Objects::nonNull).forEach(pending::addLast);
            String code = node.getCode();
            if (node.getLevel() <= 0 || code == null || code.isBlank()) continue;
            String uuid = normalize(node.getUuid());
            if (uuid == null) continue;
            String previousUuid = byCode.putIfAbsent(code, uuid);
            if (previousUuid != null && !previousUuid.equals(uuid)) ambiguous.add(code);
            String owner = byUuid.putIfAbsent(uuid, code);
            if (owner != null && !owner.equals(code)) {
                ambiguous.add(code);
                ambiguous.add(owner);
            }
        }
        ambiguous.forEach(byCode::remove);
        return Map.copyOf(byCode);
    }
}
