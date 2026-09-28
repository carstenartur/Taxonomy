package com.taxonomy.exchange;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Versioned transport values only. Domain interpretation belongs to the installed planning profile. */
public final class PlanningEnvelope {
    public static final String EXTENSION = "planningProfilesV1";
    public static final String ATTRIBUTE = "Taxonomy.PlanningProfiles.v1";
    public static final int MAX_LENGTH = 131072;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(MAX_LENGTH).maxNumberLength(10).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private PlanningEnvelope() {}
    public record Entry(String id, String profile, String version, String origin, Map<String, String> values) {
        public Entry {
            token(id); token(profile); token(version); bounded(origin, 256);
            if (values == null || values.size() > 32) throw invalid();
            values.forEach((key, value) -> { token(key); bounded(value, 4096); });
            values = Map.copyOf(values);
        }
    }
    public static List<Entry> read(String value) {
        if (value == null || value.length() > MAX_LENGTH) throw invalid();
        try {
            JsonNode root = JSON.readTree(value);
            fields(root, Set.of("schema", "entries"));
            if (!root.path("schema").isIntegralNumber() || !root.path("schema").canConvertToInt()
                    || root.path("schema").intValue() != 1
                    || !root.path("entries").isArray() || root.path("entries").size() > 32) throw invalid();
            List<Entry> entries = new ArrayList<>(); Set<String> ids = new HashSet<>();
            for (JsonNode item : root.get("entries")) {
                fields(item, Set.of("id", "profile", "version", "origin", "values"));
                Map<String, String> values = new TreeMap<>(); JsonNode data = item.get("values");
                if (!data.isObject() || data.size() > 32) throw invalid();
                data.fields().forEachRemaining(e -> { if (!e.getValue().isTextual()) throw invalid(); values.put(e.getKey(), e.getValue().textValue()); });
                Entry entry = new Entry(string(item, "id"), string(item, "profile"), string(item, "version"), string(item, "origin"), values);
                if (!ids.add(entry.id())) throw invalid();
                entries.add(entry);
            }
            return List.copyOf(entries);
        } catch (ExchangeFormatException failure) { throw failure; }
        catch (Exception malformed) { throw invalid(); }
    }
    public static String write(List<Entry> entries) {
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Entry entry : entries.stream().sorted(java.util.Comparator.comparing(Entry::id)).toList()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", entry.id()); item.put("profile", entry.profile()); item.put("version", entry.version());
                item.put("origin", entry.origin()); item.put("values", new TreeMap<>(entry.values())); items.add(item);
            }
            Map<String, Object> root = new LinkedHashMap<>(); root.put("schema", 1); root.put("entries", items);
            String value = JSON.writeValueAsString(root); read(value); return value;
        } catch (ExchangeFormatException failure) { throw failure; }
        catch (Exception failed) { throw invalid(); }
    }
    public static List<MappingLoss> report(ExchangeDocument document) {
        List<MappingLoss> result = new ArrayList<>(document.losses());
        for (Artifact artifact : document.artifacts()) if (artifact.extensions().containsKey(EXTENSION)) {
            read(artifact.extensions().get(EXTENSION));
            var loss = preservation(artifact.id());
            if (!result.contains(loss)) result.add(loss);
        }
        return List.copyOf(result);
    }
    public static MappingLoss preservation(String id) {
        return new MappingLoss(id, ATTRIBUTE, "PLANNING_PROFILE_ENVELOPE", LossDisposition.PRESERVED_EXTENSION,
                "Structured planning metadata is carried in a ReqIF STRING attribute. Native target evaluation and retention by untested products are not claimed.");
    }
    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw invalid();
        node.fieldNames().forEachRemaining(key -> { if (!expected.contains(key)) throw invalid(); });
    }
    private static String string(JsonNode node, String key) { if (!node.get(key).isTextual()) throw invalid(); return node.get(key).textValue(); }
    private static void token(String value) { if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw invalid(); }
    private static void bounded(String value, int size) {
        if (value == null || value.length() > size || value.chars().anyMatch(Character::isISOControl)) throw invalid();
    }
    private static ExchangeFormatException invalid() {
        return new ExchangeFormatException("INVALID_PLANNING_ENVELOPE", "Expected bounded planning envelope v1 with unique entry identities and string fields; no profile values were interpreted");
    }
}
