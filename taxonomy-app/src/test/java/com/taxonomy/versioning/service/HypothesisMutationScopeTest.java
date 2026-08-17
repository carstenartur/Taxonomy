package com.taxonomy.versioning.service;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class HypothesisMutationScopeTest {

    @Test
    void centralReadAnalysisFailsBeforeAnyPersistenceOrGitAccess() {
        RelationHypothesisRepository hypotheses =
                mock(RelationHypothesisRepository.class);
        RelationEvidenceRepository evidence =
                mock(RelationEvidenceRepository.class);
        TaxonomyRelationService relations = mock(TaxonomyRelationService.class);
        TaxonomyNodeRepository nodes = mock(TaxonomyNodeRepository.class);
        DslGitRepositoryFactory repositories = mock(DslGitRepositoryFactory.class);
        HypothesisService service = new HypothesisService(
                hypotheses, evidence, relations, nodes, repositories);

        assertThatThrownBy(() -> service.persistFromAnalysis(
                List.of(mock(RelationHypothesisDto.class)),
                "analysis-1",
                RepositoryContext.centralRead(
                        "repo-a", "main", "alice")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit central write context");

        verifyNoInteractions(hypotheses, evidence, relations, nodes, repositories);
    }
}
