package com.taxonomy.archimate.exchange;

import com.taxonomy.archimate.ArchiMateRelationship;
import com.taxonomy.exchange.ArchiMateEndpointOrder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned Taxonomy-to-ArchiMate projection and its bounded endpoint rules. */
public final class ArchiMateExchangeProfile {
    public static final String VERSION = ArchiMateEndpointOrder.PROFILE_V3;
    public static final String LEGACY_VERSION = "taxonomy-archimate-3.1-v2";
    public static final String RESOURCE = "/archimate/profile-v3.tsv";
    private static final Map<String, Mapping> CURRENT = load(RESOURCE, VERSION);
    private static final Map<String, Mapping> LEGACY = load("/archimate/profile-v2.tsv", LEGACY_VERSION);

    private ArchiMateExchangeProfile() { }

    public record Mapping(String scope, String sourceType, String targetType, String accessType,
                          List<String> properties, String kind, String rationale, String profileVersion,
                          Set<String> allowedRelationships) { }

    public static Mapping element(String sourceType) { return element(VERSION, sourceType); }
    static Mapping element(String version, String sourceType) { return mapping(version, "element", sourceType); }
    /** Primary projection before endpoint validation. */
    public static Mapping relationship(String sourceType) { return mapping(VERSION, "relationship", sourceType); }
    public static List<Mapping> mappings() {
        return CURRENT.values().stream().filter(mapping -> !"endpoints".equals(mapping.scope())).toList();
    }

    public static Mapping relationship(String version, String sourceType, Mapping source, Mapping target) {
        Mapping base = mapping(version, "relationship", sourceType);
        if (LEGACY_VERSION.equals(version)) return base;
        String from = source.targetType(), to = target.targetType();
        boolean reversed = reversedEndpoints(version, sourceType, base.targetType());
        String nativeFrom = reversed ? to : from, nativeTo = reversed ? from : to;
        if (allowed(nativeFrom, nativeTo, base.targetType())) return base;
        if (!allowed(from, to, "Association")) {
            throw new IllegalArgumentException("No supported ArchiMate projection for " + sourceType + " from " + from + " to " + to);
        }
        return new Mapping(base.scope(), sourceType, "Association", null, base.properties(), "APPROXIMATED",
                base.targetType() + " from " + nativeFrom + " to " + nativeTo
                        + " is unavailable. A directed Association retains endpoint order; taxonomy.type records " + sourceType + ".",
                version, Set.of());
    }

    private static boolean allowed(String from, String to, String type) {
        Mapping endpoints = CURRENT.get("endpoints:" + from + ":" + to);
        if (endpoints == null) throw new IllegalArgumentException("No ArchiMate endpoint rule for " + from + " to " + to);
        return endpoints.allowedRelationships().contains(type);
    }

    public static boolean reversedEndpoints(String version, ArchiMateRelationship relation) {
        var type = relation.properties().get("taxonomy.type");
        return type != null && reversedEndpoints(version, type.value(), relation.archiMateType());
    }

    public static boolean reversedEndpoints(String profileVersion, String taxonomyType, String nativeType) {
        return ArchiMateEndpointOrder.reversed(profileVersion, taxonomyType, nativeType);
    }

    static boolean directedAssociation(String profileVersion, String taxonomyType, String nativeType) {
        return VERSION.equals(profileVersion) && taxonomyType != null
                && !"RELATED_TO".equals(taxonomyType) && "Association".equals(nativeType);
    }

    public static byte[] resourceBytes() {
        return resourceBytes(RESOURCE);
    }

    private static byte[] resourceBytes(String resource) {
        try (InputStream input = ArchiMateExchangeProfile.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing exchange mapping profile " + resource);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static boolean supported(String version) {
        return VERSION.equals(version) || LEGACY_VERSION.equals(version);
    }

    private static Mapping mapping(String version, String scope, String sourceType) {
        if (!supported(version)) throw new IllegalArgumentException("Unsupported Taxonomy ArchiMate exchange profile: " + version);
        Mapping mapping = (VERSION.equals(version) ? CURRENT : LEGACY).get(scope + ":" + sourceType);
        if (mapping == null) {
            throw new IllegalArgumentException("Unsupported ArchiMate " + scope + " source type: "
                    + sourceType + " (profile " + version + ")");
        }
        return mapping;
    }

    private static Map<String, Mapping> load(String resource, String version) {
        Map<String, Mapping> result = new LinkedHashMap<>();
        boolean current = VERSION.equals(version);
        new String(resourceBytes(resource), StandardCharsets.UTF_8).lines().skip(1).forEach(line -> {
            if (line.isBlank()) return;
            String[] fields = line.split("\t", -1);
            if (fields.length != (current ? 9 : 8) || !version.equals(fields[fields.length - 1])) {
                throw new IllegalStateException("Invalid exchange profile row");
            }
            Mapping mapping = new Mapping(fields[0], fields[1], fields[2],
                    fields[3].isEmpty() ? null : fields[3], List.of(fields[4].split(";")), fields[5], fields[6], version,
                    current ? concepts(fields[7]) : Set.of());
            String key = mapping.scope() + ":" + mapping.sourceType();
            if ("endpoints".equals(mapping.scope())) key += ":" + mapping.targetType();
            if (result.putIfAbsent(key, mapping) != null) {
                throw new IllegalStateException("Duplicate exchange mapping");
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> concepts(String field) {
        return field.isEmpty() ? Set.of() : Set.of(field.split(";"));
    }
}
