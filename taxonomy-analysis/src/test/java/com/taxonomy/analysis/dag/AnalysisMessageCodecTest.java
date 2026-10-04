package com.taxonomy.analysis.dag;

import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.dag.json.AnalysisMessageFormatException;
import com.taxonomy.analysis.dag.json.AnalysisMessageFormatException.Kind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisMessageCodecTest {

    private static final String OP = "op-1";
    private static final String REQUIREMENT_TEXT = "Hospital staff need secure voice communication";
    private final AnalysisMessageCodec codec = new AnalysisMessageCodec();
    private final AnalysisMessageFactory messages = new AnalysisMessageFactory(
            new AnalysisOperationContext(OP, new AnalysisSourceAuthority("repo-1", null, "draft", "abc123"),
                    RequirementReference.of(1L, 2L, "snap-1", REQUIREMENT_TEXT), "corr-1"),
            Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC));
    private final AnalysisTaskGraph graph = AnalysisTaskGraph.plan(OP,
            List.of(TaxonomyShardRoot.of("CP"), TaxonomyShardRoot.of("IP")), true);

    private List<AnalysisMessage> allContracts() {
        var subtaxonomy = (SubtaxonomyAnalysisTask) messages.task(graph.tasks().get(0));
        var relation = (RelationAnalysisTask) messages.task(graph.tasks().get(2));
        return List.of(subtaxonomy,
                messages.completed(subtaxonomy, AnalysisTaskOutcome.COMPLETED, 55, 3, null),
                relation,
                messages.completed(relation, AnalysisTaskOutcome.STOPPED, 0, "TIME_LIMIT"),
                messages.progress(4, AnalysisProgressPhase.TASK_COMPLETED, graph.tasks().get(0), 1, 3),
                messages.cancellation("CANCELLED"));
    }

    @Test
    void everyContractRoundTripsThroughExplicitTypeDiscriminator() {
        List<AnalysisMessage> contracts = allContracts();
        assertThat(contracts).extracting(m -> m.envelope().messageType())
                .containsExactlyInAnyOrder(AnalysisMessageType.values());
        for (AnalysisMessage message : contracts) {
            byte[] encoded = codec.encode(message);
            String json = new String(encoded, StandardCharsets.UTF_8);
            assertThat(json).contains("\"schemaVersion\":1")
                    .doesNotContain("com.taxonomy").doesNotContain("@class").doesNotContain("@type")
                    .doesNotContain(REQUIREMENT_TEXT);
            assertThat(codec.decode(encoded)).isEqualTo(message);
        }
    }

    @Test
    void unsupportedSchemaUnknownTypeAndUnknownPropertiesAreRejected() {
        String json = new String(codec.encode(allContracts().get(0)), StandardCharsets.UTF_8);
        assertKind(json.replace("\"schemaVersion\":1", "\"schemaVersion\":999"), Kind.UNSUPPORTED_SCHEMA);
        assertKind(json.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""), Kind.UNSUPPORTED_SCHEMA);
        assertKind(json.replace("SUBTAXONOMY_ANALYSIS_TASK", "java.lang.Runtime"), Kind.UNKNOWN_TYPE);
        assertKind(json.replace("SUBTAXONOMY_ANALYSIS_TASK", "RELATION_ANALYSIS_TASK"), Kind.INVALID_CONTRACT);
        assertKind(json.replaceFirst("\\{", "{\"prompt\":\"secret\","), Kind.INVALID_CONTRACT);
        assertKind(json.replace("\"code\":\"CP\"", "\"code\":\"C P\""), Kind.INVALID_CONTRACT);
        assertKind("{not json", Kind.MALFORMED);
        assertKind("[]", Kind.MALFORMED);
        assertKind(json + "{}", Kind.MALFORMED);
    }

    @Test
    void messagesAreBoundedInBothDirections() {
        byte[] oversized = new byte[AnalysisMessageCodec.MAX_MESSAGE_BYTES + 1];
        assertThatThrownBy(() -> codec.decode(oversized))
                .isInstanceOfSatisfying(AnalysisMessageFormatException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.TOO_LARGE));
    }

    private void assertKind(String json, Kind kind) {
        assertThatThrownBy(() -> codec.decode(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(AnalysisMessageFormatException.class, e -> {
                    assertThat(e.kind()).isEqualTo(kind);
                    assertThat(e.getMessage()).doesNotContain("secret");
                });
    }
}
