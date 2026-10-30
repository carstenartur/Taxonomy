package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRequirementRepository extends JpaRepository<ProjectRequirement, Long> {

    // A scalar projection avoids Hibernate's unique-key resolution of the selected snapshot's
    // composite association, which would otherwise materialize all immutable result payloads.
    String READ_ROW = """
            select new com.taxonomy.portfolio.repository.ProjectRequirementReadRow(
                requirement.id, requirement.projectId, requirement.requirementKey, requirement.title,
                requirement.status, requirement.priority, requirement.criticality,
                requirement.requirementType, requirement.reviewStatus, requirement.ownerUsername,
                requirement.currentVersionId, requirement.currentAnalysisSnapshotId,
                requirement.createdAt, requirement.updatedAt,
                currentVersion.id, currentVersion.versionNumber, currentVersion.text,
                currentVersion.contentHash, currentVersion.changeReason, currentVersion.createdBy,
                currentVersion.createdAt, currentVersion.sourceArtifactId, currentVersion.sourceVersionId,
                currentVersion.sourceFragmentIdsJson, currentVersion.sectionReference,
                currentVersion.pageNumber, currentVersion.originalText)
              from ProjectRequirement requirement
              left join ProjectRequirementVersion currentVersion
                on currentVersion.requirementId = requirement.id
               and currentVersion.scopeKey = requirement.scopeKey
               and (currentVersion.id = requirement.currentVersionId
                    or (requirement.currentVersionId is null
                        and currentVersion.versionNumber = (
                            select max(latestVersion.versionNumber)
                              from ProjectRequirementVersion latestVersion
                             where latestVersion.requirementId = requirement.id
                               and latestVersion.scopeKey = requirement.scopeKey)))
            """;

    @Query(READ_ROW + """
             where requirement.id = :id and requirement.projectId = :projectId
               and requirement.scopeKey = :scopeKey
            """)
    Optional<ProjectRequirementReadRow> findReadRowByIdAndProjectIdAndScopeKey(
            @Param("id") Long id, @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

    @Query(READ_ROW + """
             where requirement.projectId = :projectId and requirement.scopeKey = :scopeKey
             order by requirement.requirementKey asc
            """)
    List<ProjectRequirementReadRow> findReadRowsByProjectIdAndScopeKey(
            @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

    /** Intake bounds its scalar current-version evidence in the database before provider work. */
    @Query(READ_ROW + """
             where requirement.scopeKey = :scopeKey
               and (:projectId is null or requirement.projectId = :projectId)
             order by requirement.requirementKey asc, requirement.id asc
            """)
    Slice<ProjectRequirementReadRow> findIntakeRows(
            @Param("scopeKey") String scopeKey, @Param("projectId") Long projectId, Pageable pageable);

    @Query(READ_ROW + """
             where requirement.projectId = :projectId and requirement.scopeKey = :scopeKey
               and requirement.status = :status
             order by requirement.requirementKey asc, requirement.id asc
            """)
    Slice<ProjectRequirementReadRow> findReadRowsByProjectIdAndScopeKeyAndStatus(
            @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey,
            @Param("status") RequirementStatus status, Pageable pageable);

    boolean existsByIdAndProjectIdAndScopeKey(Long id, Long projectId, String scopeKey);

    @EntityGraph(attributePaths = "currentVersion")
    List<ProjectRequirement> findByProjectIdAndScopeKeyOrderByRequirementKeyAsc(
            Long projectId, String scopeKey);

    @EntityGraph(attributePaths = "currentVersion")
    Slice<ProjectRequirement> findByProjectIdAndScopeKeyAndStatusOrderByRequirementKeyAscIdAsc(
            Long projectId, String scopeKey, RequirementStatus status, Pageable pageable);

    Optional<ProjectRequirement> findByIdAndProjectIdAndScopeKey(
            Long id, Long projectId, String scopeKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select new com.taxonomy.portfolio.repository.RequirementAnalysisSelectionRow(
                requirement.id, requirement.currentVersionId, requirement.currentAnalysisSnapshotId)
              from ProjectRequirement requirement
             where requirement.id = :id and requirement.projectId = :projectId
               and requirement.scopeKey = :scopeKey
            """)
    Optional<RequirementAnalysisSelectionRow> findAnalysisSelectionForUpdate(
            @Param("id") Long id, @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select requirement from ProjectRequirement requirement "
            + "where requirement.id = :id and requirement.projectId = :projectId "
            + "and requirement.scopeKey = :scopeKey")
    Optional<ProjectRequirement> findByIdAndProjectIdAndScopeKeyForUpdate(
            @Param("id") Long id,
            @Param("projectId") Long projectId,
            @Param("scopeKey") String scopeKey);

    Optional<ProjectRequirement> findByProjectIdAndScopeKeyAndRequirementKeyIgnoreCase(
            Long projectId, String scopeKey, String requirementKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select requirement from ProjectRequirement requirement "
            + "where requirement.projectId = :projectId and requirement.scopeKey = :scopeKey "
            + "and lower(requirement.requirementKey) = lower(:requirementKey)")
    Optional<ProjectRequirement> findByProjectIdAndScopeKeyAndRequirementKeyIgnoreCaseForUpdate(
            @Param("projectId") Long projectId,
            @Param("scopeKey") String scopeKey,
            @Param("requirementKey") String requirementKey);

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
            + "where requirement.id = :id and requirement.projectId = :projectId")
    Optional<ProjectRequirement> findByIdAndProjectIdForUpdate(
            @Param("id") Long id,
            @Param("projectId") Long projectId);

    @Deprecated(forRemoval = false)
    Optional<ProjectRequirement> findByProjectIdAndRequirementKeyIgnoreCase(
            Long projectId, String requirementKey);

    @Deprecated(forRemoval = false)
    long countByProjectId(Long projectId);
}
