package com.taxonomy.reporting.api;
import com.taxonomy.diagram.*;
import com.taxonomy.dto.*;
import com.taxonomy.reporting.api.document.ArchitectureReportDocument;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DiagramProvenanceDigestTest {
    @Test void metadataOnlyChangesAreEvidenceChangesAndOrderingIsDeterministic() {
        var derived = metadata(RelationOrigin.IMPACT_DERIVED, "rationale");
        var proposed = metadata(RelationOrigin.LLM_SUPPORTED, "rationale");
        var changedReason = metadata(RelationOrigin.IMPACT_DERIVED, "changed rationale");
        var one = edge("one", derived); var two = edge("two", proposed);
        assertNotEquals(hash(one), hash(edge("one", proposed)));
        assertNotEquals(hash(one), hash(edge("one", changedReason)));
        assertEquals(hash(one, two), hash(two, one));
    }
    @Test void sceneMustPreserveTheSameRecordedRelationshipEvidence() {
        var m = metadata(RelationOrigin.LLM_SUPPORTED, "rationale");
        var node = new DiagramNode("CP-1", "Source", "Capability", .8, false, 1);
        var graph = new DiagramModel("T", List.of(node),
            List.of(new DiagramEdge("one", "CP-1", "CP-1", "REALIZES", .8, "impact", m)), new DiagramLayout("LR", true));
        var sceneNode = new DiagramSceneNode(node.id(), node.label(), node.type(), node.relevance(), node.anchor(),
            node.layer(), node.depth(), node.selectedForImpact(), node.parentId(), node.container(), 0, 0, 238, 82);
        var same = new DiagramScene("T", 760, 420, "LR", List.of(sceneNode),
            List.of(new DiagramSceneEdge("one", "CP-1", "CP-1", "REALIZES", .8, "impact", 0, 0, 0, 0, m)));
        assertDoesNotThrow(() -> com.taxonomy.reporting.api.document.ArchitectureEvidence.validateEvidence(graph, same));
        var missing = new DiagramScene("T", 760, 420, "LR", List.of(sceneNode),
            List.of(new DiagramSceneEdge("one", "CP-1", "CP-1", "REALIZES", .8, "impact", 0, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> com.taxonomy.reporting.api.document.ArchitectureEvidence.validateEvidence(graph, missing));
    }
    @Test void scopedEvidenceOrderIsStableButExactQuoteChangesTheDigest() {
        var source = new RelationSearchModel.Node("CP-1", "CP", "Source", "source description", false);
        var target = new RelationSearchModel.Node("CR-1", "CR", "Target", "target description", false);
        var first = new RelationSearchModel.Edge(new RelationSearchModel.Contribution(source, "contribution", "quote one", "condition"),
                target, "REALIZES", RelationSearchModel.Direction.OUTGOING, null);
        var second = new RelationSearchModel.Edge(new RelationSearchModel.Contribution(source, "contribution", "quote two", "condition"),
                target, "REALIZES", RelationSearchModel.Direction.OUTGOING, null);
        var forward = new DiagramRelationMetadata(null, null, null, null, null, null, null, List.of(first, second));
        var reverse = new DiagramRelationMetadata(null, null, null, null, null, null, null, List.of(second, first));
        var changed = new DiagramRelationMetadata(null, null, null, null, null, null, null, List.of(first, first));
        assertEquals(hash(edge("one", forward)), hash(edge("one", reverse)));
        assertNotEquals(hash(edge("one", forward)), hash(edge("one", changed)));
        assertEquals("e4989f9744e906595318a77909379383cc3987902226c383b993a53c055a180d",
                hash(new DiagramEdge("one", "CP-1", "CR-1", "REALIZES", .8, "impact")));
    }
    private static DiagramRelationMetadata metadata(RelationOrigin origin, String reason) {
        return new DiagramRelationMetadata(42L, origin, .75, "included", reason, "present", null, List.of());
    }
    private static DiagramEdge edge(String id, DiagramRelationMetadata m) {
        return new DiagramEdge(id, "CP-1", "CR-1", "REALIZES", .8, "impact", m);
    }
    private static String hash(DiagramEdge... edges) {
        return ArchitectureReportDocument.graphSha256(new DiagramModel("T", List.of(), List.of(edges), new DiagramLayout("LR", true)));
    }
}
