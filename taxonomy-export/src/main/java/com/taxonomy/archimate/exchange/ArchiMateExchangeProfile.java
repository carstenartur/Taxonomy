package com.taxonomy.archimate.exchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The executable, versioned outbound profile. There is no implicit fallback. */
public final class ArchiMateExchangeProfile {
    public static final String VERSION = "taxonomy-archimate-3.1-v2";
    public static final String RESOURCE = "/archimate/profile-v2.tsv";
    private static final Map<String, Mapping> MAPPINGS = load();

    private ArchiMateExchangeProfile() { }

    public record Mapping(String scope, String sourceType, String targetType,
                          String accessType, List<String> properties, String kind, String rationale, String profileVersion) { }

    public static Mapping element(String sourceType) { return mapping("element", sourceType); }
    public static Mapping relationship(String sourceType) { return mapping("relationship", sourceType); }
    public static List<Mapping> mappings() { return List.copyOf(MAPPINGS.values()); }

    public static byte[] resourceBytes() {
        try (InputStream input = ArchiMateExchangeProfile.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing exchange mapping profile");
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Mapping mapping(String scope, String sourceType) {
        Mapping mapping = MAPPINGS.get(scope + ":" + sourceType);
        if (mapping == null) {
            throw new IllegalArgumentException("Unsupported ArchiMate " + scope + " source type: "
                    + sourceType + " (profile " + VERSION + "; no fallback enabled)");
        }
        return mapping;
    }

    private static Map<String, Mapping> load() {
        Map<String, Mapping> result = new LinkedHashMap<>();
        new String(resourceBytes(), StandardCharsets.UTF_8).lines().skip(1).forEach(line -> {
            if (line.isBlank()) return;
            String[] fields = line.split("\t", -1);
            if (fields.length != 8 || !VERSION.equals(fields[7])) {
                throw new IllegalStateException("Invalid exchange profile row");
            }
            Mapping mapping = new Mapping(fields[0], fields[1], fields[2],
                    fields[3].isEmpty() ? null : fields[3], List.of(fields[4].split(";")), fields[5], fields[6], fields[7]);
            if (result.putIfAbsent(mapping.scope() + ":" + mapping.sourceType(), mapping) != null) {
                throw new IllegalStateException("Duplicate exchange mapping");
            }
        });
        return java.util.Collections.unmodifiableMap(result);
    }
}
