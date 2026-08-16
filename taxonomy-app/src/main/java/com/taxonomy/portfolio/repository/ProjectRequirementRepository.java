package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ProjectRequirement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRequirementRepository extends JpaRepository<ProjectRequirement, Long> {

    @EntityGraph(attributePaths = "currentVersion")
    List<ProjectRequirement> findByProjectIdAndScopeKeyOrderByRequirementKeyAsc(
            Long projectId, String scopeKey);

    Optional<ProjectRequirement> findByIdAndProjectIdAndScopeKey(
            Long id, Long projectId, String scopeKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select requirement from ProjectRequirement requirement "
            + "where requirement.id = :id and requirement.project.id = :projectId "
            + "and requirement.scopeKey = :scopeKey")
    Optional<ProjectRequirement> findByIdAndProjectIdAndScopeKeyForUpdate(
            @Param("id") Long id,
            @Param("projectId") Long projectId,
            @Param("scopeKey") String scopeKey);

    Optional<ProjectRequirement> findByProjectIdAndScopeKeyAndRequirementKeyIgnoreCase(
            Long projectId, String scopeKey, String requirementKey);

    long countByProjectIdAndScopeKey(Long projectId, String scopeKey);

    /**
     * Compatibility methods for callers that have not yet migrated to the exact
     * tenant boundary. Productive repository-sensitive code must use the
     * scope-aware variants above.
     */
    @Deprecated(forRemoval = false)
    @EntityGraph(attributePaths = "currentVersion")
    List<ProjectRequirement> findByProjectIdOrderByRequirementKeyAsc(Long projectId);

    @Deprecated(forRemoval = false)
    Optional<ProjectRequirement> findByIdAndProjectId(Long id, Long projectId);

    @Deprecated(forRemoval = false)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select requirement from ProjectRequirement requirement "
            + "where requirement.id = :id and requirement.project.id = :projectId")
    Optional<ProjectRequirement> findByIdAndProjectIdForUpdate(
            @Param("id") Long id,
            @Param("projectId") Long projectId);

    @Deprecated(forRemoval = false)
    Optional<ProjectRequirement> findByProjectIdAndRequirementKeyIgnoreCase(
            Long projectId, String requirementKey);

    @Deprecated(forRemoval = false)
    long countByProjectId(Long projectId);
}
