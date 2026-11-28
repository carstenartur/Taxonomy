package com.taxonomy;

import com.taxonomy.acceptance.DirectedRelationMetrics;
import com.taxonomy.acceptance.DirectedRelationMetrics.Relation;
import com.taxonomy.acceptance.DirectedRelationMetrics.RunState;
import com.taxonomy.acceptance.ScenarioJsonFingerprint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Post-processes the selected, version-bound architecture view. Never supplies candidate answers. */
final class AltscherbitzArchitectureQa {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String REQUIREMENTS = "/scenarios/altscherbitz-backup-reference-v1.json";
    static final String ARCHITECTURE = "/scenarios/altscherbitz-architecture-reference-v1.json";

    private AltscherbitzArchitectureQa() { }

    static JsonNode resource(String path) throws IOException {
        try (var input = AltscherbitzArchitectureQa.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing reference " + path);
            return JSON.readTree(input);
        }
    }

    static Map<String, Object> evaluate(JsonNode reference, JsonNode stage, JsonNode snapshot, String evidenceKind) {
        String id = stage.required("id").asString();
        String original = stage.required("requirement").asString();
        JsonNode expected = reference.required("cases").required(id);
        JsonNode summary = snapshot.required("summary");
        JsonNode analysis = snapshot.required("analysis");
        String mode = analysis.at("/modelBinding/scope").required("mode").asString();
        var result = new LinkedHashMap<String, Object>();
        result.put("referenceId", reference.required("referenceId").asString());
        result.put("referenceSha256", ScenarioJsonFingerprint.sha256(reference));
        result.put("stage", id);
        result.put("requirementSha256", sha256(original));
        result.put("snapshotId", summary.required("id").asString());
        result.put("snapshotSha256", ScenarioJsonFingerprint.sha256(snapshot));
        result.put("scope", mode);
        result.put("evidenceKind", evidenceKind);
        result.put("sourceObligations", expected.required("sourceObligations"));
        result.put("requiredAlternatives", expected.required("requiredAlternatives"));
        result.put("basis", expected.required("basis").asString());
        result.put("openQuestionsInSource", reference.required("openQuestions"));
        if ("TAXONOMIES_ONLY".equals(mode)) {
            result.put("outcome", "NOT_RUN");
            return result;
        }
        if (!Set.of("FULL", "FOCUSED_RELATION_STUDY").contains(mode)) throw new IllegalArgumentException("Unknown analysis scope " + mode);
        JsonNode search = analysis.required("relationSearchReport");
        if (!sha256(original).equals(search.required("originalSha256").asString()))
            throw new IllegalArgumentException("The saved search used a different requirement");
        String status = analysis.required("status").asString();
        if (!status.equals(summary.required("status").asString())) throw new IllegalArgumentException("Conflicting snapshot status");
        RunState state = RunState.valueOf(status);
        if (state == RunState.SUCCESS && (!search.required("searchExhausted").asBoolean()
                || !search.required("stopReason").asString().isEmpty() || !search.required("warnings").isEmpty()
                || !search.required("result").required("unfinished").isEmpty() || !analysis.required("warnings").isEmpty()
                || !completeProgress(search) || unresolvedSource(search))) state = RunState.PARTIAL;

        var pairs = new HashSet<Set<String>>();
        reference.required("scopedPairs").forEach(pair -> pairs.add(Set.of(pair.get(0).asString(), pair.get(1).asString())));
        var processes = new HashSet<String>();
        pairs.forEach(pair -> pair.stream().filter(code -> code.startsWith("BP-")).forEach(processes::add));
        var predictions = new ArrayList<Relation>();
        var outsideReference = new ArrayList<Relation>();
        var invalidEvidence = new ArrayList<Relation>();
        for (var edge : analysis.required("architectureView").required("includedRelationships")) {
            Relation relation = new Relation(edge.required("sourceCode").asString(),
                    edge.required("relationType").asString(), edge.required("targetCode").asString());
            boolean scoped = pairs.contains(Set.copyOf(List.of(relation.source(), relation.target())))
                    || roleAssignment(relation, processes);
            (scoped ? predictions : outsideReference).add(relation);
            if (scoped && !sourceBacked(edge, relation, original)) invalidEvidence.add(relation);
        }
        var alternatives = relations(expected.required("requiredAlternatives"));
        var allowed = relations(expected.required("allowed"));
        // An explicitly enumerated alternative can discharge the one obligation; do not count it twice.
        var required = new TreeSet<Relation>();
        if (!alternatives.isEmpty()) {
            var selected = alternatives.stream().filter(predictions::contains).findFirst().orElse(alternatives.first());
            required.add(selected);
            allowed.addAll(alternatives);
            allowed.remove(selected);
        }
        var metrics = DirectedRelationMetrics.evaluate(new DirectedRelationMetrics.Reference(required, allowed, Set.of()), predictions, state);
        boolean mismatchObserved = !metrics.falsePositives().isEmpty() || !invalidEvidence.isEmpty();
        result.put("outcome", mismatchObserved ? "FAIL" : metrics.verdict().name());
        // A different type/direction can express a plausible second view. A mismatch against the frozen
        // triple reference is distinct from assigning a role or mandatory application without a source.
        var unsupportedClaims = metrics.falsePositives().stream().filter(edge -> roleAssignment(edge, processes)
                || edge.source().startsWith("UA-") || edge.target().startsWith("UA-")).toList();
        result.put("unsupportedRequiredApplicationOrRole", unsupportedClaims);
        result.put("metrics", metrics);
        result.put("sourceEvidenceMissingOrInvalid", invalidEvidence);
        result.put("unclassifiedOutsideReference", outsideReference);
        result.put("projectedWithinReference", predictions);
        var questions = new TreeSet<String>();
        var unfinishedReasons = new TreeMap<String, Integer>();
        for (JsonNode assessment : search.required("sources")) {
            String question = assessment.required("question").asString();
            if (!question.isBlank()) questions.add(question);
        }
        for (JsonNode task : search.required("result").required("unfinished")) {
            unfinishedReasons.merge(task.required("reason").asString(), 1, Integer::sum);
            String question = task.path("question").asString("");
            if (!question.isBlank()) questions.add(question);
        }
        result.put("observedOpenQuestions", questions);
        result.put("unfinishedReasons", unfinishedReasons);
        result.put("relationCalls", search.required("totalCalls").asInt());
        return result;
    }

