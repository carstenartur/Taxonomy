package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Transactional cancellation boundary for one exact-tenant persisted analysis job. */
@Service
public class CopilotJobControlService {

    private final RequirementAnalysisJobRepository jobRepository;
    private final PortfolioAnalysisPersistenceService persistenceService;
    private final EntityManager entityManager;

    public CopilotJobControlService(
            RequirementAnalysisJobRepository jobRepository,
            PortfolioAnalysisPersistenceService persistenceService,
            EntityManager entityManager) {
        this.jobRepository = jobRepository;
        this.persistenceService = persistenceService;
        this.entityManager = entityManager;
    }

    @Transactional
    public AnalysisJobView cancel(
            String jobId,
            Long projectId,
            String username,
            WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        RequirementAnalysisJob job = jobRepository
                .findByIdAndProjectIdAndScopeKey(jobId, projectId, scopeKey)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Analysis job not found: " + jobId));
        if (isTerminal(job.getStatus())) {
            return persistenceService.getJob(jobId, projectId, username, context);
        }
        Instant now = Instant.now();
        entityManager.createQuery("""
                update RequirementAnalysisJobItem item
                   set item.status = :cancelled,
                       item.completedAt = :completedAt,
                       item.errorMessage = :message,
                       item.rowVersion = item.rowVersion + 1
                 where item.jobId = :jobId
                   and item.projectId = :projectId
                   and item.scopeKey = :scopeKey
                   and item.status in :activeStatuses
                """)
                .setParameter("cancelled", AnalysisStatus.CANCELLED)
                .setParameter("completedAt", now)
                .setParameter("message", "Cancelled by user")
                .setParameter("jobId", jobId)
                .setParameter("projectId", projectId)
                .setParameter("scopeKey", scopeKey)
                .setParameter("activeStatuses", List.of(
                        AnalysisStatus.PENDING, AnalysisStatus.RUNNING))
                .executeUpdate();
        job.cancel(now);
        jobRepository.save(job);
        entityManager.flush();
        entityManager.clear();
        return persistenceService.getJob(jobId, projectId, username, context);
    }

    private static boolean isTerminal(AnalysisStatus status) {
        return status == AnalysisStatus.SUCCESS
                || status == AnalysisStatus.PARTIAL
                || status == AnalysisStatus.FAILED
                || status == AnalysisStatus.CANCELLED;
    }
}
