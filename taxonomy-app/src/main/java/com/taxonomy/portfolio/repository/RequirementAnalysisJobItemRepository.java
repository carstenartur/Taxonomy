package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequirementAnalysisJobItemRepository extends JpaRepository<RequirementAnalysisJobItem, Long> {

    List<RequirementAnalysisJobItem> findByJobIdOrderByRequirementRequirementKeyAsc(String jobId);

    List<RequirementAnalysisJobItem> findByJobIdAndStatusOrderByRequirementRequirementKeyAsc(
            String jobId, AnalysisStatus status);

    Optional<RequirementAnalysisJobItem> findByJobIdAndRequirementId(String jobId, Long requirementId);
}
