package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static com.taxonomy.dto.RelationSearchModel.*;
import static com.taxonomy.architecture.pipeline.EvidenceRelationProjectionContract.check;

/** Distinguishes a justified component proposal from a separately verified relationship. */
public final class SourceContributionProjectionContract {
    private static final Node SOURCE = new Node("reader", "UA", "Evidence application", "Read evidence", false);
    private static final Contribution PART = new Contribution(SOURCE, "Read evidence", "Read evidence.", "");
    public static void main(String[] args) throws Exception {
        int passed = 0; List<String> failed = new ArrayList<>();
        for (var method : SourceContributionProjectionContract.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("test")) continue;
            try { method.invoke(null); passed++; }
            catch (ReflectiveOperationException error) { failed.add(method.getName() + ": " + error.getCause()); }
        }
        failed.forEach(System.err::println);
        System.out.println("Source contribution projection contracts: " + passed + " passed, " + failed.size() + " failed");
        if (!failed.isEmpty()) throw new AssertionError(failed.size() + " failed");
    }
    public static void testRequiredSourceSurvivesUnfinishedRelationshipSearch() {
        var report = report(List.of(assessment(SOURCE, PART)), List.of());
        var context = project(report, 20);
        check(context.getElements().size() == 1 && context.getRelationships().isEmpty(),
                "A source contribution must not disappear merely because no relationship could be verified");
        var element = context.getElements().getFirst();
        check(element.getOrigin().name().equals("REQUIREMENT_EVIDENCE"),
                "Source-only provenance must not claim separately verified relationship evidence");
        check(element.getPresenceReason().contains("Read evidence.") && element.getPresenceReason().contains("SOURCE_ONLY"),
                "The original evidence and the absence of verified relationships must be visible");
        check(context.getView().getRelationSearchReport().equals(report), "Full immutable evidence retained");
        new ArchitecturePipelineInvariantValidator().beforeReturn(context);
    }
    public static void testUnverifiedSourceIsNeverAnImpactAnchor() {
        var context = project(report(List.of(assessment(SOURCE, PART)), List.of()), 20);
        var source = context.getElements().getFirst();
        check(!source.isSelectedForImpact(), "An isolated unconfirmed source must not authorize impact analysis");
        check(context.getAnchors().isEmpty(), "There are no verified impact anchors");
        var json = JsonMapper.builder().build();
        var copy = json.readValue(json.writeValueAsString(context.getView()), RequirementArchitectureView.class);
        check(!copy.getIncludedElements().getFirst().isSelectedForImpact(), "The unconfirmed state survives persistence");
    }
    public static void testSourceOnlyProvenanceAndFullReportSurviveJson() {
        var view = project(report(List.of(assessment(SOURCE, PART)), List.of()), 20).getView();
        var json = JsonMapper.builder().build();
        var copy = json.readValue(json.writeValueAsString(view), RequirementArchitectureView.class);
        check(copy.getIncludedElements().size() == 1, "Source proposal survives snapshot serialization");
        check(copy.getIncludedElements().getFirst().getOrigin().name().equals("REQUIREMENT_EVIDENCE"), "Exact provenance survives");
        check(copy.getRelationSearchReport().equals(view.getRelationSearchReport()), "No report reduction in the snapshot");
    }
    public static void testUnresolvedAndRejectedSourcesDoNotBecomeComponents() {
        var unresolved = new SourceAssessment(SOURCE, List.of(), "Need a decision", "Which application?");
        var rejected = new SourceAssessment(SOURCE, List.of(), "Not requested", "");
        for (var assessment : List.of(unresolved, rejected)) {
            check(project(report(List.of(assessment), List.of()), 20).getElements().isEmpty(),
                    "Unknown and negative source decisions must not become positive component proposals");
        }
    }
    public static void testConditionalSourceIsNotSilentlyAdoptedAsRequired() {
        var conditional = new Contribution(SOURCE, "Optional email evidence", "Read evidence.", "only if email is selected");
        check(project(report(List.of(assessment(SOURCE, conditional)), List.of()), 20).getElements().isEmpty(),
                "A condition with no verified required relationship remains a decision, not a required node");
    }
    public static void testContainerSourceCannotEnterArchitecture() {
        var root = new Node("UA", "UA", "Applications", "", true);
        var part = new Contribution(root, "Application contribution", "Read evidence.", "");
        check(project(report(List.of(assessment(root, part)), List.of()), 20).getElements().isEmpty(),
                "Navigation containers remain outside architecture membership");
    }
    public static void testContributionCannotBorrowAnotherSourceIdentity() {
        var other = new Node("other", "UA", "Other application", "", false);
        try {
            project(report(List.of(assessment(other, PART)), List.of()), 20);
            throw new AssertionError("Mismatched contribution/source identity was silently accepted");
        } catch (IllegalArgumentException expected) { }
    }
    public static void testSourceNodeCapIsVisibleAndDoesNotMutateEvidence() {
        var other = new Node("other", "UA", "Other application", "", false);
        var report = report(List.of(assessment(SOURCE, PART),
                assessment(other, new Contribution(other, "Other required contribution", "Read evidence.", ""))), List.of());
        var context = project(report, 1);
        check(context.getElements().size() == 1 && context.getRelationships().isEmpty(), "Source-only nodes obey the view cap");
        check(context.getView().getNotes().stream().anyMatch(note -> note.contains("NODE_LIMIT")), "Omitted source nodes are visible");
        check(report.sources().size() == 2, "The full report is not reduced by a view limit");
    }
    public static void testVerifiedEdgeStillTakesPriorityOverIsolatedSources() {
        var verified = EvidenceRelationProjectionContract.edge(Necessity.REQUIRED);
        var isolated = new Node("isolated", "BP", "Standalone activity", "", false);
        var context = project(report(List.of(assessment(isolated,
                new Contribution(isolated, "Standalone contribution", "Read evidence.", ""))), List.of(verified)), 2);
        check(context.getElements().size() == 2 && context.getRelationships().size() == 1,
                "Adding isolated proposals must not displace an already verified edge");
        check(context.getElements().stream().noneMatch(element -> element.getNodeCode().equals("isolated")), "No hidden cap overflow");
    }
    private static SourceAssessment assessment(Node node, Contribution part) {
        return new SourceAssessment(node, List.of(part), "Explicit original contribution", "");
    }
    private static RelationSearchReport report(List<SourceAssessment> sources, List<Edge> edges) {
        return new RelationSearchReport(1, "a".repeat(64), "test", sources,
                new Result(edges, List.of(new Unfinished("reader", "CONSUMES", Direction.OUTGOING,
                        List.of("IP"), "CALL_BUDGET", "")), List.of(), 1, 1, 0), 2, 2, 0, List.of(), "");
    }
    private static ArchitectureViewContext project(RelationSearchReport report, int limit) {
        var context = new ArchitectureViewContext(Map.of("reader", 50), "Read evidence.", limit, List.of());
        EvidenceRelationProjection.apply(context, report); return context;
    }
}
