package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.SolutionProductCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SolutionProductCandidateRepository
        extends JpaRepository<SolutionProductCandidate, Long> {

    List<SolutionProductCandidate> findByProjectSolutionIdOrderByCoveragePercentDesc(
            Long projectSolutionId);

    List<SolutionProductCandidate>
            findByProjectSolutionIdInOrderByProjectSolutionIdAscCoveragePercentDesc(
                    Collection<Long> projectSolutionIds);

    Optional<SolutionProductCandidate> findByProjectSolutionIdAndProductId(
            Long projectSolutionId, Long productId);

    @Query("""
            select candidate
              from SolutionProductCandidate candidate
             where candidate.projectSolution.project.id = :projectId
             order by candidate.projectSolution.solution.title, candidate.coveragePercent desc
            """)
    List<SolutionProductCandidate> findByProjectId(@Param("projectId") Long projectId);
}
