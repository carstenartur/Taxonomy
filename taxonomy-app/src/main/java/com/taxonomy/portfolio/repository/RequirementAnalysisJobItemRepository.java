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

    List<RequirementAnalysisJobItem> findByJobIdOrderByRequirementRequirementKeyAsc(String jobId);

    List<RequirementAnalysisJobItem> findByJobIdAndStatusOrderByRequirementRequirementKeyAsc(
            String jobId, AnalysisStatus status);

    Optional<RequirementAnalysisJobItem> findByJobIdAndRequirementId(String jobId, Long requirementId);

    /**
     * Atomically claims one pending work item. The status predicate is the
     * concurrency boundary: exactly one competing worker can change the row
     * from PENDING to RUNNING and therefore start the external LLM call.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
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
}
