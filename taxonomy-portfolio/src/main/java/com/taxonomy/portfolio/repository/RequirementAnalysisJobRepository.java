package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RequirementAnalysisJobRepository extends JpaRepository<RequirementAnalysisJob, String> {

    String JOB_ITEM_READ = """
            select new com.taxonomy.portfolio.repository.AnalysisJobReadRow(
                job.id, job.projectId, job.status, job.idempotencyKey, job.provider,
                job.maxArchitectureNodes, job.requestedBy, job.workspaceId,
                job.createdAt, job.startedAt, job.completedAt,
                job.totalItems, job.successfulItems, job.partialItems, job.failedItems, job.errorSummary,
                job.providerPluginId, job.providerPluginVersion, job.providerPluginSha256,
                job.providerConfigRevision, job.analysisReasonLength, job.analysisLanguage,
                item.id, item.requirementId, requirement.requirementKey, item.requirementVersionId,
                admittedVersion.versionNumber, item.status, item.snapshotId, item.attempt,
                item.startedAt, item.completedAt, item.errorMessage)
              from RequirementAnalysisJob job
              left join RequirementAnalysisJobItem item
                on item.jobId = job.id and item.projectId = job.projectId and item.scopeKey = job.scopeKey
              left join item.requirement requirement
              left join item.requirementVersion admittedVersion
            """;

    @Query(JOB_ITEM_READ + """
             where job.id = :id and job.projectId = :projectId and job.scopeKey = :scopeKey
             order by requirement.requirementKey asc
            """)
    List<AnalysisJobReadRow> findReadRowsByIdAndProjectIdAndScopeKey(
            @Param("id") String id, @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

    @Query(JOB_ITEM_READ + """
             where job.projectId = :projectId and job.scopeKey = :scopeKey
             order by job.createdAt desc, requirement.requirementKey asc
            """)
    List<AnalysisJobReadRow> findReadRowsByProjectIdAndScopeKey(
            @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

    @Query(JOB_ITEM_READ + """
             where job.projectId = :projectId and job.scopeKey = :scopeKey and job.id in :ids
             order by job.createdAt desc, requirement.requirementKey asc
            """)
    List<AnalysisJobReadRow> findReadRowsByIdsAndProjectIdAndScopeKey(
            @Param("ids") Collection<String> ids, @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

    record OperationKeyCandidate(String id, String idempotencyKey) {}

    @Query("""
            select new com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository$OperationKeyCandidate(
                job.id, job.idempotencyKey)
              from RequirementAnalysisJob job
             where job.projectId = :projectId and job.scopeKey = :scopeKey and job.idempotencyKey in :keys
            """)
    List<OperationKeyCandidate> findCanonicalOperationKeyCandidates(
            @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey, @Param("keys") List<String> keys);

    @Query("""
            select new com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository$OperationKeyCandidate(
                job.id, job.idempotencyKey)
              from RequirementAnalysisJob job
             where job.projectId = :projectId and job.scopeKey = :scopeKey
               and locate(:operationId, job.idempotencyKey) > 0
            """)
    List<OperationKeyCandidate> findLiteralOperationKeyCandidates(
            @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey, @Param("operationId") String operationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from RequirementAnalysisJob job where job.id = :id "
            + "and job.projectId = :projectId and job.scopeKey = :scopeKey")
    Optional<RequirementAnalysisJob> findByIdAndProjectIdAndScopeKeyForUpdate(
            @Param("id") String id, @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

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
