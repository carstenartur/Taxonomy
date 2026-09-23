package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.*;
import java.util.*;
import static com.taxonomy.dto.RelationSearchModel.*;

/** Real DTO projection and existing invariants, with a compact immutable evidence report. */
public final class EvidenceRelationProjectionContract {
    static final Node SOURCE = new Node("reader", "UA", "Read evidence", "", false);
    static final Node TARGET = new Node("record", "IP", "Evidence record", "", false);
    static final Contribution PART = new Contribution(SOURCE, "read existing evidence", "Read evidence.", "reader role");
    public static void main(String[] args) throws Exception {
        int passed = 0; List<String> failed = new ArrayList<>();
        for (var m : EvidenceRelationProjectionContract.class.getDeclaredMethods()) if (m.getName().startsWith("test")) {
            try { m.invoke(null); passed++; } catch (ReflectiveOperationException ex) { failed.add(m.getName() + ": " + ex.getCause()); }
        }
        failed.forEach(System.err::println);
        System.out.println("Evidence projection contracts: " + passed + " passed, " + failed.size() + " failed");
        if (!failed.isEmpty()) throw new AssertionError(failed.size() + " failed");
    }
    public static void testMultipleScopesShareOneQueryableEdgeWithoutLosingEvidence() {
        Edge first = edge(Necessity.REQUIRED);
        var second = new Edge(new Contribution(SOURCE, "read current evidence", "Read evidence.", "supervisor role"),
                TARGET, first.type(), first.direction(), first.evidence());
        var r = report(List.of(first, second));
        var c = project(r, 0);
        check(c.getRelationships().size() == 1, "snapshot unique relation signature must not collide");
        Object evidence = c.getRelationships().getFirst().getRequirementEvidence();
        check(evidence instanceof List<?> list && list.size() == 2, "both scopes retained, not first-wins");
        check(r.result().edges().size() == 2, "original evidence report unchanged");
    }
    public static void testLongEvidenceDoesNotOverflowQueryableSummaryColumns() {
        Edge original = edge(Necessity.REQUIRED);
        var longPart = new Contribution(SOURCE, "long ".repeat(1000), "Read evidence.", "role");
        var r = report(List.of(new Edge(longPart, TARGET, original.type(), original.direction(), original.evidence())));
        var c = project(r, 0);
        check(c.getElements().stream().allMatch(e -> e.getPresenceReason().length() <= 2000), "element summary column limit");
        check(c.getRelationships().stream().allMatch(e -> e.getPresenceReason().length() <= 2000), "relation summary column limit");
        check(r.result().edges().getFirst().contribution().text().length() == 5000, "full evidence must not be truncated");
    }

    public static void testOnlyVerifiedRequiredRelationsBecomeEdges() {
        var report = report(List.of(edge(Necessity.REQUIRED), edge(Necessity.OPTIONAL), edge(Necessity.ALTERNATIVE)));
        var context = project(report, 0);
        check(context.getView().getIncludedRelationships().size() == 1, "choices not silently co-installed");
        check(context.getView().getIncludedElements().size() == 2, "exact two endpoints, no roots");
        new ArchitecturePipelineInvariantValidator().beforeReturn(context);
    }
    public static void testUnknownConfidenceIsNotInvented() {
        var context = project(report(List.of(edge(Necessity.REQUIRED))), 0);
        var relation = context.getView().getIncludedRelationships().getFirst();
        check(relation.getConfidence() == 0 && relation.getOrigin() == RelationOrigin.LLM_SUPPORTED, "no score product masquerading as confidence");
        check(relation.getPresenceReason().contains("Read evidence.") && relation.getPresenceReason().contains("reader role"), "evidence and source condition visible");
    }
    public static void testSmallPositiveSourceAndZeroScoreTargetRemainIncluded() {
        var view = project(report(List.of(edge(Necessity.REQUIRED))), 0).getView();
        check(view.getIncludedElements().stream().anyMatch(e -> e.getNodeCode().equals("reader") && e.getDirectLlmScore() == 1), "not old >=50 anchor gating");
        check(view.getIncludedElements().stream().anyMatch(e -> e.getNodeCode().equals("record") && e.getDirectLlmScore() == 0), "unscored evidence target");
    }
    public static void testNodeLimitDoesNotMutateReportOrLeaveDanglingEdge() {
        var report = report(List.of(edge(Necessity.REQUIRED)));
        var c = project(report, 1);
        check(c.getElements().size() <= 1 && c.getRelationships().isEmpty(), "whole edge omitted if endpoints cannot fit");
        check(report.result().edges().size() == 1 && c.getView().getNotes().stream().anyMatch(s -> s.contains("NODE_LIMIT")), "full model evidence retained with explicit omission");
        new ArchitecturePipelineInvariantValidator().beforeReturn(c);
    }
    public static void testEmptyEvidenceDoesNotFallbackToCompatibility() {
        var c = project(report(List.of()), 0);
        check(c.getRelationships().isEmpty() && c.getView().getNotes().stream().anyMatch(s -> s.contains("No verified")), "no generated fallback");
    }
    public static void testUnverifiedClaimCannotBeProjected() {
        var d = new Decision("record", Outcome.MATCH, "existing evidence", "Read evidence.", Necessity.REQUIRED, "", "", "rationale", "");
        try { project(report(List.of(new Edge(PART, TARGET, "CONSUMES", Direction.OUTGOING, d))), 0); throw new AssertionError("unverified edge accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void testIncomingEdgeDirectionSurvives() {
        Edge old = edge(Necessity.REQUIRED);
        var c = project(report(List.of(new Edge(PART, TARGET, "SUPPORTS", Direction.INCOMING, old.evidence()))), 0);
        check(c.getRelationships().getFirst().getSourceCode().equals("record") && c.getRelationships().getFirst().getTargetCode().equals("reader"), "incoming direction not reversed twice");
    }
    static Edge edge(Necessity n) {
        return new Edge(PART, TARGET, "CONSUMES", Direction.OUTGOING, new Decision("record", Outcome.VERIFIED,
                "existing evidence", "Read evidence.", n, n == Necessity.OPTIONAL ? "if requested" : "", n == Necessity.ALTERNATIVE ? "channel" : "", "Reader needs the record", ""));
    }
    static RelationSearchReport report(List<Edge> edges) {
        return new RelationSearchReport(1, "a".repeat(64), "test", List.of(),
                new Result(edges, List.of(), List.of(), 3, 3, 0), 4, 10, 0, List.of(), "");
    }
    static ArchitectureViewContext project(RelationSearchReport report, int maxNodes) {
        var c = new ArchitectureViewContext(Map.of("reader", 1), "Read evidence.", maxNodes, List.of());
        EvidenceRelationProjection.apply(c, report); return c;
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
