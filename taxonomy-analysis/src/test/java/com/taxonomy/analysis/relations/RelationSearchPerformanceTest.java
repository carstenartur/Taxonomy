package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.analysis.service.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.taxonomy.dto.RelationSearchModel.*;
import static com.taxonomy.dto.RelationSearchProgress.*;
import static org.junit.jupiter.api.Assertions.*;

class RelationSearchPerformanceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGINAL = RequirementRelationSearchContract.READ;
    private static final Node SOURCE = RequirementRelationSearchContract.SOURCE;
    private static final Node ROOT = RequirementRelationSearchContract.ROOT;
    private static final Node TARGET = RequirementRelationSearchContract.TARGET;

    @Test void repeatedDescentsLoadScalarsOnceAndPreserveEveryAssessment() {
        assertCatalogueWork(true, 13);
    }

    @Test void emptyChildListsAreCachedOnlyWithinTheirSearch() {
        assertCatalogueWork(false, 7);
    }

    private static void assertCatalogueWork(boolean populated, int expectedCalls) {
        var prompts = new ArrayList<String>();
        var childLoads = new AtomicInteger();
        var descriptions = new AtomicInteger();
        var catalogue = new TaxonomyService(null, null, null) {
            @Override public TaxonomyNode getNodeByCode(String id) { return entity(SOURCE); }
            @Override public List<TaxonomyNode> getRootNodes() { return List.of(entity(ROOT)); }
            @Override public List<TaxonomyNode> getChildrenOf(String id) {
                childLoads.incrementAndGet();
                return populated ? List.of(entity(TARGET)) : List.of();
            }
            @Override public Map<String, String> getAssessmentDescriptions(List<TaxonomyNode> nodes) {
                descriptions.incrementAndGet();
                var result = new LinkedHashMap<String, String>();
                nodes.forEach(node -> result.put(node.getCode(), node.getDescriptionEn()));
                return result;
            }
        };
        var llm = new LlmService(null, null, JSON, null, null, null, null) {
            @Override public LlmProvider getActiveProvider() { return LlmProvider.CUSTOM_OPENAI; }
            @Override public String getActiveProviderName() { return "TEST"; }
            @Override public String callLlmRaw(String prompt) { prompts.add(prompt); return answer(prompt); }
        };
        var budget = new AiPromptBudgetPolicy(null) {
            @Override public com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor requireWithinBudget(String text, String provider) { return null; }
        };
        var service = new RequirementRelationSearchService(catalogue, new RelationCompatibilityMatrix(), llm, budget);
        for (int run = 1; run <= 2; run++) {
            prompts.clear();
            var actual = service.search(ORIGINAL, Map.of(SOURCE.id(), 1));
            var referencePrompts = new ArrayList<String>();
            var referenceLoads = new AtomicInteger();
            var reference = new RequirementRelationSearch(new RequirementRelationSearch.InputCatalogue() {
                public Node find(String id) { return SOURCE; }
                public List<Node> roots() { return List.of(ROOT); }
                public List<Node> children(Node node) {
                    referenceLoads.incrementAndGet();
                    return populated ? List.of(TARGET) : List.of();
                }
            }, new RelationCompatibilityMatrix(), prompt -> {
                referencePrompts.add(prompt); return answer(prompt);
            }, () -> {}, "TEST").search(ORIGINAL, Map.of(SOURCE.id(), 1),
                    new RequirementRelationSearch.Options(new Limits(24, 8, 10, 512), 32));
            assertEquals(referencePrompts, prompts, "prompts and evaluation order");
            assertEquals(normalize(reference), normalize(actual), "all evidence except elapsed time");
            assertEquals(expectedCalls, actual.totalCalls());
            assertEquals(3, referenceLoads.get(), "uncached fixture operation count");
            assertEquals(run, childLoads.get(), "one child read per run, including empty lists");
            assertEquals(3 * run, descriptions.get(), "root, source and one child conversion per run");
        }
    }

    private static TaxonomyNode entity(Node node) {
        var result = new TaxonomyNode();
        result.setCode(node.id()); result.setTaxonomyRoot(node.root());
        result.setNameEn(node.name()); result.setDescriptionEn(node.description());
        result.setParentCode(node.container() ? null : node.root());
        return result;
    }

    private static String answer(String prompt) {
        var input = JSON.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
        if (!input.has("nodes")) return RequirementRelationSearchContract.answer(prompt);
        var parts = new ArrayList<Map<String, String>>();
        for (int i = 0; i < 3; i++) parts.add(Map.of("text", "requested evidence access " + i,
                "quote", ORIGINAL, "condition", ""));
        return JSON.writeValueAsString(Map.of("selections", List.of(Map.of("nodeId", SOURCE.id(),
                "outcome", "EXPLICIT", "contributions", parts, "rationale", "Exact access scope", "question", ""))));
    }

    private static Object normalize(RelationSearchReport report) {
        var normalized = JSON.valueToTree(report);
        ((tools.jackson.databind.node.ObjectNode) normalized).remove("durationMillis");
        ((tools.jackson.databind.node.ObjectNode) normalized.get("result")).remove("durationMillis");
        return normalized;
    }

    @Test void freshAnswerIsDecodedOnceBeforeDurableSuccessAndReplayIsDecodedOnce() throws Exception {
        var journal = new Journal();
        var completions = new AtomicInteger();
        var decodes = new AtomicInteger();
        var protocol = new RelationSearchProtocol((step, nodes, prompt) -> {
            completions.incrementAndGet(); return "retained raw";
        }, "TEST");
        Function<String, String> decoder = raw -> { decodes.incrementAndGet(); return "decoded:" + raw; };
        journal.beforeSuccess = () -> assertEquals(1, decodes.get(), "validated before durable success");
        try (var ignored = new AnalysisCheckpointSession(journal)) {
            assertEquals("decoded:retained raw", exchange(protocol, decoder));
            assertEquals(1, decodes.get(), "fresh response parsed once");
            journal.beforeSuccess = () -> fail("replay cannot commit another provider answer");
            decodes.set(0);
            assertEquals("decoded:retained raw", exchange(protocol, decoder));
            assertEquals(1, decodes.get(), "replay still decoded and validated");
            assertEquals(1, completions.get());
        }
    }

    private static Object exchange(RelationSearchProtocol protocol, Function<String, String> decoder) throws Exception {
        var method = RelationSearchProtocol.class.getDeclaredMethod("exchange", Step.class, List.class,
                String.class, Function.class, Predicate.class);
        method.setAccessible(true);
        try { return method.invoke(protocol, Step.NAVIGATE, List.of(ROOT.id()), "identical prompt", decoder, (Predicate<String>) value -> false); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }

    @Test void freshInvalidAnswerIsDecodedOnceWithoutCheckpoint() throws Exception {
        var decodes = new AtomicInteger();
        var protocol = new RelationSearchProtocol((step, nodes, prompt) -> "malformed", "TEST");
        var invalid = new RelationSearchEngine.InvalidResponseException("invalid fixture response");
        var failure = assertThrows(RelationSearchEngine.InvalidResponseException.class, () -> exchange(protocol, raw -> {
            decodes.incrementAndGet(); throw invalid;
        }));
        assertSame(invalid, failure);
        assertEquals(1, decodes.get(), "invalid fresh response is not parsed again");
    }

    @Test void invalidAndUnresolvedProviderAnswersNeverBecomeDurableSuccess() {
        var query = query(SOURCE, ROOT, "CONSUMES");
        for (String response : List.of("{", "{\"decisions\":[]}", JSON.writeValueAsString(Map.of("decisions",
                List.of(new Decision(ROOT.id(), Outcome.UNRESOLVED, "", "", null, "", "", "Uncertain", "Which record?")))))) {
            var journal = new Journal();
            var protocol = new RelationSearchProtocol((step, nodes, prompt) -> response, "TEST");
            try (var ignored = new AnalysisCheckpointSession(journal)) {
                assertThrows(AnalysisStoppedException.class, () -> protocol.evaluate(query));
                assertEquals("FAILED", journal.saved.state());
                assertEquals(response, journal.saved.detail().getRawResponse());
            }
        }
    }

    @Test void durableReplayIsStillValidatedAgainstTheOfferedCandidates() {
        var journal = new Journal();
        var detail = new LlmCallDetail(); detail.setRawResponse("{\"decisions\":[]}");
        journal.saved = new AnalysisCheckpointSession.Checkpoint(null, "SUCCESS", detail);
        var protocol = new RelationSearchProtocol((step, nodes, prompt) -> { throw new AssertionError("replay called provider"); }, "TEST");
        try (var ignored = new AnalysisCheckpointSession(journal)) {
            assertThrows(RelationSearchEngine.InvalidResponseException.class, () -> protocol.evaluate(query(SOURCE, ROOT, "CONSUMES")));
        }
    }

    private static final class Journal implements AnalysisCheckpointSession.Store {
        AnalysisCheckpointSession.Checkpoint saved;
        Runnable beforeSuccess = () -> {};
        public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
            return saved == null ? new AnalysisCheckpointSession.Checkpoint(question, "ATTEMPT", null) : saved;
        }
        public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) {
            if (state.equals("SUCCESS")) beforeSuccess.run();
            saved = new AnalysisCheckpointSession.Checkpoint(question, state, detail);
        }
        public void checkActive() {}
    }

    @Test void progressAggregatesMatchTaskStatesThroughoutRestoreAndTransitions() {
        Node otherRoot = new Node("CR", "CR", "Services", "", true);
        var rejected = new Node("rejected", "BP", "Rejected", "", false);
        var unknown = new Node("unknown", "BP", "Unknown", "", false);
        var later = new Node("later", "BP", "Later", "", false);
        var pending = new Node("pending", "BP", "Pending", "", false);
        var plan = new RelationWorkPlan(List.of(SOURCE, rejected, unknown, later, pending), List.of(ROOT, otherRoot),
                new RelationCompatibilityMatrix(), 24);
        var restoredTasks = plan.tasks().stream().map(task -> new Task(task.sourceId(), task.targetRoot(),
                task.sourceId().equals(later.id()) ? State.COMPLETED : task.state())).toList();
        var previous = new RelationSearchReport(2, "hash", "policy", List.of(), new Result(List.of(), List.of(), List.of(), 0, 0, 0),
                0, 24, 0, List.of(), "", null, restoredTasks);
        plan.restore(previous); assertAggregates(plan);
        assertEquals(2, plan.snapshot().completedSearches(), "restored completed tasks");
        plan.assessed(new SourceAssessment(rejected, List.of(), "Rejected", "")); assertAggregates(plan);
        assertEquals(4, plan.snapshot().completedSearches());
        plan.assessed(new SourceAssessment(rejected, List.of(), "Uncertain on reassessment", "Which?")); assertAggregates(plan);
        assertEquals(2, plan.snapshot().completedSearches());
        assertEquals(2, plan.snapshot().unresolvedSearches());
        plan.assessed(new SourceAssessment(rejected, List.of(), "Rejected", "")); assertAggregates(plan);
        plan.assessed(new SourceAssessment(unknown, List.of(), "Unknown", "Which?")); assertAggregates(plan);
        var first = new Intent(contribution(SOURCE), "CONSUMES", Direction.OUTGOING, List.of(ROOT));
        var second = new Intent(contribution(SOURCE), "PRODUCES", Direction.OUTGOING, List.of(ROOT));
        var other = new Intent(contribution(SOURCE), "USES", Direction.OUTGOING, List.of(otherRoot));
        plan.expect(List.of(first, second, other));
        plan.started(query(SOURCE, ROOT, first.type()), 1); assertAggregates(plan);
        plan.calls(3); assertAggregates(plan);
        plan.finished(first, true); assertAggregates(plan);
        plan.finished(first, true); assertAggregates(plan);
        assertEquals(State.RUNNING, plan.tasks().getFirst().state(), "duplicate finish cannot complete all types");
        plan.finished(second, true); assertAggregates(plan);
        plan.started(query(SOURCE, otherRoot, other.type()), 0);
        plan.finished(other, false); assertAggregates(plan);
        var edge = new Edge(contribution(SOURCE), TARGET, "CONSUMES", Direction.OUTGOING,
                new Decision(TARGET.id(), Outcome.VERIFIED, "access", ORIGINAL, Necessity.REQUIRED, "", "", "Verified", ""));
        plan.verified(edge); plan.verified(edge); assertEquals(1, plan.snapshot().verifiedRelations());
        plan.started(query(pending, otherRoot, "USES"), 0); assertAggregates(plan);
        plan.finish(false); assertAggregates(plan); assertEquals(Step.PAUSED, plan.snapshot().step());
        assertTrue(plan.tasks().stream().filter(task -> task.sourceId().equals(pending.id()))
                .allMatch(task -> task.state() == State.PENDING), "unfinished running work becomes pending");
        plan.finish(true); assertAggregates(plan); assertEquals(Step.FINISHED, plan.snapshot().step());
        assertEquals(5, plan.snapshot().completedSearches());
        assertEquals(3, plan.snapshot().unresolvedSearches());
        assertEquals(2, plan.snapshot().pendingSearches());
    }

    @Test void progressSnapshotsDoNotVisitTheCompleteTaskMap() throws Exception {
        var plan = new RelationWorkPlan(List.of(SOURCE), List.of(ROOT), new RelationCompatibilityMatrix(), 24);
        var field = RelationWorkPlan.class.getDeclaredField("work"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var original = (Map<Object, Object>) field.get(plan);
        var measured = new CountingMap(original); field.set(plan, measured);
        for (int i = 0; i < 20; i++) plan.snapshot();
        assertEquals(0, measured.visited, "snapshots use maintained aggregates, not full task scans");
    }

    private static final class CountingMap extends LinkedHashMap<Object, Object> {
        int visited;
        CountingMap(Map<Object, Object> source) { super(source); }
        @Override public Set<Map.Entry<Object, Object>> entrySet() {
            var entries = super.entrySet();
            return new AbstractSet<>() {
                public int size() { return entries.size(); }
                public Iterator<Map.Entry<Object, Object>> iterator() {
                    var iterator = entries.iterator();
                    return new Iterator<>() {
                        public boolean hasNext() { return iterator.hasNext(); }
                        public Map.Entry<Object, Object> next() { visited++; return iterator.next(); }
                    };
                }
            };
        }
    }

    private static Contribution contribution(Node source) { return new Contribution(source, "access", ORIGINAL, ""); }
    private static Query query(Node source, Node root, String type) {
        return new Query(ORIGINAL, contribution(source), type, Direction.OUTGOING, Phase.NAVIGATE, List.of(root), null);
    }
    private static void assertAggregates(RelationWorkPlan plan) {
        var tasks = plan.tasks(); var snapshot = plan.snapshot();
        assertEquals(tasks.size(), snapshot.totalSearches());
        assertEquals(tasks.stream().filter(task -> task.state() == State.COMPLETED).count(), snapshot.completedSearches());
        assertEquals(tasks.stream().filter(task -> task.state() == State.UNRESOLVED).count(), snapshot.unresolvedSearches());
        assertEquals(tasks.size() - snapshot.completedSearches() - snapshot.unresolvedSearches(), snapshot.pendingSearches());
        for (var taxonomy : snapshot.taxonomies()) {
            var scoped = tasks.stream().filter(task -> task.targetRoot().equals(taxonomy.root())).toList();
            assertEquals(scoped.size(), taxonomy.total());
            assertEquals(scoped.stream().filter(task -> task.state() == State.COMPLETED).count(), taxonomy.completed());
            assertEquals(scoped.stream().filter(task -> task.state() == State.UNRESOLVED).count(), taxonomy.unresolved());
            assertEquals(scoped.size() - taxonomy.completed() - taxonomy.unresolved(), taxonomy.pending());
        }
    }
}
