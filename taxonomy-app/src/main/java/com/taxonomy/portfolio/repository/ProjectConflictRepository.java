package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.ProjectConflict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectConflictRepository extends JpaRepository<ProjectConflict, Long> {

    List<ProjectConflict> findByProjectIdOrderByConfidenceDescDetectedAtDesc(Long projectId);

    Optional<ProjectConflict> findByIdAndProjectId(Long id, Long projectId);

    Optional<ProjectConflict> findByProjectIdAndFingerprint(Long projectId, String fingerprint);
}
