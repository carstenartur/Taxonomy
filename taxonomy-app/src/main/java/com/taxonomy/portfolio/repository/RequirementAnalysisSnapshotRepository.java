package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequirementAnalysisSnapshotRepository
        extends JpaRepository<RequirementAnalysisSnapshot, String> {

    Optional<RequirementAnalysisSnapshot> findByIdAndProjectIdAndScopeKey(
            String id, Long projectId, String scopeKey);

    List<RequirementAnalysisSnapshot>
            findByRequirementIdAndProjectIdAndScopeKeyOrderByCreatedAtDesc(
                    Long requirementId, Long projectId, String scopeKey);

    List<RequirementAnalysisSnapshot> findByProjectIdAndScopeKeyOrderByCreatedAtDesc(
            Long projectId, String scopeKey);

    /** Compatibility signatures retained while all callers migrate. */
    @Deprecated(forRemoval = false)
    Optional<RequirementAnalysisSnapshot> findByIdAndProjectId(String id, Long projectId);

    @Deprecated(forRemoval = false)
    List<RequirementAnalysisSnapshot> findByRequirementIdOrderByCreatedAtDesc(Long requirementId);

    @Deprecated(forRemoval = false)
    List<RequirementAnalysisSnapshot> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
