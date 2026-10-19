package com.taxonomy.analysis.relations;

import com.taxonomy.dto.RelationSearchBudget;
import com.taxonomy.dto.RelationSearchReport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.taxonomy.dto.RelationSearchModel.*;
import static org.junit.jupiter.api.Assertions.*;

class InlineRelationPlanTest {
    @ParameterizedTest @ValueSource(strings = {"valid", "legacy-context"})
    void validatedInlineEvidenceSurvivesJsonAndNeedsNoWorkerGrant(String variant) {
        var plan = plan(variant);
        var mapper = new ObjectMapper();
        var copy = mapper.readValue(mapper.writeValueAsString(plan), RelationSearchDistribution.Plan.class);
        assertEquals(plan, copy);
        assertTrue(RelationSearchDistribution.ready(copy, List.of()).isEmpty());
        assertEquals(plan.inlineReport(), RelationSearchDistribution.combine(copy, List.of()));
    }

    @ParameterizedTest @ValueSource(strings = {"source-limit", "work-limit", "new-calls", "spent-calls",
            "foreign-assessment", "foreign-contribution", "foreign-edge-source", "foreign-target-root",
            "incompatible-edge-route", "unverified-edge", "wrong-decision-target", "trace-key", "trace-source",
            "trace-route", "unfinished-key", "unfinished-source", "unfinished-root", "unfinished-route"})
    void inlineEvidenceCannotBypassTheAdmittedBudgetOrRelationAuthority(String variant) {
        assertThrows(IllegalArgumentException.class, () -> plan(variant));
    }

    private static RelationSearchDistribution.Plan plan(String variant) {
        var source = new Node("source", "CP", "Capability", "Frozen source", false);
        var other = new Node("other", "CP", "Other capability", "Foreign source", false);
        var target = new Node("target", variant.equals("foreign-target-root") ? "IP" : "CR",
                "Service", "Frozen target", false);
        var contribution = new Contribution(source, "requirement", "requirement", "");
        var foreign = new Contribution(other, "requirement", "requirement", "");
        var assessment = new SourceAssessment(variant.equals("foreign-assessment") ? other : source,
                List.of(variant.equals("foreign-contribution") ? foreign : contribution), "Required", "");
        String key = RelationSearchEngine.contributionKey(contribution, "REALIZES", Direction.OUTGOING);
        String traceKey = variant.equals("legacy-context") ? null : variant.equals("trace-key") ? "foreign" : key;
        String unfinishedKey = variant.equals("legacy-context") ? null : variant.equals("unfinished-key") ? "foreign" : key;
        var edge = new Edge(variant.equals("foreign-edge-source") ? foreign : contribution, target,
                variant.equals("incompatible-edge-route") ? "CONSUMES" : "REALIZES", Direction.OUTGOING,
                new Decision(variant.equals("wrong-decision-target") ? "other-target" : "target",
                        variant.equals("unverified-edge") ? Outcome.MATCH : Outcome.VERIFIED,
                        "requirement", "requirement", Necessity.REQUIRED, "", "", "Verified original requirement", ""));
        var trace = new Trace(variant.equals("trace-source") ? "other" : "source",
                variant.equals("trace-route") ? "CONSUMES" : "REALIZES", Direction.OUTGOING,
                Phase.VERIFY, "target", Outcome.VERIFIED, "Verified original requirement", traceKey);
        var unfinished = new Unfinished(variant.equals("unfinished-source") ? "other" : "source",
                variant.equals("unfinished-route") ? "CONSUMES" : "REALIZES", Direction.OUTGOING,
                List.of("target"), "PENDING", "", unfinishedKey,
                variant.equals("legacy-context") ? null : variant.equals("unfinished-root") ? "IP" : "CR");
        var budget = new RelationSearchBudget(2, 20, variant.equals("new-calls") ? 2 : 3,
                variant.equals("spent-calls") ? 8 : 7, true, 2,
                variant.equals("source-limit") ? 1 : 2, variant.equals("work-limit") ? 8 : 16, 2);
        var report = new RelationSearchReport(2, "original-hash", "inline-test", List.of(assessment),
                new Result(List.of(edge), List.of(unfinished), List.of(trace), 2, 2, 1),
                3, 10, 1, List.of(), "").withBudget(budget);
        return new RelationSearchDistribution.Plan(1, "operation:relations", "original-hash", List.of("source-result"),
                new RequirementRelationSearch.Options(new Limits(10, 8, 10, 16), 2, 16_000,
                        new RequirementRelationSearch.BudgetPolicy(2, 20, 4, true, 2, 16, 2)),
                List.of(source), List.of(new Node("CR", "CR", "Services", "Frozen root", true)),
                List.of(assessment), List.of(), 1, 1, List.of(), "", true, report);
    }
}
