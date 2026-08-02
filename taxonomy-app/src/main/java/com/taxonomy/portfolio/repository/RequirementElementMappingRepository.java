package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.RequirementElementMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RequirementElementMappingRepository
        extends JpaRepository<RequirementElementMapping, Long> {

    List<RequirementElementMapping> findBySnapshotIdOrderByTaxonomyRootAscNodeCodeAsc(String snapshotId);

    Optional<RequirementElementMapping> findByIdAndSnapshotProjectId(Long id, Long projectId);

    @Query("""
            select mapping
              from RequirementElementMapping mapping
              join mapping.snapshot snapshot
              join snapshot.requirement requirement
             where snapshot.project.id = :projectId
               and requirement.currentAnalysisSnapshotId = snapshot.id
             order by requirement.requirementKey, mapping.taxonomyRoot, mapping.nodeCode
            """)
    List<RequirementElementMapping> findCurrentMappingsForProject(@Param("projectId") Long projectId);
}
