package com.taxonomy.relations.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceOverlayScopeOwnershipTest {

    private static final List<String> WORKSPACE_SCOPED_MODELS = List.of(
            "src/main/java/com/taxonomy/relations/model/RelationHypothesis.java",
            "src/main/java/com/taxonomy/relations/model/RelationProposal.java",
            "src/main/java/com/taxonomy/catalog/model/TaxonomyRelation.java");

    @Test
    void relationAuthoritiesUseTheDisjointStorageKey() throws Exception {
        String commit = Files.readString(Path.of("../taxonomy-workspace/src/main/java/com/taxonomy/versioning/model/ArchitectureCommitIndex.java"));
        assertThat(commit).contains("WorkspaceOverlayScope.storageKeyFor(");
        for (String model : List.of("RelationDecisionProjection", "RelationDecisionProjectionCheckpoint", "RelationProjectionRecovery")) {
            String source = Files.readString(Path.of("src/main/java/com/taxonomy/relations/model", model + ".java"));
            assertThat(source).contains("WorkspaceOverlayScope.storageKeyFor(").doesNotContain("WorkspaceOverlayScope.keyFor(");
        }
    }

    @Test
    void workspaceScopedModelsDelegateOverlayIdentityToDomainOwner() throws Exception {
        for (String path : WORKSPACE_SCOPED_MODELS) {
            String source = Files.readString(Path.of(path));

            assertThat(source)
                    .as("workspace overlay identity in %s", path)
                    .contains("WorkspaceOverlayScope.storageKeyFor(")
                    .doesNotContain("\"__shared__\"");
        }
    }
}
