package com.taxonomy.dsl.command;

import com.taxonomy.dsl.command.ArchitectureCommand.RelationKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** One source observation about a directed relation. Only the frozen snapshot adapter builds these. */
public record SnapshotEvidence(RelationKey relation, String origin, Double confidence, Map<String, String> details) {
    public static final String FINGERPRINT = "x-analysis-evidence-fingerprint";
    public static final String GENERATED_DESCRIPTION_HASH = "x-analysis-generated-description-sha256";
    static final String VIEW = "x-analysis-view-id";
    static final String SNAPSHOT = "x-analysis-snapshot-id";
    public static final int MAX_RECORDS = 10000;
    private static final Set<String> FIELDS = Set.of("summary", "x-analysis-derivation", "x-analysis-presence",
            "x-analysis-included-because", "x-analysis-seed-type", "x-analysis-direction", "x-analysis-outcome",
            "x-analysis-necessity", "x-analysis-assessed-id", "x-analysis-assessed-contribution",
            "x-analysis-assessed-quote", "x-analysis-assessed-condition", "x-analysis-candidate-id",
            "x-analysis-candidate-contribution", "x-analysis-candidate-quote", "x-analysis-candidate-condition",
            "x-analysis-alternative-group", "x-analysis-question");

    public SnapshotEvidence {
        Objects.requireNonNull(relation);
        requireUtf8(relation.id());
        if (origin != null && !origin.matches("[A-Za-z0-9_-]{1,100}")) throw invalid("Invalid recorded origin");
        if (confidence != null && (!Double.isFinite(confidence) || confidence < 0 || confidence > 1))
            throw invalid("Recorded confidence must be finite and between zero and one");
        if (!FIELDS.containsAll(details.keySet())) throw invalid("Unknown snapshot evidence field");
        details = Map.copyOf(details);
        for (String value : details.values()) {
            requireUtf8(value);
            if (value.length() > 8000 || value.chars().anyMatch(c -> Character.isISOControl(c)
                    && c != '\n' && c != '\r' && c != '\t' || c == 0xfffe || c == 0xffff))
                throw invalid("Snapshot evidence contains an unsupported character or exceeds 8000 characters per field");
        }
    }

    Map<String, String> relationProperties() {
        Map<String, String> fields = new TreeMap<>();
        if (origin != null) fields.put("provenance", origin);
        if (confidence != null) fields.put("confidence", confidence.toString());
        return fields;
    }

    Map<String, String> properties(String viewId, String snapshotId) {
        var fields = new TreeMap<>(details);
        fields.put("for-relation", relation.id());
        fields.put("type", details.containsKey("x-analysis-outcome") ? "requirement-relation" : "analysis-relationship");
        fields.put(VIEW, viewId);
        fields.put(SNAPSHOT, snapshotId);
        if (origin != null) fields.put("x-analysis-origin", origin);
        if (confidence != null) fields.put("confidence", confidence.toString());
        return fields;
    }

    /** Field names are fixed, and values cannot contain NUL, so this framing preserves field boundaries. */
    static String id(Map<String, String> fields) {
        StringJoiner parts = new StringJoiner("\u0000");
        new TreeMap<>(fields).forEach((name, value) -> { parts.add(name); parts.add(value); });
        return "analysis-evidence-" + digest(parts.toString());
    }

    static String fingerprint(String viewId, String snapshotId, List<SnapshotEvidence> evidence) {
        if (evidence.size() > MAX_RECORDS) throw invalid("At most 10000 evidence records can be adopted");
        if (!evidence.isEmpty() && (snapshotId == null || snapshotId.isBlank() || viewId == null))
            throw invalid("Evidence requires an exact source snapshot and view");
        var ids = new TreeSet<String>();
        long bytes = 0;
        Map<RelationKey, Map<String, String>> edges = new HashMap<>();
        for (var entry : evidence) {
            var fields = entry.properties(viewId, snapshotId);
            for (var field : fields.entrySet()) bytes += field.getKey().length() + field.getValue().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 4 * 1024 * 1024) throw invalid("Snapshot evidence exceeds 4 MiB");
            if (!ids.add(id(fields))) throw invalid("Duplicate source evidence identity");
            var relationProperties = entry.relationProperties();
            var prior = edges.putIfAbsent(entry.relation(), relationProperties);
            if (prior != null && !prior.equals(relationProperties)) throw invalid("Conflicting metadata on the same snapshot relation");
        }
        return fingerprint(ids);
    }

    static String fingerprint(SortedSet<String> evidenceIds) {
        return digest(String.join("\n", evidenceIds));
    }

    static String digest(String value) {
        requireUtf8(value);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** UTF-8 is the byte representation for both frozen fingerprints and Git checkpoint contents. */
    public static void requireUtf8(String value) {
        if (value != null && !StandardCharsets.UTF_8.newEncoder().canEncode(value))
            throw invalid("Frozen snapshot text contains an unpaired UTF-16 surrogate");
    }

    public static ArchitectureDslCommands.CommandProblem invalid(String message) {
        return new ArchitectureDslCommands.CommandProblem("INVALID_SNAPSHOT_EVIDENCE", "evidence", message, List.of());
    }
}
