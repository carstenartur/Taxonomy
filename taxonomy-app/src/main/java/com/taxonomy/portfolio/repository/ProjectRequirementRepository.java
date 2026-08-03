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
    List<ProjectRequirement> findByProjectIdOrderByRequirementKeyAsc(Long projectId);

    Optional<ProjectRequirement> findByIdAndProjectId(Long id, Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select requirement from ProjectRequirement requirement "
            + "where requirement.id = :id and requirement.project.id = :projectId")
    Optional<ProjectRequirement> findByIdAndProjectIdForUpdate(@Param("id") Long id,
                                                               @Param("projectId") Long projectId);

    Optional<ProjectRequirement> findByProjectIdAndRequirementKeyIgnoreCase(Long projectId, String requirementKey);

    long countByProjectId(Long projectId);
}
