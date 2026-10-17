package com.taxonomy.analysis.relations;

import com.taxonomy.dto.RelationSearchProgress;
import com.taxonomy.dto.RelationSearchReport;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.taxonomy.dto.RelationSearchModel.*;
import static org.assertj.core.api.Assertions.*;

class RelationSearchEvidenceTest {
    private static final Node SOURCE = new Node("CP", "CP", "Capability", "", false);
    private static final Node TARGET = new Node("IP", "IP", "Information product", "", false);
    private static final Contribution CONTRIBUTION = new Contribution(SOURCE, "requirement", "requirement", "");
    private static final Edge ACCEPTED = edge(Outcome.VERIFIED);

    @Test void acceptedEvidenceDoesNotInheritPreviousSearchCompletionOrConsumption() {
        var current = report("same-requirement", List.of(), 1, 0, 1);
        var retained = report("same-requirement", List.of(ACCEPTED, edge(Outcome.MATCH)), 9, 1, 0);
        var merged = RelationSearchDistribution.retainAcceptedRelations(current, retained);
        assertThat(merged.result().edges()).containsExactly(ACCEPTED);
        assertThat(merged.progress().verifiedRelations()).isEqualTo(1);
        assertThat(merged.progress().completedSearches()).isZero();
        assertThat(merged.progress().pendingSearches()).isEqualTo(1);
        assertThat(merged.progress().calls()).isEqualTo(1);
        assertThat(merged.progress().taxonomies()).containsExactly(new RelationSearchProgress.Taxonomy("IP", 1, 0, 0, 1));
        assertThat(merged.totalCalls()).isEqualTo(1);
        assertThat(merged.durationMillis()).isEqualTo(5);
        assertThat(merged.result().calls()).isZero();
        assertThat(merged.result().visitedBatches()).isZero();
        assertThat(merged.result().durationMillis()).isEqualTo(5);
        assertThat(merged.result().unfinished()).containsExactly(new Unfinished("CP", "REQUIRES", Direction.OUTGOING, List.of("IP"), "PENDING", ""));
        assertThat(merged.warnings()).containsExactly("current warning");
        assertThat(merged.stopReason()).isEqualTo("current stop");
        assertThat(merged.tasks()).containsExactly(new RelationSearchProgress.Task("CP", "IP", RelationSearchProgress.State.PENDING));
        assertThat(current.result().edges()).isEmpty();
        assertThat(retained.result().edges()).hasSize(2);
    }

    @Test void replayedAcceptedEvidenceIsOnlyCountedOnce() {
        var merged = RelationSearchDistribution.retainAcceptedRelations(report("same-requirement", List.of(ACCEPTED), 2, 1, 0),
                report("same-requirement", List.of(ACCEPTED), 9, 1, 0));
        assertThat(merged.result().edges()).containsExactly(ACCEPTED);
        assertThat(merged.progress().verifiedRelations()).isEqualTo(1);
        assertThat(merged.totalCalls()).isEqualTo(2);
    }

    @Test void evidenceFromAnotherRequirementCannotBeRetained() {
        assertThatThrownBy(() -> RelationSearchDistribution.retainAcceptedRelations(report("current", List.of(), 1, 0, 1),
                report("foreign", List.of(ACCEPTED), 9, 1, 0))).isInstanceOf(IllegalArgumentException.class);
    }

    private static Edge edge(Outcome outcome) {
        return new Edge(CONTRIBUTION, TARGET, "REQUIRES", Direction.OUTGOING,
                new Decision("IP", outcome, "requirement", "requirement", Necessity.REQUIRED, "", "", "Accepted evidence", ""));
    }

    private static RelationSearchReport report(String requirement, List<Edge> edges, int calls, int completed, int pending) {
        var unfinished = pending == 0 ? List.<Unfinished>of() : List.of(new Unfinished("CP", "REQUIRES", Direction.OUTGOING, List.of("IP"), "PENDING", ""));
        var progress = new RelationSearchProgress(1, 1, 1, completed, 0, pending, calls, 10, edges.size(),
                RelationSearchProgress.Step.PAUSED, null, List.of(new RelationSearchProgress.Taxonomy("IP", 1, completed, 0, pending)));
        return new RelationSearchReport(2, requirement, "current policy", List.of(new SourceAssessment(SOURCE, List.of(CONTRIBUTION), "", "")),
                new Result(edges, unfinished, List.of(), 0, 0, 5), calls, 10, 5, List.of("current warning"), "current stop", progress,
                List.of(new RelationSearchProgress.Task("CP", "IP", pending == 0 ? RelationSearchProgress.State.COMPLETED : RelationSearchProgress.State.PENDING)));
    }
}
