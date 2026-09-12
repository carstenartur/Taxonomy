package com.taxonomy.relations.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceOverlayScopeOwnershipTest {

    private static final List<String> WORKSPACE_SCOPED_MODELS = List.of(
            "src/main/java/com/taxonomy/versioning/model/ArchitectureCommitIndex.java",
            "src/main/java/com/taxonomy/relations/model/RelationDecisionProjection.java",
            "src/main/java/com/taxonomy/relations/model/RelationDecisionProjectionCheckpoint.java",
            "src/main/java/com/taxonomy/relations/model/RelationProjectionRecovery.java",
            "src/main/java/com/taxonomy/relations/model/RelationHypothesis.java",
            "src/main/java/com/taxonomy/relations/model/RelationProposal.java",
            "src/main/java/com/taxonomy/catalog/model/TaxonomyRelation.java");

    @Test
    void workspaceScopedModelsDelegateOverlayIdentityToDomainOwner() throws Exception {
        for (String path : WORKSPACE_SCOPED_MODELS) {
            String source = Files.readString(Path.of(path));

            assertThat(source)
                    .as("workspace overlay identity in %s", path)
                    .contains("WorkspaceOverlayScope.keyFor(")
                    .doesNotContain("\"__shared__\"");
        }
    }
}
