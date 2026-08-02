package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.RequirementRelationMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequirementRelationMappingRepository
        extends JpaRepository<RequirementRelationMapping, Long> {

    List<RequirementRelationMapping> findBySnapshotIdOrderBySourceCodeAscTargetCodeAsc(String snapshotId);

    Optional<RequirementRelationMapping> findByIdAndSnapshotProjectId(Long id, Long projectId);
}
