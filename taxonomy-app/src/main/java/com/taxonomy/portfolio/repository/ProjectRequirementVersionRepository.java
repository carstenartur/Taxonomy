package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import jakarta.persistence.Persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRequirementVersionRepository
        extends JpaRepository<ProjectRequirementVersion, Long> {

    List<ProjectRequirementVersion> findByRequirementIdAndScopeKeyOrderByVersionNumberDesc(
            Long requirementId, String scopeKey);

    Optional<ProjectRequirementVersion>
            findFirstByRequirementIdAndScopeKeyOrderByVersionNumberDesc(
                    Long requirementId, String scopeKey);

    Optional<ProjectRequirementVersion> findByRequirementIdAndScopeKeyAndVersionNumber(
            Long requirementId, String scopeKey, int versionNumber);

    Optional<ProjectRequirementVersion> findByRequirementIdAndScopeKeyAndContentHash(
            Long requirementId, String scopeKey, String contentHash);

    /**
     * Resolve from the already populated persistence context when a requirement
     * list join-fetched its current version. Otherwise execute a query containing
     * the complete requirement and tenant key; never initialize an ID-only proxy.
     */
    default Optional<ProjectRequirementVersion> findByIdAndRequirementIdAndScopeKey(
            Long id, Long requirementId, String scopeKey) {
        if (id == null || requirementId == null
                || scopeKey == null || scopeKey.isBlank()) {
            return Optional.empty();
        }
        String normalizedScope = scopeKey.strip();
        ProjectRequirementVersion candidate = getReferenceById(id);
        if (Persistence.getPersistenceUtil().isLoaded(candidate)) {
            return Optional.of(candidate)
                    .filter(version -> requirementId.equals(version.getRequirementId()))
                    .filter(version -> normalizedScope.equals(version.getScopeKey()));
        }
        return findExactTenantVersion(id, requirementId, normalizedScope);
    }

    @Query("select version from ProjectRequirementVersion version "
            + "where version.id = :id and version.requirementId = :requirementId "
            + "and version.scopeKey = :scopeKey")
    Optional<ProjectRequirementVersion> findExactTenantVersion(
            @Param("id") Long id,
            @Param("requirementId") Long requirementId,
            @Param("scopeKey") String scopeKey);

    /** Compatibility methods retained while all callers migrate to exact scope. */
    @Deprecated(forRemoval = false)
    List<ProjectRequirementVersion> findByRequirementIdOrderByVersionNumberDesc(
            Long requirementId);

    @Deprecated(forRemoval = false)
    Optional<ProjectRequirementVersion> findFirstByRequirementIdOrderByVersionNumberDesc(
            Long requirementId);

    @Deprecated(forRemoval = false)
    Optional<ProjectRequirementVersion> findByRequirementIdAndVersionNumber(
            Long requirementId, int versionNumber);

    @Deprecated(forRemoval = false)
    Optional<ProjectRequirementVersion> findByRequirementIdAndContentHash(
            Long requirementId, String contentHash);

    @Deprecated(forRemoval = false)
    Optional<ProjectRequirementVersion> findByIdAndRequirementId(
            Long id, Long requirementId);
}
