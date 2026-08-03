package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.RequirementSolutionLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RequirementSolutionLinkRepository
        extends JpaRepository<RequirementSolutionLink, Long> {

    List<RequirementSolutionLink> findByProjectSolutionIdOrderByRequirementRequirementKeyAsc(
            Long projectSolutionId);

    List<RequirementSolutionLink>
            findByProjectSolutionIdInOrderByProjectSolutionIdAscRequirementRequirementKeyAsc(
                    Collection<Long> projectSolutionIds);

    List<RequirementSolutionLink> findByRequirementIdOrderByProjectSolutionSolutionTitleAsc(
            Long requirementId);

    Optional<RequirementSolutionLink> findByProjectSolutionIdAndRequirementId(
            Long projectSolutionId, Long requirementId);

    @Query("""
            select link
              from RequirementSolutionLink link
             where link.projectSolution.project.id = :projectId
             order by link.projectSolution.solution.title, link.requirement.requirementKey
            """)
    List<RequirementSolutionLink> findByProjectId(@Param("projectId") Long projectId);
}
