package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ProjectRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRequirementRepository extends JpaRepository<ProjectRequirement, Long> {

    List<ProjectRequirement> findByProjectIdOrderByRequirementKeyAsc(Long projectId);

    Optional<ProjectRequirement> findByIdAndProjectId(Long id, Long projectId);

    Optional<ProjectRequirement> findByProjectIdAndRequirementKeyIgnoreCase(Long projectId, String requirementKey);

    long countByProjectId(Long projectId);
}
