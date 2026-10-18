package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RequirementAnalysisJobRepository extends JpaRepository<RequirementAnalysisJob, String> {

    @Query("""
            select new com.taxonomy.portfolio.repository.AnalysisJobReadRow(
                job.id, job.projectId, job.status, job.idempotencyKey, job.provider,
                job.maxArchitectureNodes, job.requestedBy, job.workspaceId,
                job.createdAt, job.startedAt, job.completedAt,
                job.totalItems, job.successfulItems, job.partialItems, job.failedItems, job.errorSummary,
                job.providerPluginId, job.providerPluginVersion, job.providerPluginSha256,
                job.providerConfigRevision, job.analysisReasonLength,
                item.id, item.requirementId, requirement.requirementKey, item.requirementVersionId,
                admittedVersion.versionNumber, item.status, item.snapshotId, item.attempt,
                item.startedAt, item.completedAt, item.errorMessage)
              from RequirementAnalysisJob job
              left join RequirementAnalysisJobItem item
                on item.jobId = job.id and item.projectId = job.projectId and item.scopeKey = job.scopeKey
              left join item.requirement requirement
              left join item.requirementVersion admittedVersion
             where job.id = :id and job.projectId = :projectId and job.scopeKey = :scopeKey
             order by requirement.requirementKey asc
            """)
    List<AnalysisJobReadRow> findReadRowsByIdAndProjectIdAndScopeKey(
            @Param("id") String id, @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

    @Query("""
            select new com.taxonomy.portfolio.repository.AnalysisJobReadRow(
                job.id, job.projectId, job.status, job.idempotencyKey, job.provider,
                job.maxArchitectureNodes, job.requestedBy, job.workspaceId,
                job.createdAt, job.startedAt, job.completedAt,
                job.totalItems, job.successfulItems, job.partialItems, job.failedItems, job.errorSummary,
                job.providerPluginId, job.providerPluginVersion, job.providerPluginSha256,
                job.providerConfigRevision, job.analysisReasonLength,
                item.id, item.requirementId, requirement.requirementKey, item.requirementVersionId,
                admittedVersion.versionNumber, item.status, item.snapshotId, item.attempt,
                item.startedAt, item.completedAt, item.errorMessage)
              from RequirementAnalysisJob job
              left join RequirementAnalysisJobItem item
                on item.jobId = job.id and item.projectId = job.projectId and item.scopeKey = job.scopeKey
              left join item.requirement requirement
              left join item.requirementVersion admittedVersion
             where job.projectId = :projectId and job.scopeKey = :scopeKey
             order by job.createdAt desc, requirement.requirementKey asc
            """)
    List<AnalysisJobReadRow> findReadRowsByProjectIdAndScopeKey(
            @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

    Optional<RequirementAnalysisJob> findByIdAndProjectIdAndScopeKey(
            String id, Long projectId, String scopeKey);

    Optional<RequirementAnalysisJob> findByProjectIdAndScopeKeyAndIdempotencyKey(
            Long projectId, String scopeKey, String idempotencyKey);

    List<RequirementAnalysisJob> findByProjectIdAndScopeKeyOrderByCreatedAtDesc(
            Long projectId, String scopeKey);

    /** Compatibility signatures retained until all tests and extensions migrate. */
    @Deprecated(forRemoval = false)
    Optional<RequirementAnalysisJob> findByIdAndProjectId(String id, Long projectId);

    @Deprecated(forRemoval = false)
    Optional<RequirementAnalysisJob> findByProjectIdAndIdempotencyKey(
            Long projectId, String idempotencyKey);

    @Deprecated(forRemoval = false)
    List<RequirementAnalysisJob> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
