package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRequirementVersionRepository extends JpaRepository<ProjectRequirementVersion, Long> {

    List<ProjectRequirementVersion> findByRequirementIdOrderByVersionNumberDesc(Long requirementId);

    Optional<ProjectRequirementVersion> findFirstByRequirementIdOrderByVersionNumberDesc(Long requirementId);

    Optional<ProjectRequirementVersion> findByRequirementIdAndContentHash(Long requirementId, String contentHash);

    Optional<ProjectRequirementVersion> findByIdAndRequirementId(Long id, Long requirementId);
}
