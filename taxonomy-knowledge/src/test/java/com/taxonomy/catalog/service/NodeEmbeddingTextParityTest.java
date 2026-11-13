package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.model.RelationType;
import com.taxonomy.search.NodeEmbeddingBinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class NodeEmbeddingTextParityTest {
    @ParameterizedTest
    @MethodSource("legacyTexts")
    void selectedSnapshotFormatterPreservesExactText(TaxonomyNode node, String expected) {
        assertThat(NodeEmbeddingText.buildEnrichedText(node)).isEqualTo(expected);
    }

    @Test
    void globalNodeInputExcludesOutgoingAndIncomingBusinessRelationsAcrossOwners() {
        var central = node("Shared capability", "Reference description.");
        var workspace = relation(RelationType.SUPPORTS, central, node("Private working-copy target", null));
        workspace.setRepositoryId("repo-a");
        workspace.setWorkspaceId("workspace-a");
        var foreign = relation(RelationType.DEPENDS_ON, node("Other repository source", null), central);
        foreign.setRepositoryId("repo-b");
        central.getOutgoingRelations().add(workspace);
        central.getIncomingRelations().add(foreign);
        assertThat(NodeEmbeddingBinder.Bridge.buildEnrichedText(central)).isEqualTo("Shared capability.\nReference description.");
        assertThat(NodeEmbeddingText.buildEnrichedText(central)).contains("Private working-copy target", "Other repository source");
    }

    private static Stream<Arguments> legacyTexts() {
        var full = node("Secure communications", "Support coordination.");
        full.getOutgoingRelations().add(relation(RelationType.SUPPORTS, full, node("Medical coordination", null)));
        full.getOutgoingRelations().add(relation(null, full, node("Ignored", null)));
        full.getOutgoingRelations().add(relation(RelationType.REALIZES, full, null));
        full.getIncomingRelations().add(relation(RelationType.SUPPORTS, node("Planning", null), full));
        full.getIncomingRelations().add(relation(RelationType.REALIZES, null, full));
        var untyped = node(null, "  ");
        untyped.getOutgoingRelations().add(relation(null, untyped, null));
        untyped.getIncomingRelations().add(relation(null, null, untyped));
        return Stream.of(
                Arguments.of(full, "Secure communications.\nSupport coordination.\n"
                        + "Outgoing: supports Medical coordination, realizes .\n"
                        + "Incoming: supports Planning, realizes ."),
                Arguments.of(node("Name", "  "), "Name."),
                Arguments.of(node(null, null), ""),
                Arguments.of(untyped, "Outgoing: .\nIncoming: ."));
    }

    private static TaxonomyNode node(String name, String description) {
        var node = new TaxonomyNode();
        node.setNameEn(name);
        node.setDescriptionEn(description);
        return node;
    }

    private static TaxonomyRelation relation(RelationType type, TaxonomyNode source, TaxonomyNode target) {
        var relation = new TaxonomyRelation();
        relation.setRelationType(type);
        relation.setSourceNode(source);
        relation.setTargetNode(target);
        return relation;
    }
}
