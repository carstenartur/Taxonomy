package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.service.PortablePortfolioGitService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReformulationEvidenceConcurrencyAndQueryContractTest {

    @Test
    void headMaterializationOwnsTheTransactionBoundary() throws Exception {
        Method method = PortablePortfolioGitService.class.getMethod(
                "materializeHead", String.class, String.class, WorkspaceContext.class);

        assertThat(method.getAnnotation(Transactional.class))
                .as("materializeHead must open the transaction before self-dispatch reaches materialize")
                .isNotNull();
    }

    @Test
    void adoptionEvidenceLoadsPortableAssociationsWithoutPerReceiptQueries() throws Exception {
        Method method = ReformulationAdoptionRepository.class.getDeclaredMethod(
                "findByScopeKeyOrderByCreatedAtAsc", String.class);
        EntityGraph graph = method.getAnnotation(EntityGraph.class);

        assertThat(graph).isNotNull();
        assertThat(List.of(graph.attributePaths()))
                .contains("preview", "version", "version.requirement", "version.requirement.project");

        Method bulkVersions = ProjectRequirementVersionRepository.class.getDeclaredMethod(
                "findByScopeKeyAndIdIn", String.class, Collection.class);
        assertThat(bulkVersions).isNotNull();
    }

    @Test
    void importedEvidenceSerializesOnItsTargetRequirementBeforeIdempotenceCheck() throws Exception {
        Method method = ProjectRequirementRepository.class.getDeclaredMethod(
                "findByProjectIdAndScopeKeyAndRequirementKeyIgnoreCaseForUpdate",
                Long.class, String.class, String.class);
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value().name()).isEqualTo("PESSIMISTIC_WRITE");
    }
}
