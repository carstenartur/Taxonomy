package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequirementAnalysisSnapshotRepository
        extends JpaRepository<RequirementAnalysisSnapshot, String> {

    Optional<RequirementAnalysisSnapshot> findByIdAndProjectId(String id, Long projectId);

    List<RequirementAnalysisSnapshot> findByRequirementIdOrderByCreatedAtDesc(Long requirementId);

    List<RequirementAnalysisSnapshot> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
