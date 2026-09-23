package com.taxonomy.analysis.relations;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.taxonomy.dto.RelationSearchModel.*;

/** Runs the real production engine without a Spring context or a remote model. */
public final class RelationSearchContract {
    static final String ORIGINAL = "Readers may read evidence. Editors may read and update evidence. Notify users; channel undecided.";
    static final Node SOURCE = node("app.reader", "UA", false);
    static final Node ROOT = node("IP", "IP", true);
    static final Node FOLDER = node("evidence", "IP", false);
    static final Node LEAF = node("record", "IP", false);
    static final Contribution READ = new Contribution(SOURCE, "read existing evidence", "Readers may read evidence.", "read-only role");
    static final Limits DEFAULTS = new Limits(30, 8, 10, 100);

    public static void main(String[] args) throws Exception {
        int passed = 0; List<String> failures = new ArrayList<>();
        for (var method : RelationSearchContract.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("test")) continue;
            try { method.invoke(null); passed++; }
            catch (ReflectiveOperationException e) { failures.add(method.getName() + ": " + e.getCause()); }
        }
        failures.forEach(System.err::println);
        System.out.println("Relation search contracts: " + passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) throw new AssertionError(failures.size() + " failing contracts");
    }

    public static void testUnstartedRootsDoNotConsumeAllWorkSlots() {
        List<Intent> intents = new ArrayList<>(); intents.add(intent(READ));
        for (int i = 0; i < 20; i++) intents.add(intent(new Contribution(SOURCE, "other " + i, READ.quote(), "")));
        Result r = engine(q -> decisions(q, q.phase() == Phase.VERIFY ? Outcome.VERIFIED
                : q.candidates().getFirst().container() ? Outcome.DESCEND : Outcome.MATCH))
                .search(ORIGINAL, intents, new Limits(3, 8, 10, 3));
        check(r.edges().size() == 1 && !r.unfinished().isEmpty(), "bounded admission must still complete one promising path");
    }

    public static void testNavigationIsNotAnEdge() {
        Result result = engine(q -> decisions(q, q.phase() == Phase.VERIFY ? Outcome.VERIFIED
                : q.candidates().getFirst().container() ? Outcome.DESCEND : Outcome.MATCH)).search(ORIGINAL, List.of(intent(READ)), DEFAULTS);
        check(result.edges().size() == 1, "one verified edge");
        check(result.edges().getFirst().target().equals(FOLDER), "stop before leaf at sufficient detail");
        check(result.calls() == 3, "root navigation, children, then independent verification");
    }

    public static void testZeroBudgetIsNotNoRelation() {
        Result r = engine(q -> { throw new AssertionError("must not call model"); }).search(ORIGINAL, List.of(intent(READ)), new Limits(0, 8, 10, 100));
        check(!r.searchExhausted() && r.calls() == 0, "zero budget retains unfinished work");
        check(r.unfinished().getFirst().reason().equals("CALL_BUDGET"), "specific budget reason");
    }

    public static void testBudgetIncludesVerification() {
        Result r = engine(q -> decisions(q, q.candidates().getFirst().container() ? Outcome.DESCEND : Outcome.MATCH))
                .search(ORIGINAL, List.of(intent(READ)), new Limits(2, 8, 10, 100));
        check(r.edges().isEmpty() && r.calls() == 2 && !r.searchExhausted(), "unverified matches are not edges");
    }

    public static void testRejectDoesNotDescend() {
        Result r = engine(q -> decisions(q, Outcome.REJECT)).search(ORIGINAL, List.of(intent(READ)), DEFAULTS);
        check(r.calls() == 1 && r.edges().isEmpty() && r.searchExhausted(), "pruned, not exhaustively proved");
        check(r.trace().size() == 1 && r.trace().getFirst().outcome() == Outcome.REJECT, "negative decision retained");
    }

    public static void testUnknownIdsInvalidateWholeBatch() {
        Result r = engine(q -> List.of(decision("invented", Outcome.MATCH))).search(ORIGINAL, List.of(intent(READ)), DEFAULTS);
        check(r.edges().isEmpty() && !r.searchExhausted(), "foreign ID cannot become an edge");
        check(r.unfinished().getFirst().reason().equals("INVALID_RESPONSE"), "invalid response, not rejection");
    }

    public static void testMissingDecisionInvalidatesBatch() {
        Result r = engine(q -> List.of()).search(ORIGINAL, List.of(intent(READ)), DEFAULTS);
        check(!r.searchExhausted() && r.calls() == 1, "omitted children stay unassessed");
    }

    public static void testDuplicateDecisionInvalidatesBatch() {
        Result r = engine(q -> List.of(decision("IP", Outcome.REJECT), decision("IP", Outcome.REJECT)))
                .search(ORIGINAL, List.of(intent(READ)), DEFAULTS);
        check(!r.searchExhausted(), "duplicate decisions are ambiguous");
    }

    public static void testUnresolvedQuestionStopsWithoutInventingChannel() {
        Result r = engine(q -> List.of(new Decision("IP", Outcome.UNRESOLVED, "", "", null, "", "", "Channel absent", "Which channel?")))
                .search(ORIGINAL, List.of(intent(READ)), DEFAULTS);
        check(r.edges().isEmpty() && r.unfinished().getFirst().question().equals("Which channel?"), "question retained");
    }

    public static void testOriginalAndScopeSurviveDescent() {
        Result r = engine(q -> {
            check(q.original().equals(ORIGINAL) && q.contribution().equals(READ), "immutable context at every level");
            return decisions(q, q.phase() == Phase.VERIFY ? Outcome.VERIFIED : q.candidates().getFirst().equals(LEAF) ? Outcome.MATCH : Outcome.DESCEND);
        }).search(ORIGINAL, List.of(intent(READ)), DEFAULTS);
        check(r.calls() == 4 && r.edges().getFirst().target().equals(LEAF), "full controlled descent");
    }

    public static void testIdenticalContextsAreDeduplicated() {
        Result r = engine(q -> decisions(q, Outcome.REJECT)).search(ORIGINAL, List.of(intent(READ), intent(READ)), DEFAULTS);
        check(r.calls() == 1, "duplicate intent must not pay twice");
    }

    public static void testDistinctConditionsAreNotMerged() {
        var other = new Contribution(SOURCE, READ.text(), READ.quote(), "different role");
        Result r = engine(q -> decisions(q, Outcome.REJECT)).search(ORIGINAL, List.of(intent(READ), intent(other)), DEFAULTS);
        check(r.calls() == 2, "same code is not same semantic context");
    }

    public static void testIncomingDirectionIsPreserved() {
        Intent incoming = new Intent(READ, "SUPPORTS", Direction.INCOMING, List.of(FOLDER));
        Result r = engine(q -> decisions(q, q.phase() == Phase.VERIFY ? Outcome.VERIFIED : Outcome.MATCH))
                .search(ORIGINAL, List.of(incoming), DEFAULTS);
        check(r.edges().getFirst().sourceId().equals("evidence") && r.edges().getFirst().targetId().equals("app.reader"), "reverse endpoint orientation");
    }

    public static void testUnscoredTargetsAreDiscoverable() {
        Result r = engine(q -> decisions(q, q.phase() == Phase.VERIFY ? Outcome.VERIFIED : q.candidates().getFirst().container() ? Outcome.DESCEND : Outcome.MATCH))
                .search(ORIGINAL, List.of(intent(READ)), DEFAULTS);
        check(r.edges().getFirst().target().equals(FOLDER), "no target score gate");
    }

    public static void testEvidenceMustBeOriginalQuote() {
        Result r = engine(q -> List.of(new Decision(q.candidates().getFirst().id(), Outcome.MATCH, "read", "invented quotation", Necessity.REQUIRED, "", "", "reason", "")))
                .search(ORIGINAL, List.of(new Intent(READ, "CONSUMES", Direction.OUTGOING, List.of(FOLDER))), DEFAULTS);
        check(r.edges().isEmpty() && !r.searchExhausted(), "fabricated evidence rejected");
    }

    public static void testCyclesAreBoundedAndReported() {
        var cycle = new RelationSearchEngine(n -> List.of(ROOT), q -> decisions(q, Outcome.DESCEND), () -> { });
        Result r = cycle.search(ORIGINAL, List.of(intent(READ)), DEFAULTS);
        check(r.calls() == 1 && !r.searchExhausted(), "cycle detected before paying again");
        check(r.unfinished().stream().anyMatch(i -> i.reason().equals("CYCLE")), "cycle reason");
    }

    public static void testDepthLimitRetainsUnfinishedChildren() {
        Result r = engine(q -> decisions(q, Outcome.DESCEND)).search(ORIGINAL, List.of(intent(READ)), new Limits(20, 0, 10, 100));
        check(r.calls() == 1 && !r.searchExhausted() && r.unfinished().getFirst().reason().equals("DEPTH_LIMIT"), "depth bound is explicit");
    }

    public static void testBatchPartitionDoesNotSilentlyDropSiblings() {
        List<Node> targets = List.of(node("a", "IP", false), node("b", "IP", false), node("c", "IP", false));
        Result r = engine(q -> { check(q.candidates().size() <= 2, "batch cap"); return decisions(q, Outcome.REJECT); })
                .search(ORIGINAL, List.of(new Intent(READ, "CONSUMES", Direction.OUTGOING, targets)), new Limits(20, 8, 2, 100));
        check(r.calls() == 2 && r.trace().size() == 3, "all siblings assessed in bounded batches");
    }

    public static void testOptionalAndAlternativeSemanticsSurviveVerification() {
        for (Necessity mode : List.of(Necessity.OPTIONAL, Necessity.ALTERNATIVE)) {
            Result r = engine(q -> List.of(new Decision("evidence", q.phase() == Phase.VERIFY ? Outcome.VERIFIED : Outcome.MATCH,
                    "read evidence", READ.quote(), mode, "when enabled", mode == Necessity.ALTERNATIVE ? "choice-1" : "", "reason", "")))
                    .search(ORIGINAL, List.of(new Intent(READ, "CONSUMES", Direction.OUTGOING, List.of(FOLDER))), DEFAULTS);
            check(r.edges().getFirst().evidence().necessity() == mode, "no percentage normalization or flattening");
        }
    }

    public static void testContainerCannotBecomeConcreteEdge() {
        Result r = engine(q -> decisions(q, Outcome.MATCH)).search(ORIGINAL, List.of(intent(READ)), DEFAULTS);
        check(r.edges().isEmpty() && !r.searchExhausted(), "catalogue root is navigation only");
    }

    public static void testWorkLimitIsVisible() {
        Result r = engine(q -> decisions(q, Outcome.REJECT)).search(ORIGINAL,
                List.of(intent(READ), intent(new Contribution(SOURCE, READ.text(), READ.quote(), "second"))), new Limits(20, 8, 10, 1));
        check(r.calls() == 1 && r.unfinished().stream().anyMatch(i -> i.reason().equals("WORK_LIMIT")), "work cap never means no relation");
    }

    public static void testCheckpointStopsBetweenCalls() {
        AtomicInteger checkpoints = new AtomicInteger();
        var e = new RelationSearchEngine(n -> List.of(FOLDER), q -> decisions(q, Outcome.DESCEND), () -> {
            if (checkpoints.incrementAndGet() > 1) throw new IllegalStateException("cancelled");
        });
        try { e.search(ORIGINAL, List.of(intent(READ)), DEFAULTS); throw new AssertionError("stop must propagate"); }
        catch (IllegalStateException expected) { check(expected.getMessage().equals("cancelled"), "do not disguise cancellation as a model rejection"); }
    }

    public static void testBudgetPrioritizesFinishingAStartedPath() {
        List<Intent> intents = new ArrayList<>();
        for (int i = 0; i < 20; i++) intents.add(intent(new Contribution(SOURCE, READ.text(), READ.quote(), "role-" + i)));
        Result r = engine(q -> decisions(q, q.phase() == Phase.VERIFY ? Outcome.VERIFIED
                : q.candidates().getFirst().container() ? Outcome.DESCEND : Outcome.MATCH))
                .search(ORIGINAL, intents, new Limits(3, 8, 10, 100));
        check(r.edges().size() == 1 && !r.searchExhausted(), "finish and verify one path before spending every call on roots");
    }

    public static void testInterruptionPreservesVerifiedEvidence() {
        AtomicInteger ticks = new AtomicInteger();
        var e = new RelationSearchEngine(n -> List.of(), q -> decisions(q,
                q.phase() == Phase.VERIFY ? Outcome.VERIFIED : Outcome.MATCH), () -> {
            if (ticks.incrementAndGet() == 3) throw new IllegalStateException("cancelled");
        });
        List<Intent> intents = List.of(new Intent(READ, "CONSUMES", Direction.OUTGOING, List.of(FOLDER)),
                new Intent(READ, "PRODUCES", Direction.OUTGOING, List.of(FOLDER)));
        try { e.search(ORIGINAL, intents, DEFAULTS); throw new AssertionError("expected interruption"); }
        catch (RelationSearchEngine.InterruptedSearchException expected) {
            check(expected.partialResult().edges().size() == 1, "preserve already verified results");
            check(!expected.partialResult().searchExhausted(), "pending work survives interruption");
        }
    }

    static RelationSearchEngine engine(Evaluator e) {
        return new RelationSearchEngine(n -> n.equals(ROOT) ? List.of(FOLDER) : n.equals(FOLDER) ? List.of(LEAF) : List.of(), e, () -> { });
    }
    static Intent intent(Contribution c) { return new Intent(c, "CONSUMES", Direction.OUTGOING, List.of(ROOT)); }
    static Node node(String id, String root, boolean container) { return new Node(id, root, id, "Description of " + id, container); }
    static List<Decision> decisions(Query q, Outcome o) { return q.candidates().stream().map(n -> decision(n.id(), o)).toList(); }
    static Decision decision(String id, Outcome o) { return new Decision(id, o, "read evidence", READ.quote(), Necessity.REQUIRED, "", "", "Supported by the requirement", ""); }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
