package com.taxonomy.versioning.service;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HypothesisCommitBoundaryTest {

    @Mock
    private RelationHypothesisRepository hypothesisRepository;
    @Mock
    private RelationEvidenceRepository evidenceRepository;
    @Mock
    private TaxonomyRelationService relationService;
    @Mock
    private TaxonomyNodeRepository nodeRepository;
    @Mock
    private DslGitRepositoryFactory repositoryFactory;
    @Mock
    private DslGitRepository workspaceRepository;

    private HypothesisService service;
    private RepositoryContext context;
    private RelationHypothesisDto hypothesis;

    @BeforeEach
    void setUp() throws Exception {
        service = new HypothesisService(
                hypothesisRepository,
                evidenceRepository,
                relationService,
                nodeRepository,
                repositoryFactory);
        context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "draft", "alice");
        hypothesis = new RelationHypothesisDto(
                "CP-1023", "Communication Capability",
                "CR-1047", "Infrastructure Services",
                "REALIZES", 0.82, "Architecture compatibility rule");

        when(hypothesisRepository.existsInRepositoryWorkspaceSession(
                anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);
        when(hypothesisRepository.save(any(RelationHypothesis.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(nodeRepository.findByCode(anyString())).thenReturn(Optional.empty());
        lenient().when(repositoryFactory.resolveRepository(context)).thenReturn(workspaceRepository);
        lenient().when(workspaceRepository.commitDsl(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn("commit-1");
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void successfulDatabaseCommitPublishesCanonicalDslExactlyAfterCommit() throws Exception {
        TransactionSynchronizationManager.initSynchronization();

        List<RelationHypothesis> persisted = service.persistFromAnalysisAfterCommit(
                List.of(hypothesis), "analysis-1", context);

        assertThat(persisted).hasSize(1);
        verifyNoInteractions(workspaceRepository);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(repositoryFactory).resolveRepository(context);
        verify(workspaceRepository).commitDsl(
                org.mockito.ArgumentMatchers.eq("draft"),
                org.mockito.ArgumentMatchers.contains("analysis-session:analysis-1"),
                org.mockito.ArgumentMatchers.eq("alice"),
                org.mockito.ArgumentMatchers.contains("analysis-1"));
    }

    @Test
    void rolledBackDatabaseTransactionPublishesNoGitCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.persistFromAnalysisAfterCommit(
                List.of(hypothesis), "analysis-rollback", context);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.forEach(synchronization -> synchronization.afterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(workspaceRepository);
        verifyNoInteractions(repositoryFactory);
    }
}