    private static boolean unresolvedSource(JsonNode search) {
        for (JsonNode source : search.required("sources")) {
            if (!source.required("question").asString().isBlank()) return true;
        }
        return false;
    }

    private static boolean completeProgress(JsonNode search) {
        JsonNode progress = search.path("progress");
        return progress.isObject() && progress.required("totalSources").asInt() == progress.required("assessedSources").asInt()
                && progress.required("totalSearches").asInt() == progress.required("completedSearches").asInt()
                && progress.required("pendingSearches").asInt() == 0 && progress.required("unresolvedSearches").asInt() == 0;
    }

    private static boolean roleAssignment(Relation edge, Set<String> processes) {
        if (!edge.type().equals("ASSIGNED_TO")) return false;
        return edge.source().startsWith("BR-") && processes.contains(edge.target())
                || edge.target().startsWith("BR-") && processes.contains(edge.source());
    }

    private static TreeSet<Relation> relations(JsonNode entries) {
        var result = new TreeSet<Relation>();
        for (var entry : entries) result.add(new Relation(entry.required("source").asString(),
                entry.required("type").asString(), entry.required("target").asString()));
        return result;
    }

    private static boolean sourceBacked(JsonNode projected, Relation relation, String original) {
        for (var edge : projected.required("requirementEvidence")) {
            JsonNode decision = edge.required("evidence");
            JsonNode contribution = edge.required("contribution");
            String first = contribution.required("source").required("id").asString();
            String second = edge.required("target").required("id").asString();
            String direction = edge.required("direction").asString();
            boolean endpoints = switch (direction) {
                case "OUTGOING" -> relation.source().equals(first) && relation.target().equals(second);
                case "INCOMING" -> relation.source().equals(second) && relation.target().equals(first);
                default -> false;
            };
            if (endpoints && relation.type().equals(edge.required("type").asString())
                    && second.equals(decision.required("targetId").asString())
                    && "VERIFIED".equals(decision.required("outcome").asString())
                    && "REQUIRED".equals(decision.required("necessity").asString())
                    && quoted(original, contribution) && quoted(original, decision)) return true;
        }
        return false;
    }

    private static boolean quoted(String original, JsonNode claim) {
        String quote = claim.required("quote").asString();
        return !quote.isBlank() && original.contains(quote);
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
