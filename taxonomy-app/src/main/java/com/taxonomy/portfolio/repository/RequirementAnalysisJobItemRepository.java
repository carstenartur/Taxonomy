package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RequirementAnalysisJobItemRepository extends JpaRepository<RequirementAnalysisJobItem, Long> {

    List<RequirementAnalysisJobItem> findByJobIdOrderByRequirementRequirementKeyAsc(String jobId);

    List<RequirementAnalysisJobItem> findByJobIdAndStatusOrderByRequirementRequirementKeyAsc(
            String jobId, AnalysisStatus status);

    List<RequirementAnalysisJobItem>
            findByJobIdAndStatusAndStartedAtBeforeOrderByRequirementRequirementKeyAsc(
                    String jobId, AnalysisStatus status, Instant startedBefore);

    Optional<RequirementAnalysisJobItem> findByJobIdAndRequirementId(String jobId, Long requirementId);

    /**
     * Atomically claims one pending work item. The status predicate is the
     * concurrency boundary: exactly one competing worker can change the row
     * from PENDING to RUNNING and therefore start the external LLM call.
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
               and item.status = :pendingStatus
            """)
    int claimPending(@Param("itemId") Long itemId,
                     @Param("pendingStatus") AnalysisStatus pendingStatus,
                     @Param("runningStatus") AnalysisStatus runningStatus,
                     @Param("startedAt") Instant startedAt);

    /** Atomically prepares one failed item for another attempt. */
    @Modifying
    @Query("""
            update RequirementAnalysisJobItem item
               set item.requirementVersion = :requirementVersion,
                   item.status = :pendingStatus,
                   item.snapshotId = null,
                   item.errorMessage = null,
                   item.startedAt = null,
                   item.completedAt = null,
                   item.attempt = item.attempt + 1,
                   item.rowVersion = item.rowVersion + 1
             where item.id = :itemId
               and item.status = :failedStatus
            """)
    int resetFailed(@Param("itemId") Long itemId,
                    @Param("failedStatus") AnalysisStatus failedStatus,
                    @Param("pendingStatus") AnalysisStatus pendingStatus,
                    @Param("requirementVersion") ProjectRequirementVersion requirementVersion);

    /**
     * Atomically recovers a RUNNING item only when its claim is still expired at
     * update time. Competing retry requests therefore cannot reset it twice.
     */
    @Modifying
    @Query("""
            update RequirementAnalysisJobItem item
               set item.requirementVersion = :requirementVersion,
                   item.status = :pendingStatus,
                   item.snapshotId = null,
                   item.errorMessage = null,
                   item.startedAt = null,
                   item.completedAt = null,
                   item.attempt = item.attempt + 1,
                   item.rowVersion = item.rowVersion + 1
             where item.id = :itemId
               and item.status = :runningStatus
               and item.startedAt < :staleBefore
            """)
    int resetExpiredRunning(@Param("itemId") Long itemId,
                            @Param("runningStatus") AnalysisStatus runningStatus,
                            @Param("pendingStatus") AnalysisStatus pendingStatus,
                            @Param("staleBefore") Instant staleBefore,
                            @Param("requirementVersion") ProjectRequirementVersion requirementVersion);
}
