package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequirementAnalysisJobRepository extends JpaRepository<RequirementAnalysisJob, String> {

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
