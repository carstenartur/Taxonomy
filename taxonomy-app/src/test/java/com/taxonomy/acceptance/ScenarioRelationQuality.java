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
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.taxonomy.acceptance.DirectedRelationMetrics.*;

/** Post-processes the existing scenario application scenario; never calls a provider or supplies its answers. */
public final class ScenarioRelationQuality {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CASE_ID = "flood-information-v1";
    private static final String REFERENCE_RESOURCE = "/scenarios/flood-quality-reference.json";
    private static final List<String> REPORTS = List.of("relation-quality.json", "relation-quality.csv", "relation-quality.html");
    private static final QualityReference ORACLE = loadReference();

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
        if (!ORACLE.requirementId().equals(text(summary, "requirementKey"))
                || !ORACLE.originalSha256().equals(fingerprint(search, "originalSha256"))) {
            throw new IllegalArgumentException("Requirement identity or original text differs from the independent reference");
        }
        String status = text(analysis, "status");
        if (!status.equals(text(summary, "status"))) throw new IllegalArgumentException("Conflicting snapshot states");
        RunState reportedState = RunState.valueOf(status);
        RunState state = reportedState;
        JsonNode unfinished = array(object(search, "result"), "unfinished");
        JsonNode warnings = array(search, "warnings");
        JsonNode analysisWarnings = array(analysis, "warnings");
        String stopReason = textValue(search, "stopReason");
        Completeness completeness = completeness(search);
        // Exhausting a navigation queue is not proof of semantic completeness.
        // Unclassified analysis warnings require review. They do not prove a wrong
        // relationship, but must not silently certify complete evidence either.
        if (state == RunState.SUCCESS && (!unfinished.isEmpty() || !warnings.isEmpty()
                || !analysisWarnings.isEmpty() || !stopReason.isEmpty() || !completeness.complete())) {
            state = RunState.PARTIAL;
        }
        var predictions = new ArrayList<Relation>();
        for (JsonNode relation : array(object(analysis, "architectureView"), "includedRelationships")) {
            predictions.add(new Relation(text(relation, "sourceCode"), text(relation, "relationType"), text(relation, "targetCode")));
        }
        int optionalContexts = validateOptionalContexts(search);
        List<Map<String, Object>> openPoints = acceptedOpenPoints(search, analysisWarnings, reportedState, completeness);
        Evaluation evaluation = evaluate(ORACLE.relations(), predictions, state);
        if (!openPoints.isEmpty()) {
            // The exact, independently declared noncore identity question does not
            // invalidate a complete functional topology. Keep PARTIAL and null
            // rates: this is not a claim of search exhaustion or measured recall.
            Verdict functional = evaluation.falsePositives().isEmpty() && evaluation.notObservedRequired().isEmpty()
                    ? Verdict.PASS : Verdict.FAIL;
            evaluation = new Evaluation(state, functional, evaluation.requiredCount(), evaluation.predictedCount(),
                    evaluation.matchedRequired(), evaluation.matchedAllowed(), evaluation.unresolvedReferenceCount(),
                    evaluation.falsePositives(), evaluation.notObservedRequired(), evaluation.unresolvedPredictions(), null, null);
        }
        boolean strictComplete = reportedState == RunState.SUCCESS && completeness.complete() && unfinished.isEmpty()
                && warnings.isEmpty() && analysisWarnings.isEmpty() && stopReason.isEmpty();
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", 1);
        report.put("referenceVersion", ORACLE.version());
        report.put("referenceResource", REFERENCE_RESOURCE);
        report.put("referenceSha256", ORACLE.sha256());
        report.put("evidenceKind", "AUTHORED_PLAYBACK_NOT_LIVE_MODEL");
        report.put("evaluationScope", "DIRECTED_RELATIONS_AND_SOURCE_COMPLETENESS");
        report.put("outcome", evaluation.verdict() == Verdict.PASS && !openPoints.isEmpty()
                ? "PASS_WITH_OPEN_POINTS" : evaluation.verdict().name());
        report.put("functionalAcceptanceComplete", evaluation.verdict() == Verdict.PASS);
        report.put("validatedOptionalContextCount", optionalContexts);
        report.put("openPoints", openPoints);
        report.put("openRelationBindingCount", openPoints.size());
        report.put("openQuestionGroupCount", openPoints.stream().map(point -> point.get("questionGroup")).distinct().count());
        report.put("coverageRequiredCount", ORACLE.constraintCount());
        report.put("coverageAssessment", "SEPARATE_SNAPSHOT_AND_EXPORT_GATE");
        report.put("caseId", caseId);
        report.put("fixtureSha256", fingerprint(run, "fixtureSha256"));
        report.put("snapshotId", text(summary, "id"));
        report.put("snapshotSha256", sha256(snapshotBytes));
        report.put("sourceRevision", text(run, "sourceRevision"));
        report.put("originalSha256", ORACLE.originalSha256());
        report.put("taxonomyFingerprint", fingerprint(summary, "taxonomyFingerprint"));
        report.put("promptFingerprint", fingerprint(summary, "promptFingerprint"));
        report.put("totalScenarioLlmCalls", count(run, "llmCalls"));
        report.put("relationCalls", count(search, "totalCalls"));
        report.put("relationCallBudget", count(search, "maxCalls"));
        report.put("relationDurationMillis", count(search, "durationMillis"));
        report.put("unfinishedSearchCount", unfinished.size());
        report.put("analysisWarningCount", analysisWarnings.size());
        report.put("relationWarningCount", warnings.size());
        report.put("stopReasonPresent", !stopReason.isEmpty());
        report.put("totalSourceCount", completeness.totalSources());
        report.put("assessedSourceCount", completeness.assessedSources());
        report.put("retainedSourceCount", completeness.retainedSources());
        report.put("unassessedSourceCount", Math.max(0, completeness.totalSources() - completeness.assessedSources()));
        report.put("totalSearchCount", completeness.totalSearches());
        report.put("completedSearchCount", completeness.completedSearches());
        report.put("pendingSearchCount", completeness.pendingSearches());
        report.put("unresolvedSearchCount", completeness.unresolvedSearches());
        report.put("searchExhausted", completeness.exhausted());
        report.put("sourceAndSearchEvidenceComplete", strictComplete);
        report.put("rejectedRequiredClaimsObserved", predictions.stream().filter(ORACLE.rejected()::contains).sorted().toList());
        report.put("reference", ORACLE.relations());
        report.put("evaluation", evaluation);
        String renderedJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";
        // Inputs, cases and comparison ordering are fixed; there is no timestamp or random output ID.
        Files.writeString(directory.resolve("relation-quality.csv"), csv(evaluation), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("relation-quality.html"), html(renderedJson), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("relation-quality.json"), renderedJson, StandardCharsets.UTF_8);
        return evaluation;
    }

    private record QualityReference(String version, String sha256, String requirementId, String originalSha256,
                                    Reference relations, Set<Relation> rejected, int constraintCount,
                                    Map<Relation, String> necessities, Map<Relation, IdentityQuestion> identityQuestions) { }
    private record IdentityQuestion(String id, String questionGroup, Relation relation, String question, List<String> quoteIds,
                                    List<String> quotes, String rationale) { }

    /** This resource is maintained independently of provider playback answers and never populated from them. */
    private static QualityReference loadReference() {
        try (var input = ScenarioRelationQuality.class.getResourceAsStream(REFERENCE_RESOURCE)) {
            if (input == null) throw new IllegalArgumentException("Independent Flood quality reference is missing");
            byte[] bytes = input.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw new IllegalArgumentException("Independent reference exceeds its byte budget");
            JsonNode reference = JSON.readTree(bytes);
            if (count(reference, "schemaVersion") != 1 || !CASE_ID.equals(text(reference, "caseId"))) {
                throw new IllegalArgumentException("Unknown independent reference schema or case");
            }
            JsonNode requirement = object(reference, "requirement");
            String original = text(requirement, "text");
            var relationIds = new HashSet<String>();
            Set<Relation> required = relations(reference, "requiredRelations", relationIds);
            Set<Relation> allowed = relations(reference, "allowedRelations", relationIds);
            Set<Relation> rejected = relations(reference, "rejectedRequiredClaims", relationIds);
            var necessities = new TreeMap<Relation, String>();
            var optionalOnly = new TreeSet<Relation>();
            for (JsonNode entry : array(reference, "allowedRelations")) {
                if (!entry.has("necessity")) continue;
                String necessity = text(entry, "necessity");
                if (!Set.of("REQUIRED", "OPTIONAL").contains(necessity)) {
                    throw new IllegalArgumentException("Unknown authored reference necessity");
                }
                Relation relation = referenceRelation(entry);
                necessities.put(relation, necessity);
                if (necessity.equals("OPTIONAL")) {
                    text(entry, "condition"); // An optional view must retain its selection boundary.
                    optionalOnly.add(relation);
                }
            }
            var requiredViewAllowed = new TreeSet<>(allowed);
            requiredViewAllowed.removeAll(optionalOnly);
            if (!Collections.disjoint(required, rejected) || !Collections.disjoint(requiredViewAllowed, rejected)) {
                throw new IllegalArgumentException("A rejected required claim cannot also be accepted by the oracle");
            }
            var identityQuestions = new TreeMap<Relation, IdentityQuestion>();
            var questionGroups = new TreeMap<String, String>();
            for (JsonNode entry : array(reference, "openIdentityQuestions")) {
                Relation relation = referenceRelation(entry);
                if (!rejected.contains(relation) || required.contains(relation) || allowed.contains(relation)
                        || !relationIds.contains(text(entry, "id"))) {
                    throw new IllegalArgumentException("An accepted open identity must remain a rejected, noncore, unasserted relation");
                }
                validateQuotes(entry, requirement, original);
                IdentityQuestion question = new IdentityQuestion(text(entry, "id"), text(entry, "questionGroup"), relation, text(entry, "question"),
                        stringList(array(entry, "quoteIds")), stringList(array(entry, "quotes")), text(entry, "rationale"));
                String previousQuestion = questionGroups.putIfAbsent(question.questionGroup(), question.question());
                if (previousQuestion != null && !previousQuestion.equals(question.question())) {
                    throw new IllegalArgumentException("A shared identity group cannot hide distinct questions");
                }
                if (identityQuestions.putIfAbsent(relation, question) != null) {
                    throw new IllegalArgumentException("Duplicate open identity reference");
                }
            }
            var constraintIds = new HashSet<String>();
            JsonNode constraints = array(reference, "coverageConstraints");
            for (JsonNode constraint : constraints) {
                if (!constraintIds.add(text(constraint, "id"))) throw new IllegalArgumentException("Duplicate coverage constraint");
                text(constraint, "requiredBehavior");
                JsonNode binding = object(constraint, "binding");
                JsonNode elements = array(binding, "elementIds");
                if (elements.isEmpty()) throw new IllegalArgumentException("Coverage needs an actual element binding");
                for (JsonNode id : array(binding, "relationIds")) {
                    if (!id.isString() || !relationIds.contains(id.stringValue())) {
                        throw new IllegalArgumentException("Coverage refers to an unknown relation");
                    }
                }
            }
            for (String field : List.of("requiredRelations", "allowedRelations", "rejectedRequiredClaims", "coverageConstraints")) {
                for (JsonNode entry : array(reference, field)) validateQuotes(entry, requirement, original);
            }
            return new QualityReference(text(reference, "referenceVersion"), sha256(bytes), text(requirement, "id"),
                    sha256(original.getBytes(StandardCharsets.UTF_8)), new Reference(required, requiredViewAllowed, Set.of()),
                    Set.copyOf(rejected), constraints.size(), Map.copyOf(necessities), Map.copyOf(identityQuestions));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read independent Flood quality reference", failure);
        }
    }

    private static Set<Relation> relations(JsonNode reference, String field, Set<String> ids) {
        var values = new TreeSet<Relation>();
        for (JsonNode entry : array(reference, field)) {
            if (!ids.add(text(entry, "id")) || !values.add(new Relation(text(entry, "sourceId"),
                    text(entry, "type"), text(entry, "targetId")))) {
                throw new IllegalArgumentException("Duplicate reference identity or directed relation");
            }
        }
        return values;
    }

    private static Relation referenceRelation(JsonNode value) {
        return new Relation(text(value, "sourceId"), text(value, "type"), text(value, "targetId"));
    }

    private static List<String> stringList(JsonNode array) {
        var result = new ArrayList<String>();
        for (JsonNode value : array) {
            if (!value.isString() || value.asString().isBlank()) throw new IllegalArgumentException("Expected nonblank text list");
            result.add(value.asString());
        }
        return List.copyOf(result);
    }

    private static Relation oriented(String source, String type, String direction, String target) {
        return switch (direction) {
            case "OUTGOING" -> new Relation(source, type, target);
            case "INCOMING" -> new Relation(target, type, source);
            default -> throw new IllegalArgumentException("Unknown relation evidence direction");
        };
    }

    /** Checks retained options too: dropping them from the required projection is not validation. */
    private static int validateOptionalContexts(JsonNode search) {
        int optional = 0;
        for (JsonNode edge : array(object(search, "result"), "edges")) {
            String source = text(object(object(edge, "contribution"), "source"), "id");
            Relation relation = oriented(source, text(edge, "type"), text(edge, "direction"), text(object(edge, "target"), "id"));
            JsonNode evidence = object(edge, "evidence");
            String actual = text(evidence, "necessity");
            String expected = ORACLE.necessities().get(relation);
            if (ORACLE.identityQuestions().containsKey(relation)
                    || ORACLE.rejected().contains(relation) && actual.equals("REQUIRED")) {
                throw new IllegalArgumentException("Rejected required or unresolved-identity claim is asserted in retained evidence: " + relation);
            }
            if (expected == null) continue;
            if (!expected.equals(actual) || !"VERIFIED".equals(text(evidence, "outcome"))
                    || !textValue(evidence, "question").isEmpty()) {
                throw new IllegalArgumentException("Reference necessity differs from retained relationship evidence: " + relation);
            }
            if (expected.equals("OPTIONAL")) {
                text(evidence, "condition");
                optional++;
            }
        }
        return optional;
    }

    /** This exception accepts a named open identity, never an arbitrary partial run or source gap. */
    private static List<Map<String, Object>> acceptedOpenPoints(JsonNode search, JsonNode warnings,
                                                               RunState reportedState, Completeness complete) {
        if (reportedState != RunState.PARTIAL || complete.exhausted() || complete.sourceQuestions()
                || complete.totalSources() <= 0 || complete.totalSources() != complete.assessedSources()
                || complete.assessedSources() != complete.retainedSources() || complete.pendingSearches() != 0
                || complete.unresolvedSearches() <= 0
                || complete.totalSearches() != complete.completedSearches() + complete.unresolvedSearches()
                || !array(search, "warnings").isEmpty() || !textValue(search, "stopReason").isEmpty()) return List.of();
        JsonNode result = object(search, "result"), unfinished = array(result, "unfinished");
        if (unfinished.isEmpty() || !result.path("trace").isArray() || !search.path("tasks").isArray()) return List.of();
        var grouped = new TreeMap<Relation, List<Map<String, String>>>();
        var openTasks = new TreeSet<String>();
        var unresolvedScopes = new TreeSet<String>();
        var questionWarnings = new ArrayList<String>();
        for (JsonNode item : unfinished) {
            if (!"UNRESOLVED".equals(item.path("reason").asString()) || !item.path("targets").isArray()
                    || item.path("targets").size() != 1) return List.of();
            String source = item.path("sourceId").asString(), target = item.path("targets").get(0).asString();
            String type = item.path("type").asString(), direction = item.path("direction").asString();
            if (!Set.of("OUTGOING", "INCOMING").contains(direction)) return List.of();
            Relation relation = oriented(source, type, direction, target);
            IdentityQuestion reference = ORACLE.identityQuestions().get(relation);
            if (reference == null || !reference.question().equals(item.path("question").asString())) return List.of();
            String scope = source + ":" + type + ":" + direction + ":" + target;
            if (!unresolvedScopes.add(scope)) return List.of();
            boolean traced = false;
            for (JsonNode trace : result.path("trace")) {
                if (scope.equals(traceScope(trace)) && "UNRESOLVED".equals(trace.path("outcome").asString())
                        && Set.of("NAVIGATE", "VERIFY").contains(trace.path("phase").asString())
                        && !trace.path("rationale").asString().isBlank()) traced = true;
            }
            if (!traced) return List.of();
            String targetRoot = target.substring(0, target.indexOf('-'));
            openTasks.add(source + ":" + targetRoot);
            questionWarnings.add(source + ": " + reference.question());
            grouped.computeIfAbsent(relation, ignored -> new ArrayList<>()).add(Map.of(
                    "sourceId", source, "type", type, "direction", direction, "targetId", target,
                    "reason", "UNRESOLVED", "question", reference.question()));
        }
        for (JsonNode trace : result.path("trace")) {
            if ("UNRESOLVED".equals(trace.path("outcome").asString()) && !unresolvedScopes.contains(traceScope(trace))) return List.of();
        }
        var retainedSources = new TreeSet<String>();
        for (JsonNode source : array(search, "sources")) retainedSources.add(text(object(source, "node"), "id"));
        var tasks = new TreeSet<String>(); var observedOpenTasks = new TreeSet<String>();
        int completed = 0;
        for (JsonNode task : search.path("tasks")) {
            String source = task.path("sourceId").asString(), target = task.path("targetRoot").asString();
            String key = source + ":" + target;
            if (!retainedSources.contains(source) || target.isBlank() || !tasks.add(key)) return List.of();
            if (openTasks.contains(key) && "UNRESOLVED".equals(task.path("state").asString())) observedOpenTasks.add(key);
            else if (!openTasks.contains(key) && "COMPLETED".equals(task.path("state").asString())) completed++;
            else return List.of();
        }
        if (!observedOpenTasks.equals(openTasks) || tasks.size() != complete.totalSearches()
                || completed != complete.completedSearches() || openTasks.size() != complete.unresolvedSearches()) return List.of();
        var expectedWarnings = new ArrayList<String>();
        expectedWarnings.add("RELATION_SEARCH_PARTIAL: " + count(search, "totalCalls") + "/" + count(search, "maxCalls")
                + " evaluation attempts; " + unfinished.size() + " unfinished search batches. 0 unassessed sources; 0 pending and "
                + complete.unresolvedSearches() + " unresolved source/target searches.");
        // Match the real analysis adapter's display boundary. It retains the
        // first eight unfinished questions in order, including repeated source
        // labels; every undisplayed item was still validated above in full.
        expectedWarnings.addAll(questionWarnings.stream().limit(8).toList());
        List<String> warningValues = stringList(warnings);
        if (!warningValues.equals(expectedWarnings)) return List.of();
        var points = new ArrayList<Map<String, Object>>();
        for (var entry : grouped.entrySet()) {
            IdentityQuestion question = ORACLE.identityQuestions().get(entry.getKey());
            var point = new LinkedHashMap<String, Object>();
            point.put("id", question.id()); point.put("sourceId", question.relation().source());
            point.put("questionGroup", question.questionGroup());
            point.put("type", question.relation().type()); point.put("targetId", question.relation().target());
            point.put("question", question.question()); point.put("quoteIds", question.quoteIds()); point.put("quotes", question.quotes());
            point.put("rationale", question.rationale()); point.put("evidenceCount", entry.getValue().size());
            point.put("evidence", List.copyOf(entry.getValue())); points.add(Map.copyOf(point));
        }
        return List.copyOf(points);
    }

    private static String traceScope(JsonNode trace) {
        return trace.path("sourceId").asString() + ":" + trace.path("type").asString() + ":"
                + trace.path("direction").asString() + ":" + trace.path("targetId").asString();
    }

    private static void validateQuotes(JsonNode entry, JsonNode requirement, String original) {
        JsonNode ids = array(entry, "quoteIds"), quotes = array(entry, "quotes");
        if (ids.isEmpty() || ids.size() != quotes.size()) throw new IllegalArgumentException("Reference quote bindings are incomplete");
        for (int index = 0; index < ids.size(); index++) {
            JsonNode id = ids.get(index), quote = quotes.get(index);
            if (!id.isString() || !quote.isString() || quote.stringValue().isBlank()
                    || !text(object(requirement, "clauses"), id.stringValue()).equals(quote.stringValue())
                    || !original.contains(quote.stringValue())) {
                throw new IllegalArgumentException("Reference evidence is not a verbatim original clause");
            }
        }
    }

    private record Completeness(long totalSources, long assessedSources, int retainedSources,
                                long totalSearches, long completedSearches, long unresolvedSearches,
                                long pendingSearches, boolean exhausted, boolean sourceQuestions) {
        boolean complete() {
            return totalSources > 0 && totalSources == assessedSources && assessedSources == retainedSources
                    && totalSearches == completedSearches && pendingSearches == 0 && unresolvedSearches == 0
                    && exhausted && !sourceQuestions;
        }
    }

    private static Completeness completeness(JsonNode search) {
        JsonNode progress = object(search, "progress"), sources = array(search, "sources");
        JsonNode exhausted = search.path("searchExhausted");
        if (!exhausted.isBoolean()) throw new IllegalArgumentException("Missing explicit search exhaustion evidence");
        var ids = new HashSet<String>();
        boolean questions = false;
        for (JsonNode source : sources) {
            if (!ids.add(text(object(source, "node"), "id"))) throw new IllegalArgumentException("Duplicate assessed source identity");
            questions |= !textValue(source, "question").isBlank();
        }
        return new Completeness(count(progress, "totalSources"), count(progress, "assessedSources"), sources.size(),
                count(progress, "totalSearches"), count(progress, "completedSearches"), count(progress, "unresolvedSearches"),
                count(progress, "pendingSearches"), exhausted.booleanValue(), questions);
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
                + "Null rates mean undefined, not perfect quality. "
                + "A relation PASS does not certify functional coverage or delivered architecture quality: "
                + "the 20 element-bound constraints require the separate snapshot and Sparx export gate.</p><pre>"
                + escaped + "</pre></html>\n";
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
