package com.taxonomy.portfolio;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.portfolio.service.PortfolioAnalysisClaimPersistenceService;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioAnalysisWorkQueue;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.versioning.service.HypothesisService;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioAnalysisClaimPersistenceUnitTest {

    @Mock
    private RequirementAnalysisJobItemRepository itemRepository;

    @Mock
    private PortfolioAnalysisPersistenceService persistenceService;

    @Mock
    private HypothesisService hypothesisService;

    @Mock
    private RequirementAnalysisJobItem item;

    @Mock
    private RequirementAnalysisJob job;

    @InjectMocks
    private PortfolioAnalysisClaimPersistenceService service;

    private final WorkspaceContext context = new WorkspaceContext(
            "architect", "ws-architect", "draft");

    @Test
    void activeClaimPersistsDeferredHypothesesBeforeItsSnapshot() {
        PortfolioAnalysisWorkQueue.WorkItem claim = claim(1);
        stubActiveClaim(claim, 1);
        AnalysisResult analysis = analysisWithHypothesis();

        service.persistSnapshot(
                claim,
                "snapshot-1",
                "portfolio:snapshot-1",
                analysis,
                null,
                null,
                null,
                "MOCK",
                null,
                "prompt-fingerprint",
                "taxonomy-fingerprint",
                context.username(),
                context,
                5L);

        InOrder order = inOrder(itemRepository, hypothesisService, persistenceService);
        order.verify(itemRepository).findClaimForUpdate(
                claim.itemId(), claim.jobId(), claim.projectId(), claim.scopeKey());
        order.verify(hypothesisService).persistFromAnalysis(
                analysis.getProvisionalRelations(),
                "portfolio:snapshot-1",
                context);
        order.verify(persistenceService).persistSnapshot(
                claim.itemId(),
                claim.jobId(),
                claim.projectId(),
                claim.scopeKey(),
                "snapshot-1",
                "portfolio:snapshot-1",
                analysis,
                null,
                null,
                null,
                "MOCK",
                null,
                "prompt-fingerprint",
                "taxonomy-fingerprint",
                context.username(),
                context,
                5L);
    }

    @Test
    void staleGenerationCannotPersistHypothesesOrSnapshot() {
        PortfolioAnalysisWorkQueue.WorkItem expired = claim(1);
        stubActiveClaim(expired, 2);

        assertThatThrownBy(() -> service.persistSnapshot(
                expired,
                "snapshot-stale",
                "portfolio:snapshot-stale",
                analysisWithHypothesis(),
                null,
                null,
                null,
                "MOCK",
                null,
                "prompt-fingerprint",
                "taxonomy-fingerprint",
                context.username(),
                context,
                5L))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("claim is no longer active");

        verifyNoInteractions(hypothesisService, persistenceService);
    }

    @Test
    void claimLookupUsesAPessimisticWriteLock() throws Exception {
        Method method = RequirementAnalysisJobItemRepository.class.getMethod(
                "findClaimForUpdate",
                Long.class,
                String.class,
                Long.class,
                String.class);

        Lock lock = method.getAnnotation(Lock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    private void stubActiveClaim(PortfolioAnalysisWorkQueue.WorkItem claim,
                                 int persistedAttempt) {
        when(itemRepository.findClaimForUpdate(
                claim.itemId(), claim.jobId(), claim.projectId(), claim.scopeKey()))
                .thenReturn(Optional.of(item));
        when(item.getJob()).thenReturn(job);
        when(job.getStatus()).thenReturn(AnalysisStatus.RUNNING);
        when(item.getStatus()).thenReturn(AnalysisStatus.RUNNING);
        when(item.getAttempt()).thenReturn(persistedAttempt);
        lenient().when(item.getRequirementId()).thenReturn(claim.requirementId());
        lenient().when(item.getRequirementVersionId())
                .thenReturn(claim.requirementVersionId());
    }

    private static PortfolioAnalysisWorkQueue.WorkItem claim(int attempt) {
        return new PortfolioAnalysisWorkQueue.WorkItem(
                11L,
                "job-1",
                41L,
                "repo|ws|draft",
                7L,
                "REQ-001",
                9L,
                1,
                "Need secure voice communications",
                attempt);
    }

    private static AnalysisResult analysisWithHypothesis() {
        AnalysisResult analysis = new AnalysisResult();
        analysis.setProvisionalRelations(List.of(
                new RelationHypothesisDto(
                        "CP",
                        "Capabilities",
                        "CR",
                        "Communications",
                        "REALIZES",
                        0.56,
                        "compatibility matrix")));
        return analysis;
    }
}
