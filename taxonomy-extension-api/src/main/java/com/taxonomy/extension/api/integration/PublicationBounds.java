package com.taxonomy.extension.api.integration;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.util.*;

/** Finite transport budgets shared by adapters and the journal. No payload appears in diagnostics. */
public final class PublicationBounds {
    public static final int SCHEMA_VERSION = 1;
    public static final String CONTRACT_VERSION = "taxonomy-publication-contract-v1";
    public static final int MAX_SCOPE_ITEMS = 10_000, MAX_MUTATIONS = 1_024;
    public static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024, MAX_ITEM_BYTES = 1024 * 1024;
    public static final int MAX_ATTEMPTS = 100;
    public static final long INVOCATION_SECONDS = 30, CLAIM_LEASE_SECONDS = 30;
    private PublicationBounds() {}
    public static void schema(int value) { if (value != SCHEMA_VERSION) throw invalid(); }
    public static void attempt(int value) { if (value < 1 || value > MAX_ATTEMPTS) throw invalid(); }
    public static String text(String value) {
        if (value == null || value.isBlank() || value.length() > 2048 || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }
    public static String token(String value) {
        text(value);
        if (value.startsWith("W/") || !value.matches("[A-Za-z0-9._~:/+=\"-]+")) throw invalid();
        return value;
    }
    public static String resource(String value) {
        text(value);
        if (value.toLowerCase(Locale.ROOT).matches(".*%(?:2e|2f|5c|40|3f|23|25).*")) throw invalid();
        try {
            URI uri = URI.create(value);
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || value.contains("\\")
                    || Arrays.asList(uri.getPath() == null ? new String[0] : uri.getPath().split("/")).contains("..")) throw invalid();
            if (uri.isAbsolute() && !Set.of("https", "http", "urn").contains(uri.getScheme())) throw invalid();
        } catch (IllegalArgumentException failure) { throw invalid(); }
        return value;
    }
    public static <T> List<T> list(List<T> values, int maximum) {
        Objects.requireNonNull(values); if (values.size() > maximum) throw invalid(); return List.copyOf(values);
    }
    public static <T> Set<T> set(Set<T> values, int maximum) {
        Objects.requireNonNull(values); if (values.size() > maximum) throw invalid(); return Set.copyOf(values);
    }
    public static <K,V> Map<K,V> map(Map<K,V> values, int maximum) {
        Objects.requireNonNull(values); if (values.size() > maximum) throw invalid(); return Map.copyOf(values);
    }
    public static void document(IntegrationContracts.ExchangeDocument value) {
        Objects.requireNonNull(value);
        if ((long)value.artifacts().size() + value.relations().size() + value.placements().size() > MAX_SCOPE_ITEMS) throw invalid();
        bounded(MAX_DOCUMENT_BYTES, value);
    }
    /** Counts JSON bytes directly, before serialization/canonical-tree allocation; stops at the limit. */
    public static void bounded(int maximum, Object... values) {
        // Reserve field-name/envelope overhead when constructors check components before their record exists.
        Counter counter = new Counter(maximum - 2048L); counter.add(2);
        for (Object value : values) { counter.value(value, 0); counter.add(1); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid or oversized publication value"); }
    private static final class Counter {
        long remaining;
        Counter(long maximum) { remaining = maximum; }
        void add(long count) { remaining -= count; if (remaining < 0) throw invalid(); }
        void string(String value) {
            add(2);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 32) add(6); else if (c == '"' || c == '\\') add(2);
                else if (c < 128) add(1); else if (c < 2048) add(2);
                else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) { add(4); i++; }
                else add(3);
            }
        }
        void value(Object value, int depth) {
            if (depth > 32) throw invalid();
            if (value == null) { add(4); return; }
            if (value instanceof String s) { string(s); return; }
            if (value instanceof Number || value instanceof Boolean) { add(value.toString().length()); return; }
            if (value instanceof Map<?,?> map) {
                if (map.size() > MAX_SCOPE_ITEMS) throw invalid(); add(2);
                map.forEach((key, item) -> { string(key.toString()); add(2); value(item, depth + 1); }); return;
            }
            if (value instanceof Collection<?> collection) {
                if (collection.size() > MAX_SCOPE_ITEMS) throw invalid(); add(2);
                for (Object item : collection) { value(item, depth + 1); add(1); } return;
            }
            if (value.getClass().isRecord()) {
                add(2);
                try { for (RecordComponent field : value.getClass().getRecordComponents()) { string(field.getName()); add(2); value(field.getAccessor().invoke(value), depth + 1); } }
                catch (ReflectiveOperationException e) { throw invalid(); } return;
            }
            if (value instanceof Enum<?> || value instanceof UUID || value instanceof java.time.Instant) { string(value.toString()); return; }
            throw invalid();
        }
    }
}
