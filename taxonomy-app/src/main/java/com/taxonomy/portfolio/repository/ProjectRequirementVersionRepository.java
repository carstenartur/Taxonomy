package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import org.springframework.data.jpa.repository.JpaRepository;

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

    Optional<ProjectRequirementVersion> findByIdAndRequirementIdAndScopeKey(
            Long id, Long requirementId, String scopeKey);

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
    default Optional<ProjectRequirementVersion> findByIdAndRequirementId(
            Long id, Long requirementId) {
        if (id == null || requirementId == null) {
            return Optional.empty();
        }
        return findById(id)
                .filter(version -> requirementId.equals(version.getRequirement().getId()));
    }
}
