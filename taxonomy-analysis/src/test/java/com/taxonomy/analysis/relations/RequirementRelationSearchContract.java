package com.taxonomy.analysis.relations;

import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.taxonomy.dto.RelationSearchModel.*;
import static com.taxonomy.analysis.relations.RelationSearchContract.check;

/** Executes extraction, routing, descent and separate verification through actual protocol code. */
public final class RequirementRelationSearchContract {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Node SOURCE = new Node("process", "BP", "Review evidence", "A review process", false);
    static final Node ROOT = new Node("IP", "IP", "Information products", "Catalogue category", true);
    static final Node TARGET = new Node("evidence", "IP", "Evidence record", "Record of completed measures", false);
    static final String READ = "The process reads evidence.";
    static final String WRITE = "The process reads and writes evidence.";
    static final RequirementRelationSearch.Options OPTIONS = new RequirementRelationSearch.Options(new Limits(30, 8, 10, 128), 16);

    public static void main(String[] args) throws Exception {
        int passed = 0; List<String> failed = new ArrayList<>();
        for (var m : RequirementRelationSearchContract.class.getDeclaredMethods()) if (m.getName().startsWith("test")) {
            try { m.invoke(null); passed++; } catch (ReflectiveOperationException ex) { failed.add(m.getName() + ": " + ex.getCause()); }
        }
        failed.forEach(System.err::println);
        System.out.println("Relation session contracts: " + passed + " passed, " + failed.size() + " failed");
        if (!failed.isEmpty()) throw new AssertionError(failed.size() + " failed");
    }
    public static void testSameScoresReadAndWriteHaveDifferentEdges() {
        var read = session(RequirementRelationSearchContract::answer).search(READ, Map.of("process", 1), OPTIONS);
        var write = session(RequirementRelationSearchContract::answer).search(WRITE, Map.of("process", 1), OPTIONS);
        check(types(read).equals(Set.of("CONSUMES")), "only reads despite small positive score");
        check(types(write).equals(Set.of("CONSUMES", "PRODUCES")), "write has an independently justified production edge");
        check(!read.originalSha256().equals(write.originalSha256()), "original identity retained");
    }
    public static void testUnscoredTargetAndNavigationRootsAreNotConflated() {
        var report = session(RequirementRelationSearchContract::answer).search(READ, Map.of("process", 10), OPTIONS);
        check(report.result().edges().size() == 1 && report.result().edges().getFirst().targetId().equals("evidence"), "unscored concrete target");
        check(report.result().trace().stream().anyMatch(t -> t.targetId().equals("IP") && t.outcome() == Outcome.DESCEND), "navigation retained separately");
    }
    public static void testTotalBudgetIncludesExtractionAndVerification() {
        AtomicInteger raw = new AtomicInteger();
        var report = session(p -> { raw.incrementAndGet(); return answer(p); }).search(READ, Map.of("process", 10),
                new RequirementRelationSearch.Options(new Limits(3, 8, 10, 128), 16));
        check(raw.get() == 3 && report.totalCalls() == 3, "extraction counts against shared budget");
        check(report.result().edges().isEmpty() && !report.isSearchExhausted(), "no budget for verification");
    }
    public static void testZeroBudgetDoesNotCallModel() {
        var r = session(p -> { throw new AssertionError("no model call"); }).search(READ, Map.of("process", 10),
                new RequirementRelationSearch.Options(new Limits(0, 8, 10, 128), 16));
        check(r.totalCalls() == 0 && !r.isSearchExhausted(), "explicit extraction budget stop");
    }
    public static void testInvalidExtractionDoesNotFallbackToScores() {
        var r = session(p -> "Please try again").search(READ, Map.of("process", 100, "evidence", 100), OPTIONS);
        check(r.result().edges().isEmpty() && !r.warnings().isEmpty(), "malformed extraction cannot invent edges");
    }
    public static void testAllNonpositiveScoresUseNoCalls() {
        var r = session(p -> { throw new AssertionError("no positive source"); }).search(READ, Map.of("process", 0), OPTIONS);
        check(r.totalCalls() == 0 && r.result().edges().isEmpty(), "no search without a positive source");
    }
    public static void testRootOnlyEvidenceIsReportedNotTreatedAsComponent() {
        var r = session(p -> { throw new AssertionError("container is not a source component"); }).search(READ, Map.of("IP", 10), OPTIONS);
        check(r.result().edges().isEmpty() && !r.isSearchExhausted(), "root has no concrete source");
    }
    public static void testMissingCatalogueSourceIsVisible() {
        var r = session(p -> { throw new AssertionError("missing source"); }).search(READ, Map.of("absent", 5), OPTIONS);
        check(!r.warnings().isEmpty() && !r.isSearchExhausted(), "missing catalogue identity is not ignored");
    }
    public static void testRejectedSourceRetainsRationale() {
        var r = session(p -> "{\"selections\":[{\"nodeId\":\"process\",\"outcome\":\"REJECT\",\"contributions\":[],\"rationale\":\"Not requested by this original\",\"question\":\"\"}]}").search(READ, Map.of("process", 5), OPTIONS);
        check(r.sources().size() == 1 && r.sources().getFirst().rationale().contains("Not requested"), "source assessment evidence survives");
    }
    public static void testCooperativeStopHasPartialReport() {
        var r = new RequirementRelationSearch(catalogue(), new RelationCompatibilityMatrix(), RequirementRelationSearchContract::answer,
                () -> { throw new com.taxonomy.analysis.service.AnalysisStoppedException(com.taxonomy.analysis.service.AnalysisStoppedException.Reason.CANCELLED); }).search(READ, Map.of("process", 5), OPTIONS);
        check(r.totalCalls() == 0 && r.stopReason().startsWith("CANCELLED") && !r.isSearchExhausted(), "pre-call stop visible");
    }
    public static void testSnapshotJsonRoundtripKeepsAllEvidence() {
        var r = session(RequirementRelationSearchContract::answer).search(WRITE, Map.of("process", 5), OPTIONS);
        var copy = JSON.readValue(JSON.writeValueAsString(r), RelationSearchReport.class);
        check(!copy.result().edges().isEmpty() && copy.result().edges().equals(r.result().edges()) && copy.sources().equals(r.sources()), "typed JSON snapshot roundtrip");
    }
    static RequirementRelationSearch session(java.util.function.Function<String,String> completion) {
        return new RequirementRelationSearch(catalogue(), new RelationCompatibilityMatrix(), completion, () -> { });
    }
    static RequirementRelationSearch.InputCatalogue catalogue() {
        return new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return Map.of("process", SOURCE, "IP", ROOT, "evidence", TARGET).get(id); }
            public List<Node> roots() { return List.of(ROOT); }
            public List<Node> children(Node n) { return n.equals(ROOT) ? List.of(TARGET) : List.of(); }
        };
    }
    static Set<String> types(RelationSearchReport r) {
        Set<String> result = new TreeSet<>(); r.result().edges().forEach(e -> result.add(e.type())); return result;
    }
    static String answer(String prompt) {
        JsonNode input = JSON.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
        String original = input.path("original").asString();
        if (input.has("nodes")) {
            List<Map<String,Object>> selections = new ArrayList<>();
            for (JsonNode node : input.get("nodes")) selections.add(Map.of("nodeId", node.get("id").asString(), "outcome", "EXPLICIT",
                    "contributions", List.of(Map.of("text", "requested evidence access", "quote", original, "condition", "")), "rationale", "Exact access scope", "question", ""));
            return JSON.writeValueAsString(Map.of("selections", selections));
        }
        Query q = JSON.treeToValue(input, Query.class);
        List<Decision> decisions = new ArrayList<>();
        for (Node node : q.candidates()) {
            Outcome outcome = q.type().equals("CONSUMES") || (q.type().equals("PRODUCES") && original.contains("writes"))
                    ? q.phase() == Phase.VERIFY ? Outcome.VERIFIED : node.container() ? Outcome.DESCEND : Outcome.MATCH : Outcome.REJECT;
            decisions.add(new Decision(node.id(), outcome, "evidence record access", original, Necessity.REQUIRED, "", "", "Original requests this exact access", ""));
        }
        return JSON.writeValueAsString(Map.of("decisions", decisions));
    }
}
