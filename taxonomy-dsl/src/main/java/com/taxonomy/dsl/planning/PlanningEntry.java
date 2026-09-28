package com.taxonomy.dsl.planning;

import java.util.Map;

/** A bounded, versioned value attached to one requirement in one canonical workspace. */
public record PlanningEntry(String id, String profile, String version, String origin,
                            Map<String, String> values) {
    public PlanningEntry {
        token(id, "entry id"); token(profile, "profile"); token(version, "profile version");
        text(origin, 256, "origin");
        if (values == null || values.size() > 32) throw new IllegalArgumentException("At most 32 planning fields are allowed");
        values.forEach((key, value) -> { token(key, "field"); text(value, 4096, key); });
        values = Map.copyOf(values);
    }
    static void token(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Invalid planning " + field);
    }
    static void text(String value, int max, String field) {
        if (value == null || value.length() > max || value.chars().anyMatch(c -> Character.isISOControl(c)))
            throw new IllegalArgumentException("Invalid or oversized planning " + field);
    }
}
