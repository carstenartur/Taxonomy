package com.taxonomy.relations.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalDuplicateProjectionArchitectureTest {

    private static final Path PROPOSAL_SERVICE = Path.of(
            "src/main/java/com/taxonomy/relations/service/"
                    + "RelationProposalService.java");
    private static final Path VALIDATION_SERVICE = Path.of(
            "src/main/java/com/taxonomy/relations/service/"
                    + "RelationValidationService.java");
    private static final Path RELATION_READ_SERVICE = Path.of(
            "src/main/java/com/taxonomy/relations/service/"
                    + "RelationProjectionReadService.java");

    @Test
    void proposalDuplicateValidationUsesOneProjectedIdentitySnapshot()
            throws Exception {
        String proposal = Files.readString(PROPOSAL_SERVICE);
        String validation = Files.readString(VALIDATION_SERVICE);
        String relationRead = Files.readString(RELATION_READ_SERVICE);

        assertThat(proposal)
                .contains("relationReadService.readIdentitySnapshot(tenant)")
                .contains("IdentitySnapshot existingRelations")
                .contains("existingRelations.contains(")
                .doesNotContain("TaxonomyRelationRepository")
                .doesNotContain("findBySourceNodeCodeAndRelationTypeIn");
        assertThat(validation)
                .doesNotContain("TaxonomyRelationRepository")
                .doesNotContain("relationRepository")
                .doesNotContain("findBySourceNodeCodeAndRelationTypeIn");
        assertThat(relationRead)
                .contains("public IdentitySnapshot readIdentitySnapshot(")
                .contains("ResolvedSource source = resolveSource(selected)")
                .contains("ReadModel.LEGACY_FALLBACK")
                .contains("throw unavailable(selected, readiness, pendingRecoveries)");
    }
}
