package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRequirementVersionRepository extends JpaRepository<ProjectRequirementVersion, Long> {

    List<ProjectRequirementVersion> findByRequirementIdOrderByVersionNumberDesc(Long requirementId);

    Optional<ProjectRequirementVersion> findFirstByRequirementIdOrderByVersionNumberDesc(Long requirementId);

    Optional<ProjectRequirementVersion> findByRequirementIdAndVersionNumber(
            Long requirementId, int versionNumber);

    Optional<ProjectRequirementVersion> findByRequirementIdAndContentHash(Long requirementId, String contentHash);

    /**
     * Uses the first-level persistence cache when the version was fetched with
     * its requirement list, while retaining the requirement ownership check.
     */
    default Optional<ProjectRequirementVersion> findByIdAndRequirementId(Long id, Long requirementId) {
        if (id == null || requirementId == null) {
            return Optional.empty();
        }
        return findById(id)
                .filter(version -> requirementId.equals(version.getRequirement().getId()));
    }
}
