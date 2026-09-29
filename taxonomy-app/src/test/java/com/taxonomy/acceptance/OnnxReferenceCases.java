package com.taxonomy.acceptance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Positive-reference retrieval metrics, not exhaustive precision or probability estimates. */
public final class OnnxReferenceCases {
    public static final String RESOURCE = "/scenarios/local-onnx-reference-v1.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private OnnxReferenceCases() { }

    public record Case(String id, String language, String domain, String query,
                       Set<String> required, String expectedTitle) {
        public Case { required = Set.copyOf(required); }
    }
    public record Measurement(List<String> predicted, List<String> missing,
                              Double recall, Integer firstRelevantRank) {
        public Measurement {
            predicted = List.copyOf(predicted);
            missing = List.copyOf(missing);
        }
    }

    public static List<Case> load() throws IOException { return parse(resourceBytes()); }

    public static byte[] resourceBytes() throws IOException {
        try (var input = OnnxReferenceCases.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Missing ONNX reference resource");
            byte[] bytes = input.readNBytes(65_537);
            if (bytes.length > 65_536) throw new IOException("ONNX reference exceeds 64 KiB");
            return bytes;
        }
    }

    public static List<Case> parse(byte[] bytes) {
        if (bytes.length > 65_536) throw new IllegalArgumentException("Reference size budget");
        JsonNode root = JSON.readTree(bytes);
        if (root == null || !root.path("schemaVersion").isIntegralNumber()
                || root.path("schemaVersion").asInt() != 1
                || !"SYNTHETIC_POSITIVE_REFERENCES_NOT_EXHAUSTIVE".equals(text(root, "referenceKind"))
                || !root.path("cases").isArray() || root.path("cases").isEmpty()
                || root.path("cases").size() > 12) throw new IllegalArgumentException("Invalid reference schema");
        text(root, "source");
        if (!text(root, "catalogueSourceRevision").matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Invalid reference catalogue revision");
        }
        var ids = new HashSet<String>();
        var cases = new ArrayList<Case>();
        for (JsonNode row : root.path("cases")) {
            String id = text(row, "id"), language = text(row, "language");
            if (!id.matches("[a-z0-9-]{1,80}") || !ids.add(id)
                    || !Set.of("de", "en").contains(language)) throw new IllegalArgumentException("Invalid case identity");
            String query = text(row, "query"), domain = text(row, "domain");
            if (query.length() > 4096 || !domain.matches("[a-z0-9-]{1,80}")) {
                throw new IllegalArgumentException("Invalid case query or domain");
            }
            if (!row.path("required").isArray() || row.path("required").size() != 1) {
                throw new IllegalArgumentException("This version requires one independently bound catalogue target");
            }
            var required = new HashSet<String>();
            for (JsonNode node : row.path("required")) {
                if (!node.isString() || !node.stringValue().matches("[A-Z]+-[0-9]+")
                        || !required.add(node.stringValue())) throw new IllegalArgumentException("Invalid reference code");
            }
            cases.add(new Case(id, language, domain, query, required, text(row, "expectedTitle")));
        }
        cases.sort(java.util.Comparator.comparing(Case::id));
        return List.copyOf(cases);
    }

    public static Measurement measure(Set<String> required, List<String> predictions, int k) {
        Objects.requireNonNull(required); Objects.requireNonNull(predictions);
        if (required.isEmpty() || k < 1 || k > 100 || required.stream().anyMatch(s -> s == null || s.isBlank())
                || predictions.stream().anyMatch(s -> s == null || s.isBlank())
                || new HashSet<>(predictions).size() != predictions.size()) {
            throw new IllegalArgumentException("Invalid positive reference, ranking or top-K");
        }
        List<String> ranked = List.copyOf(predictions.subList(0, Math.min(k, predictions.size())));
        var missing = new TreeSet<>(required);
        missing.removeAll(ranked);
        Integer first = null;
        for (int i = 0; i < ranked.size(); i++) if (required.contains(ranked.get(i))) { first = i + 1; break; }
        return new Measurement(ranked, List.copyOf(missing),
                (required.size() - missing.size()) / (double) required.size(), first);
    }

    public static boolean ready(JsonNode status) {
        JsonNode stateValue = status.path("indexState");
        String state = stateValue.isString() ? stateValue.stringValue() : "";
        return flag(status, "enabled") && flag(status, "available") && flag(status, "modelAvailable")
                && flag(status, "semanticReady")
                && Set.of("INDEXING_RELATIONS", "READY", "PARTIAL").contains(state)
                && status.path("indexedNodesAtReadiness").isIntegralNumber()
                && status.path("indexedNodesAtReadiness").asLong() > 0;
    }
    private static boolean flag(JsonNode node, String name) {
        return node.path(name).isBoolean() && node.path(name).booleanValue();
    }
    private static String text(JsonNode row, String name) {
        JsonNode value = row.path(name);
        if (!value.isString() || value.stringValue().isBlank()) throw new IllegalArgumentException("Invalid field: " + name);
        return value.stringValue();
    }
}
