package com.taxonomy.analysis.dag;

import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.dag.json.AnalysisMessageFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisMessageValidationTest {

    private static final String OP = "op-1";
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final TaxonomyShardRoot CP = TaxonomyShardRoot.of("CP");
    private static final TaxonomyShardRoot IP = TaxonomyShardRoot.of("IP");
    private static final List<TaxonomyShardRoot> ROOTS = List.of(CP, IP);
    private static final AnalysisSourceAuthority AUTHORITY =
            new AnalysisSourceAuthority("repo-1", null, "draft", "abc123");
    private static final RequirementReference REQUIREMENT =
            RequirementReference.of(1L, 2L, "snap-1", "Authored unit fixture");
    private final AnalysisMessageCodec codec = new AnalysisMessageCodec();

    @Test
    void envelopeRejectsTaskFamilyAndRootIdentityMismatches() {
        assertThrows(IllegalArgumentException.class, () -> envelope(
                AnalysisMessageType.RELATION_ANALYSIS_COMPLETED, AnalysisTaskId.subtaxonomy(OP, CP),
                AnalysisTaskType.RELATION_ANALYSIS, ROOTS));
        assertThrows(IllegalArgumentException.class, () -> envelope(
                AnalysisMessageType.SUBTAXONOMY_ANALYSIS_COMPLETED, AnalysisTaskId.relation(OP, ROOTS),
                AnalysisTaskType.SUBTAXONOMY_ANALYSIS, List.of(CP)));
        assertThrows(IllegalArgumentException.class, () -> envelope(
                AnalysisMessageType.SUBTAXONOMY_ANALYSIS_COMPLETED, AnalysisTaskId.subtaxonomy(OP, CP),
                AnalysisTaskType.SUBTAXONOMY_ANALYSIS, List.of(IP)));
        assertThrows(IllegalArgumentException.class, () -> envelope(
                AnalysisMessageType.RELATION_ANALYSIS_COMPLETED, AnalysisTaskId.relation(OP, List.of(CP)),
                AnalysisTaskType.RELATION_ANALYSIS, ROOTS));
        for (List<TaxonomyShardRoot> roots : List.of(List.<TaxonomyShardRoot>of(), ROOTS)) {
            assertThrows(IllegalArgumentException.class, () -> envelope(
                    AnalysisMessageType.ANALYSIS_PROGRESS, AnalysisTaskId.subtaxonomy(OP, CP),
                    AnalysisTaskType.SUBTAXONOMY_ANALYSIS, roots));
        }
    }

    @Test
    void strictCodecRejectsInconsistentCompletionIdentities() {
        var sub = subCompletion(AnalysisTaskOutcome.COMPLETED, null);
        var relation = relationCompletion(AnalysisTaskOutcome.COMPLETED, null);
        assertInvalidJson(mutate(json(relation), "op-1:relation:CP+IP", "op-1:subtaxonomy:CP"));
        assertInvalidJson(mutate(json(sub), "\"taskType\":\"SUBTAXONOMY_ANALYSIS\"",
                "\"taskType\":\"RELATION_ANALYSIS\""));
        assertInvalidJson(mutate(json(sub), "\"roots\":[{\"code\":\"CP\"}]",
                "\"roots\":[{\"code\":\"IP\"}]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"op-1:relation:CP+IP", "op-1:relation:CP", "op-1:future:CP",
            "op-1:subtaxonomy:CP+IP", "op-1:subtaxonomy:*", "other:subtaxonomy:CP"})
    void prerequisitesRejectSelfOtherRelationsUnknownFamiliesRootSetsAndOtherOperations(String id) {
        assertThrows(IllegalArgumentException.class, () -> relationTask(List.of(new AnalysisTaskId(id))));
        String valid = json(relationTask(List.of(AnalysisTaskId.subtaxonomy(OP, CP))));
        assertInvalidJson(mutate(valid, "op-1:subtaxonomy:CP", id));
    }

    @Test
    void duplicatePrerequisitesAreRejectedAtConstructionAndDecode() {
        var cp = AnalysisTaskId.subtaxonomy(OP, CP);
        assertThrows(IllegalArgumentException.class, () -> relationTask(List.of(cp, cp)));
        String valid = json(relationTask(List.of(cp, AnalysisTaskId.subtaxonomy(OP, IP))));
        assertInvalidJson(mutate(valid, "op-1:subtaxonomy:IP", "op-1:subtaxonomy:CP"));
    }

    @Test
    void unknownStopReasonsAreRejectedByAllThreeContractsAndTheCodec() {
        assertThrows(IllegalArgumentException.class,
                () -> subCompletion(AnalysisTaskOutcome.STOPPED, "NOT_A_REASON"));
        assertThrows(IllegalArgumentException.class,
                () -> relationCompletion(AnalysisTaskOutcome.STOPPED, "NOT_A_REASON"));
        assertThrows(IllegalArgumentException.class, () -> cancellation("NOT_A_REASON"));
        assertInvalidJson(mutate(json(subCompletion(AnalysisTaskOutcome.STOPPED, "TIME_LIMIT")),
                "TIME_LIMIT", "NOT_A_REASON"));
        assertInvalidJson(mutate(json(relationCompletion(AnalysisTaskOutcome.STOPPED, "TIME_LIMIT")),
                "TIME_LIMIT", "NOT_A_REASON"));
        assertInvalidJson(mutate(json(cancellation("CANCELLED")), "CANCELLED", "NOT_A_REASON"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "MEMORY_PRESSURE", "TIME_LIMIT", "AWAITING_DECISION"})
    void everySupportedStopReasonStillRoundTrips(String reason) {
        assertRoundTrip(subCompletion(AnalysisTaskOutcome.STOPPED, reason));
        assertRoundTrip(relationCompletion(AnalysisTaskOutcome.STOPPED, reason));
        assertRoundTrip(cancellation(reason));
    }

    @Test
    void validFactoryMessagesProgressAndWildcardRelationsRemainCompatible() {
        assertRoundTrip(subCompletion(AnalysisTaskOutcome.COMPLETED, null));
        assertRoundTrip(relationCompletion(AnalysisTaskOutcome.COMPLETED, null));
        assertRoundTrip(relationTask(List.of(AnalysisTaskId.subtaxonomy(OP, CP),
                AnalysisTaskId.subtaxonomy(OP, IP))));
        var messages = new AnalysisMessageFactory(
                new AnalysisOperationContext(OP, AUTHORITY, REQUIREMENT, "corr-1"),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var graph = AnalysisTaskGraph.plan(OP, ROOTS, true);
        for (var node : graph.tasks()) assertRoundTrip(messages.task(node));
        assertRoundTrip(messages.progress(1, AnalysisProgressPhase.TASK_COMPLETED, null, 0, 3));
        assertRoundTrip(messages.progress(2, AnalysisProgressPhase.TASK_COMPLETED, graph.tasks().get(0), 1, 3));
        assertRoundTrip(new RelationAnalysisTask(envelope(AnalysisMessageType.RELATION_ANALYSIS_TASK,
                AnalysisTaskId.relation(OP, List.of()), AnalysisTaskType.RELATION_ANALYSIS, List.of()),
                List.of(), List.of(AnalysisTaskId.subtaxonomy(OP, CP))));
    }

    private AnalysisEnvelope envelope(AnalysisMessageType messageType, AnalysisTaskId id,
                                      AnalysisTaskType taskType, List<TaxonomyShardRoot> roots) {
        return new AnalysisEnvelope(AnalysisEnvelope.SCHEMA_VERSION, messageType, OP, id, taskType,
                AUTHORITY, REQUIREMENT, roots, 1, null, "corr-1", NOW, null);
    }

    private SubtaxonomyAnalysisCompleted subCompletion(AnalysisTaskOutcome outcome, String reason) {
        return new SubtaxonomyAnalysisCompleted(envelope(AnalysisMessageType.SUBTAXONOMY_ANALYSIS_COMPLETED,
                AnalysisTaskId.subtaxonomy(OP, CP), AnalysisTaskType.SUBTAXONOMY_ANALYSIS, List.of(CP)),
                CP, outcome, null, 0, reason);
    }

    private RelationAnalysisCompleted relationCompletion(AnalysisTaskOutcome outcome, String reason) {
        return new RelationAnalysisCompleted(envelope(AnalysisMessageType.RELATION_ANALYSIS_COMPLETED,
                AnalysisTaskId.relation(OP, ROOTS), AnalysisTaskType.RELATION_ANALYSIS, ROOTS),
                outcome, 0, reason);
    }

    private RelationAnalysisTask relationTask(List<AnalysisTaskId> prerequisites) {
        return new RelationAnalysisTask(envelope(AnalysisMessageType.RELATION_ANALYSIS_TASK,
                AnalysisTaskId.relation(OP, ROOTS), AnalysisTaskType.RELATION_ANALYSIS, ROOTS),
                ROOTS, prerequisites);
    }

    private AnalysisCancellationEvent cancellation(String reason) {
        return new AnalysisCancellationEvent(envelope(
                AnalysisMessageType.ANALYSIS_CANCELLATION, null, null, List.of()), reason);
    }

    private String json(AnalysisMessage message) {
        return new String(codec.encode(message), StandardCharsets.UTF_8);
    }

    private String mutate(String json, String original, String replacement) {
        String changed = json.replace(original, replacement);
        assertNotEquals(json, changed, "The malformed fixture must actually change the encoded message");
        return changed;
    }

    private void assertInvalidJson(String json) {
        var failure = assertThrows(AnalysisMessageFormatException.class,
                () -> codec.decode(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(AnalysisMessageFormatException.Kind.INVALID_CONTRACT, failure.kind());
    }

    private void assertRoundTrip(AnalysisMessage message) {
        assertEquals(message, codec.decode(codec.encode(message)));
    }
}
