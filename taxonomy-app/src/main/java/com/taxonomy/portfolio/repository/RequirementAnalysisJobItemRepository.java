package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RequirementAnalysisJobItemRepository extends JpaRepository<RequirementAnalysisJobItem, Long> {

    List<RequirementAnalysisJobItem>
            findByJobIdAndProjectIdAndScopeKeyOrderByRequirementRequirementKeyAsc(
                    String jobId, Long projectId, String scopeKey);

    List<RequirementAnalysisJobItem>
            findByJobIdAndProjectIdAndScopeKeyAndStatusOrderByRequirementRequirementKeyAsc(
                    String jobId, Long projectId, String scopeKey, AnalysisStatus status);

    List<RequirementAnalysisJobItem>
            findByJobIdAndProjectIdAndScopeKeyAndStatusAndStartedAtBeforeOrderByRequirementRequirementKeyAsc(
                    String jobId,
                    Long projectId,
                    String scopeKey,
                    AnalysisStatus status,
                    Instant startedBefore);

    Optional<RequirementAnalysisJobItem> findByIdAndJobIdAndProjectIdAndScopeKey(
            Long id, String jobId, Long projectId, String scopeKey);

    Optional<RequirementAnalysisJobItem>
            findByJobIdAndProjectIdAndScopeKeyAndRequirementId(
                    String jobId, Long projectId, String scopeKey, Long requirementId);

    /**
     * Atomically claims one pending work item inside the exact tenant. The full
     * identity predicate is the concurrency and authorization boundary.
     */
    @Modifying
    @Query("""
            update RequirementAnalysisJobItem item
               set item.status = :runningStatus,
                   item.startedAt = :startedAt,
                   item.completedAt = null,
                   item.errorMessage = null,
                   item.rowVersion = item.rowVersion + 1
             where item.id = :itemId
               and item.jobId = :jobId
               and item.projectId = :projectId
               and item.scopeKey = :scopeKey
               and item.status = :pendingStatus
            """)
    int claimPending(@Param("itemId") Long itemId,
                     @Param("jobId") String jobId,
                     @Param("projectId") Long projectId,
                     @Param("scopeKey") String scopeKey,
                     @Param("pendingStatus") AnalysisStatus pendingStatus,
                     @Param("runningStatus") AnalysisStatus runningStatus,
                     @Param("startedAt") Instant startedAt);

    /** Atomically prepares one exact-tenant failed item for another attempt. */
    @Modifying
    @Query("""
            update RequirementAnalysisJobItem item
               set item.requirementVersionId = :requirementVersionId,
                   item.status = :pendingStatus,
                   item.snapshotId = null,
                   item.errorMessage = null,
                   item.startedAt = null,
                   item.completedAt = null,
                   item.attempt = item.attempt + 1,
                   item.rowVersion = item.rowVersion + 1
             where item.id = :itemId
               and item.jobId = :jobId
               and item.projectId = :projectId
               and item.scopeKey = :scopeKey
               and item.status = :failedStatus
            """)
    int resetFailed(@Param("itemId") Long itemId,
                    @Param("jobId") String jobId,
                    @Param("projectId") Long projectId,
                    @Param("scopeKey") String scopeKey,
                    @Param("failedStatus") AnalysisStatus failedStatus,
                    @Param("pendingStatus") AnalysisStatus pendingStatus,
                    @Param("requirementVersionId") Long requirementVersionId);

    /**
     * Atomically recovers a RUNNING item only when its exact-tenant claim is
     * still expired at update time. Competing retry requests cannot reset it twice.
     */
    @Modifying
    @Query("""
            update RequirementAnalysisJobItem item
               set item.requirementVersionId = :requirementVersionId,
                   item.status = :pendingStatus,
                   item.snapshotId = null,
                   item.errorMessage = null,
                   item.startedAt = null,
                   item.completedAt = null,
                   item.attempt = item.attempt + 1,
                   item.rowVersion = item.rowVersion + 1
             where item.id = :itemId
               and item.jobId = :jobId
               and item.projectId = :projectId
               and item.scopeKey = :scopeKey
               and item.status = :runningStatus
               and item.startedAt < :staleBefore
            """)
    int resetExpiredRunning(@Param("itemId") Long itemId,
                            @Param("jobId") String jobId,
                            @Param("projectId") Long projectId,
                            @Param("scopeKey") String scopeKey,
                            @Param("runningStatus") AnalysisStatus runningStatus,
                            @Param("pendingStatus") AnalysisStatus pendingStatus,
                            @Param("staleBefore") Instant staleBefore,
                            @Param("requirementVersionId") Long requirementVersionId);

    /** Compatibility signatures retained while all callers migrate. */
    @Deprecated(forRemoval = false)
    List<RequirementAnalysisJobItem> findByJobIdOrderByRequirementRequirementKeyAsc(String jobId);

    @Deprecated(forRemoval = false)
    List<RequirementAnalysisJobItem> findByJobIdAndStatusOrderByRequirementRequirementKeyAsc(
            String jobId, AnalysisStatus status);

    @Deprecated(forRemoval = false)
    List<RequirementAnalysisJobItem>
            findByJobIdAndStatusAndStartedAtBeforeOrderByRequirementRequirementKeyAsc(
                    String jobId, AnalysisStatus status, Instant startedBefore);

    @Deprecated(forRemoval = false)
    Optional<RequirementAnalysisJobItem> findByJobIdAndRequirementId(
            String jobId, Long requirementId);
}
