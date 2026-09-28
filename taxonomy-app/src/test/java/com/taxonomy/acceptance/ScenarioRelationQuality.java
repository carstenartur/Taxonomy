package com.taxonomy.acceptance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static com.taxonomy.acceptance.DirectedRelationMetrics.*;

/** Post-processes the existing scenario application scenario; never calls a provider or supplies its answers. */
public final class ScenarioRelationQuality {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CASE_ID = "flood-information-v1";
    private static final List<String> REPORTS = List.of("relation-quality.json", "relation-quality.csv", "relation-quality.html");
    private static final Reference REFERENCE = new Reference(Set.of(
            new Relation("CI-1052", "SUPPORTS", "BP-1017"), new Relation("UA-1580", "CONSUMES", "IP-1116"),
            new Relation("BP-1017", "CONSUMES", "IP-1116"), new Relation("CI-1052", "FULFILLS", "CP-1041"),
            new Relation("UA-1580", "USES", "CI-1052"), new Relation("CI-1052", "PRODUCES", "IP-1116"),
            new Relation("UA-1580", "USES", "CR-1097")), Set.of(), Set.of());

    private ScenarioRelationQuality() { }

    public static void clearReports(Path directory) throws IOException {
        for (String report : REPORTS) Files.deleteIfExists(directory.resolve(report));
    }

    public static void verify(Path directory) throws IOException {
        var result = write(directory);
        if (result.verdict() != Verdict.PASS) {
            throw new AssertionError("Authored scenario relation evaluation: " + result.verdict()
                    + "; inspect relation-quality.json (not a live-model benchmark)");
        }
    }

    public static Evaluation write(Path directory) throws IOException {
        clearReports(directory);
        byte[] snapshotBytes = readBounded(directory.resolve("snapshot.json"), 32 * 1024 * 1024);
        JsonNode snapshot = JSON.readTree(snapshotBytes);
        JsonNode run = JSON.readTree(readBounded(directory.resolve("run.json"), 64 * 1024));
        JsonNode summary = object(snapshot, "summary");
        JsonNode analysis = object(snapshot, "analysis");
        JsonNode search = object(analysis, "relationSearchReport");
        String caseId = text(run, "scenario");
        if (!CASE_ID.equals(caseId) || !text(run, "snapshotId").equals(text(summary, "id"))) {
            throw new IllegalArgumentException("Case or snapshot identity does not match the authored evaluation");
        }
        String status = text(analysis, "status");
        if (!status.equals(text(summary, "status"))) throw new IllegalArgumentException("Conflicting snapshot states");
        RunState state = RunState.valueOf(status);
        JsonNode unfinished = array(object(search, "result"), "unfinished");
        JsonNode warnings = array(search, "warnings");
        String stopReason = textValue(search, "stopReason");
        // Exhausting a navigation queue is not proof of semantic completeness.
        if (state == RunState.SUCCESS && (!unfinished.isEmpty() || !warnings.isEmpty() || !stopReason.isEmpty())) {
            state = RunState.PARTIAL;
        }
        var predictions = new ArrayList<Relation>();
        for (JsonNode relation : array(object(analysis, "architectureView"), "includedRelationships")) {
            predictions.add(new Relation(text(relation, "sourceCode"), text(relation, "relationType"), text(relation, "targetCode")));
        }
        Evaluation evaluation = evaluate(REFERENCE, predictions, state);
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", 1);
        report.put("referenceVersion", "scenario-directed-relations-v1");
        report.put("evidenceKind", "AUTHORED_PLAYBACK_NOT_LIVE_MODEL");
        report.put("caseId", caseId);
        report.put("fixtureSha256", fingerprint(run, "fixtureSha256"));
        report.put("snapshotId", text(summary, "id"));
        report.put("snapshotSha256", sha256(snapshotBytes));
        report.put("sourceRevision", text(run, "sourceRevision"));
        report.put("taxonomyFingerprint", fingerprint(summary, "taxonomyFingerprint"));
        report.put("promptFingerprint", fingerprint(summary, "promptFingerprint"));
        report.put("totalScenarioLlmCalls", count(run, "llmCalls"));
        report.put("relationCalls", count(search, "totalCalls"));
        report.put("relationCallBudget", count(search, "maxCalls"));
        report.put("relationDurationMillis", count(search, "durationMillis"));
        report.put("unfinishedSearchCount", unfinished.size());
        report.put("reference", REFERENCE);
        report.put("evaluation", evaluation);
        String renderedJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";
        // Inputs, cases and comparison ordering are fixed; there is no timestamp or random output ID.
        Files.writeString(directory.resolve("relation-quality.csv"), csv(evaluation), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("relation-quality.html"), html(renderedJson), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("relation-quality.json"), renderedJson, StandardCharsets.UTF_8);
        return evaluation;
    }

    private static String csv(Evaluation value) {
        return "case,run_state,verdict,required,predicted,matched_required,matched_allowed,false_positives,not_observed_required,unresolved_reference,unresolved_predictions,precision,recall\n"
                + CASE_ID + "," + value.state() + "," + value.verdict() + "," + value.requiredCount() + ","
                + value.predictedCount() + "," + value.matchedRequired() + "," + value.matchedAllowed() + ","
                + value.falsePositives().size() + "," + value.notObservedRequired().size() + ","
                + value.unresolvedReferenceCount() + "," + value.unresolvedPredictions().size() + ","
                + nullable(value.precision()) + "," + nullable(value.recall()) + "\n";
    }

    private static String nullable(Double value) { return value == null ? "" : value.toString(); }

    private static String html(String report) {
        String escaped = report.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
        return "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Authored relation quality</title>"
                + "<h1>Authored directed-relation evaluation</h1><p>Not a live-model benchmark. "
                + "Exact type and direction are compared with a separately authored reference. "
                + "Unresolved references remain open; incomplete runs have no quality rates. "
                + "Null rates mean undefined, not perfect quality.</p><pre>" + escaped + "</pre></html>\n";
    }

    private static JsonNode object(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        if (!value.isObject()) throw new IllegalArgumentException("Expected object field: " + name);
        return value;
    }

    private static JsonNode array(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        if (!value.isArray()) throw new IllegalArgumentException("Expected array field: " + name);
        return value;
    }

    private static String textValue(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        if (!value.isString()) throw new IllegalArgumentException("Expected text field: " + name);
        return value.stringValue();
    }

    private static String text(JsonNode parent, String name) {
        String value = textValue(parent, name);
        if (value.isBlank()) throw new IllegalArgumentException("Expected nonblank field: " + name);
        return value;
    }

    private static String fingerprint(JsonNode parent, String name) {
        String value = text(parent, name);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid fingerprint field: " + name);
        return value;
    }

    private static long count(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
            throw new IllegalArgumentException("Expected nonnegative integer field: " + name);
        }
        return value.asLong();
    }

    private static byte[] readBounded(Path file, int limit) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Evaluation input exceeds its documented byte budget");
            return bytes;
        }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
