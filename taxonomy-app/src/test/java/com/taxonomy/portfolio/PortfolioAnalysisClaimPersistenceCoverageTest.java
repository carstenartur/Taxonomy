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
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioAnalysisClaimPersistenceCoverageTest {

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

    @Test
    void activePendingWorkspaceClaimUsesExactWorkspaceAndStripsSessionId() {
        PortfolioAnalysisWorkQueue.WorkItem claim = claim(1);
        stubClaim(claim, AnalysisStatus.PENDING, AnalysisStatus.RUNNING, 1);
        AnalysisResult analysis = analysisWithHypothesis();
        WorkspaceContext context = new WorkspaceContext(
                "architect", "ws-1", "draft", "repo-a");

        service.persistSnapshot(
                claim,
                "snapshot-1",
                "  portfolio:snapshot-1  ",
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

        verify(hypothesisService).persistFromAnalysisAfterCommit(
                analysis.getProvisionalRelations(),
                "portfolio:snapshot-1",
                RepositoryContext.workspace(
                        "repo-a", "ws-1", "draft", "architect"));
        verify(persistenceService).persistSnapshot(
                claim.itemId(),
                claim.jobId(),
                claim.projectId(),
                claim.scopeKey(),
                "snapshot-1",
                "  portfolio:snapshot-1  ",
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
    void nullAnalysisSkipsDeferredHypothesesButStillFinalizesActiveClaim() {
        PortfolioAnalysisWorkQueue.WorkItem claim = claim(1);
        stubClaim(claim, AnalysisStatus.RUNNING, AnalysisStatus.RUNNING, 1);
        WorkspaceContext context = new WorkspaceContext(
                "architect", null, "main", "repo-a");

        service.persistSnapshot(
                claim,
                "snapshot-1",
                null,
                null,
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

        verifyNoInteractions(hypothesisService);
        verify(persistenceService).persistSnapshot(
                claim.itemId(),
                claim.jobId(),
                claim.projectId(),
                claim.scopeKey(),
                "snapshot-1",
                null,
                null,
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
    void deferredHypothesesRequireANonBlankSessionId() {
        PortfolioAnalysisWorkQueue.WorkItem claim = claim(1);
        stubClaim(claim, AnalysisStatus.RUNNING, AnalysisStatus.RUNNING, 1);
        WorkspaceContext context = new WorkspaceContext(
                "architect", null, "main", "repo-a");

        assertThatThrownBy(() -> service.persistSnapshot(
                claim,
                "snapshot-1",
                "   ",
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
                .hasMessageContaining("session ID is required");

        verifyNoInteractions(hypothesisService, persistenceService);
    }

    @Test
    void incompleteAndMissingClaimsFailClosedBeforeAnyWorkerWrite() {
        assertThatThrownBy(() -> service.failItem(null, new IllegalStateException("late")))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Complete analysis claim identity");
        verifyNoInteractions(itemRepository, persistenceService, hypothesisService);

        PortfolioAnalysisWorkQueue.WorkItem claim = claim(1);
        when(itemRepository.findClaimForUpdate(
                claim.itemId(), claim.jobId(), claim.projectId(), claim.scopeKey()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.failItem(claim, new IllegalStateException("late")))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Analysis job item not found");
        verifyNoInteractions(persistenceService, hypothesisService);
    }

    @Test
    void terminalJobRejectsAnOtherwiseMatchingItemClaim() {
        PortfolioAnalysisWorkQueue.WorkItem claim = claim(1);
        stubClaim(claim, AnalysisStatus.CANCELLED, AnalysisStatus.RUNNING, 1);

        assertThatThrownBy(() -> service.failItem(claim, new IllegalStateException("late")))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("claim is no longer active");

        verifyNoInteractions(persistenceService, hypothesisService);
    }

    private void stubClaim(PortfolioAnalysisWorkQueue.WorkItem claim,
                           AnalysisStatus jobStatus,
                           AnalysisStatus itemStatus,
                           int attempt) {
        when(itemRepository.findClaimForUpdate(
                claim.itemId(), claim.jobId(), claim.projectId(), claim.scopeKey()))
                .thenReturn(Optional.of(item));
        when(item.getJob()).thenReturn(job);
        when(job.getStatus()).thenReturn(jobStatus);
        when(item.getStatus()).thenReturn(itemStatus);
        lenient().when(item.getAttempt()).thenReturn(attempt);
        lenient().when(item.getRequirementId()).thenReturn(claim.requirementId());
        lenient().when(item.getRequirementVersionId())
                .thenReturn(claim.requirementVersionId());
    }

    private static PortfolioAnalysisWorkQueue.WorkItem claim(int attempt) {
        return new PortfolioAnalysisWorkQueue.WorkItem(
                11L,
                "job-1",
                41L,
                "repo-a|workspace:ws-1|draft",
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
