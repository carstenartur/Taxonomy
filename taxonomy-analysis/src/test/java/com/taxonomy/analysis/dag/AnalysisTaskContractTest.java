package com.taxonomy.analysis.dag;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisTaskContractTest {

    private static final String OP = "7b0c3f8e-1d2a-4c55-9a51-2f3e4d5c6b7a";
    private static final AnalysisSourceAuthority AUTHORITY =
            new AnalysisSourceAuthority("repo-1", "ws-1", "draft", "abc123");
    private static final RequirementReference REQUIREMENT = RequirementReference.adHoc("requirement text");

    private static TaxonomyShardRoot root(String code) { return TaxonomyShardRoot.of(code); }

    @Test
    void taskIdsAreDeterministicAndOperationScoped() {
        assertThat(AnalysisTaskId.subtaxonomy(OP, root("CP")))
                .isEqualTo(AnalysisTaskId.subtaxonomy(OP, root("CP")))
                .isNotEqualTo(AnalysisTaskId.subtaxonomy(OP, root("IP")))
                .isNotEqualTo(AnalysisTaskId.subtaxonomy("other-op", root("CP")));
        assertThat(AnalysisTaskId.subtaxonomy(OP, root("CP")).value()).isEqualTo(OP + ":subtaxonomy:CP");
        assertThat(AnalysisTaskId.relation(OP, List.of(root("IP"), root("CP"))))
                .isEqualTo(AnalysisTaskId.relation(OP, List.of(root("CP"), root("IP"))));
        assertThat(AnalysisTaskId.relation(OP, List.of()).value()).isEqualTo(OP + ":relation:*");
        assertThat(AnalysisTaskId.subtaxonomy(OP, root("BP")).operationId()).isEqualTo(OP);
    }

    @Test
    void rootsAreValidatedIdentifiersAndTheEightDefaultsAreKnown() {
        assertThat(TaxonomyShardRoot.DEFAULT_ROOTS).extracting(TaxonomyShardRoot::code)
                .containsExactly("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA");
        assertThat(root("CP").defaultCatalogueRoot()).isTrue();
        for (String invalid : new String[] {"", " ", "C P", "CP:1", "CP+IP", "*", "../x", "a".repeat(65)}) {
            assertThatThrownBy(() -> TaxonomyShardRoot.of(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> AnalysisTaskId.requireOperationId("op:with:colon"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void graphModelsAllRootsSelectedSubsetAndNoRelations() {
        var all = AnalysisTaskGraph.plan(OP, TaxonomyShardRoot.DEFAULT_ROOTS, true);
        assertThat(all.totalTasks()).isEqualTo(9);
        assertThat(all.subtaxonomyRoots()).isEqualTo(TaxonomyShardRoot.DEFAULT_ROOTS);
        var relation = all.tasksOf(AnalysisTaskType.RELATION_ANALYSIS).get(0);
        assertThat(relation.prerequisites()).containsExactlyElementsOf(all.subtaxonomyTaskIds().values());

        var subset = AnalysisTaskGraph.plan(OP, List.of(root("IP"), root("CP")), true);
        assertThat(subset.subtaxonomyRoots()).containsExactly(root("IP"), root("CP"));
        assertThat(subset.tasksOf(AnalysisTaskType.RELATION_ANALYSIS).get(0).roots())
                .containsExactly(root("CP"), root("IP"));

        var noRelations = AnalysisTaskGraph.plan(OP, List.of(root("BP")), false);
        assertThat(noRelations.includesRelations()).isFalse();
        assertThat(noRelations.totalTasks()).isEqualTo(1);

        var relationsOnly = AnalysisTaskGraph.relationsOnly(OP, List.of());
        assertThat(relationsOnly.tasks()).singleElement()
                .satisfies(node -> assertThat(node.prerequisites()).isEmpty());
    }

    @Test
    void readyTasksRespectPrerequisitesAndDispatchState() {
        var graph = AnalysisTaskGraph.plan(OP, List.of(root("BP"), root("CP")), true);
        var bp = AnalysisTaskId.subtaxonomy(OP, root("BP"));
        var cp = AnalysisTaskId.subtaxonomy(OP, root("CP"));
        assertThat(graph.ready(AnalysisTaskType.RELATION_ANALYSIS, Set.of(bp), Set.of())).isEmpty();
        assertThat(graph.ready(AnalysisTaskType.RELATION_ANALYSIS, Set.of(bp, cp), Set.of())).hasSize(1);
        assertThat(graph.ready(AnalysisTaskType.SUBTAXONOMY_ANALYSIS, Set.of(), Set.of(bp)))
                .extracting(AnalysisTaskGraph.Node::id).containsExactly(cp);
    }

    @Test
    void graphRejectsDuplicatesForeignTasksAndBrokenOrder() {
        assertThatThrownBy(() -> AnalysisTaskGraph.plan(OP, List.of(root("BP"), root("BP")), false))
                .isInstanceOf(IllegalArgumentException.class);
        var foreign = new AnalysisTaskGraph.Node(AnalysisTaskId.subtaxonomy("other", root("BP")),
                AnalysisTaskType.SUBTAXONOMY_ANALYSIS, List.of(root("BP")), List.of());
        assertThatThrownBy(() -> new AnalysisTaskGraph(OP, List.of(foreign)))
                .isInstanceOf(IllegalArgumentException.class);
        var relationFirst = new AnalysisTaskGraph.Node(AnalysisTaskId.relation(OP, List.of(root("BP"))),
                AnalysisTaskType.RELATION_ANALYSIS, List.of(root("BP")),
                List.of(AnalysisTaskId.subtaxonomy(OP, root("BP"))));
        assertThatThrownBy(() -> new AnalysisTaskGraph(OP, List.of(relationFirst)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void envelopesAreVersionedAndFailClosedOnIdentityMismatch() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var bp = AnalysisTaskId.subtaxonomy(OP, root("BP"));
        assertThatThrownBy(() -> new AnalysisEnvelope(2, AnalysisMessageType.SUBTAXONOMY_ANALYSIS_TASK, OP, bp,
                AnalysisTaskType.SUBTAXONOMY_ANALYSIS, AUTHORITY, REQUIREMENT, List.of(root("BP")), 1, null, OP,
                now, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisEnvelope(1, AnalysisMessageType.SUBTAXONOMY_ANALYSIS_TASK, "other", bp,
                AnalysisTaskType.SUBTAXONOMY_ANALYSIS, AUTHORITY, REQUIREMENT, List.of(root("BP")), 1, null, OP,
                now, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisEnvelope(1, AnalysisMessageType.SUBTAXONOMY_ANALYSIS_TASK, OP, bp,
                null, AUTHORITY, REQUIREMENT, List.of(), 1, null, OP, now, null))
                .isInstanceOf(IllegalArgumentException.class);
        var mismatch = new AnalysisEnvelope(1, AnalysisMessageType.SUBTAXONOMY_ANALYSIS_TASK, OP, bp,
                AnalysisTaskType.SUBTAXONOMY_ANALYSIS, AUTHORITY, REQUIREMENT, List.of(root("BP")), 1, null, OP,
                now, null);
        assertThatThrownBy(() -> new SubtaxonomyAnalysisTask(mismatch, root("CP")))
                .isInstanceOf(IllegalArgumentException.class);
        var task = new SubtaxonomyAnalysisTask(mismatch, root("BP"));
        assertThat(task.envelope().nextAttempt().redelivered()).isTrue();
        assertThat(task.envelope().nextAttempt().taskId()).isEqualTo(task.taskId());
    }

    @Test
    void completionsKeepPartialAndStoppedOutcomesExplicit() {
        var messages = new AnalysisMessageFactory(
                new AnalysisOperationContext(OP, AUTHORITY, REQUIREMENT, null), java.time.Clock.systemUTC());
        var graph = AnalysisTaskGraph.plan(OP, List.of(root("BP")), false);
        var task = (SubtaxonomyAnalysisTask) messages.task(graph.tasks().get(0));
        assertThat(messages.completed(task, AnalysisTaskOutcome.STOPPED, null, 0, "CANCELLED").stopsOperation())
                .isTrue();
        assertThat(messages.completed(task, AnalysisTaskOutcome.FAILED, null, 0, null).rootScore()).isNull();
        assertThatThrownBy(() -> messages.completed(task, AnalysisTaskOutcome.STOPPED, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> messages.completed(task, AnalysisTaskOutcome.COMPLETED, 101, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> messages.cancellation("cancelled by alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(messages.cancellation("CANCELLED").envelope().taskId()).isNull();
    }

    @Test
    void requirementReferenceIdentifiesTextWithoutCarryingIt() {
        var reference = RequirementReference.of(4L, 9L, "snapshot-1", "Need secure voice comms");
        assertThat(reference.matches("Need secure voice comms")).isTrue();
        assertThat(reference.matches("Need secure voice comms ")).isFalse();
        assertThat(reference.toString()).doesNotContain("secure voice");
    }
}
