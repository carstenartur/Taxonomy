package com.taxonomy.analysis.dag;

import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RelationWorkMessageTest {
    @Test void completionAfterTaskDeadlinePreservesAuthorityAndCanStillSettleExpiry() {
        var root = TaxonomyShardRoot.of("CP"); var created = Instant.parse("2026-01-01T00:00:00Z");
        var header = new AnalysisEnvelope(1, AnalysisMessageType.SUBTAXONOMY_ANALYSIS_TASK, "expiry",
                AnalysisTaskId.subtaxonomy("expiry", root), AnalysisTaskType.SUBTAXONOMY_ANALYSIS,
                new AnalysisSourceAuthority("repo", "ws", "draft", "commit"), RequirementReference.adHoc("requirement"),
                List.of(root), 1, null, "expiry", created, created.plusSeconds(1));
        var completion = header.derive(AnalysisMessageType.SUBTAXONOMY_ANALYSIS_COMPLETED, created.plusSeconds(10));
        AnalysisTaskIdentity.requireSameSource(header, completion);
        assertEquals(header.deadline(), completion.deadline());
        assertThrows(IllegalArgumentException.class, () -> header.derive(AnalysisMessageType.SUBTAXONOMY_ANALYSIS_TASK, created.plusSeconds(10)));
    }
    @Test void versionTwoCarriesStableWorkOrdinalWithoutPromptPayload() {
        var cp = TaxonomyShardRoot.of("CP");
        var id = new AnalysisTaskId("op:relation:CP.w000003");
        var header = envelope(2, id, cp);
        var task = new RelationAnalysisTask(header, List.of(cp), List.of(AnalysisTaskId.subtaxonomy("op", cp)));
        var codec = new AnalysisMessageCodec();
        assertEquals(task, codec.decode(codec.encode(task)));
        assertEquals(2, task.atAttempt(2).envelope().schemaVersion());
        assertEquals(2, header.derive(AnalysisMessageType.RELATION_ANALYSIS_COMPLETED, Instant.now()).schemaVersion());
        assertThrows(IllegalArgumentException.class, () -> envelope(1, id, cp));
        assertThrows(IllegalArgumentException.class, () -> envelope(2, id, TaxonomyShardRoot.of("IP")));
        assertThrows(IllegalArgumentException.class, () -> envelope(2, new AnalysisTaskId("op:relation:CP.w000512"), cp));
        assertThrows(IllegalArgumentException.class, () -> envelope(3, id, cp));
    }
    private static AnalysisEnvelope envelope(int version, AnalysisTaskId task, TaxonomyShardRoot root) {
        return new AnalysisEnvelope(version, AnalysisMessageType.RELATION_ANALYSIS_TASK, "op", task,
                AnalysisTaskType.RELATION_ANALYSIS, new AnalysisSourceAuthority("repo", "ws", "draft", "commit"),
                RequirementReference.adHoc("requirement"), List.of(root), 1, null, "op", Instant.now(), null);
    }
}
