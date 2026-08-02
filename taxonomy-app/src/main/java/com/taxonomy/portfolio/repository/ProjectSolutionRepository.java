package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ProjectSolution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectSolutionRepository extends JpaRepository<ProjectSolution, Long> {

    List<ProjectSolution> findByProjectIdOrderByPriorityDescSolutionTitleAsc(Long projectId);

    Optional<ProjectSolution> findByIdAndProjectId(Long id, Long projectId);

    Optional<ProjectSolution> findByProjectIdAndSolutionId(Long projectId, Long solutionId);
}
