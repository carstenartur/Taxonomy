package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequirementAnalysisJobRepository extends JpaRepository<RequirementAnalysisJob, String> {

    Optional<RequirementAnalysisJob> findByIdAndProjectId(String id, Long projectId);

    Optional<RequirementAnalysisJob> findByProjectIdAndIdempotencyKey(Long projectId, String idempotencyKey);

    List<RequirementAnalysisJob> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
