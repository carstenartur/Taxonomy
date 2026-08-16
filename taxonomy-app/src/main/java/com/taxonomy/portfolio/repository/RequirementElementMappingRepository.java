package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.RequirementElementMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RequirementElementMappingRepository
        extends JpaRepository<RequirementElementMapping, Long> {

    List<RequirementElementMapping>
            findBySnapshotIdAndScopeKeyOrderByTaxonomyRootAscNodeCodeAsc(
                    String snapshotId, String scopeKey);

    Optional<RequirementElementMapping> findByIdAndScopeKeyAndSnapshotProjectId(
            Long id, String scopeKey, Long projectId);

    @Query("""
            select mapping
              from RequirementElementMapping mapping
              join mapping.snapshot snapshot
              join snapshot.requirement requirement
             where snapshot.projectId = :projectId
               and snapshot.scopeKey = :scopeKey
               and mapping.scopeKey = :scopeKey
               and requirement.scopeKey = :scopeKey
               and requirement.currentAnalysisSnapshotId = snapshot.id
             order by requirement.requirementKey, mapping.taxonomyRoot, mapping.nodeCode
            """)
    List<RequirementElementMapping> findCurrentMappingsForProject(
            @Param("projectId") Long projectId,
            @Param("scopeKey") String scopeKey);

    /** Compatibility signatures retained while all callers migrate. */
    @Deprecated(forRemoval = false)
    List<RequirementElementMapping> findBySnapshotIdOrderByTaxonomyRootAscNodeCodeAsc(
            String snapshotId);

    @Deprecated(forRemoval = false)
    Optional<RequirementElementMapping> findByIdAndSnapshotProjectId(Long id, Long projectId);

    @Deprecated(forRemoval = false)
    @Query("""
            select mapping
              from RequirementElementMapping mapping
              join mapping.snapshot snapshot
              join snapshot.requirement requirement
             where snapshot.projectId = :projectId
               and requirement.currentAnalysisSnapshotId = snapshot.id
             order by requirement.requirementKey, mapping.taxonomyRoot, mapping.nodeCode
            """)
    List<RequirementElementMapping> findCurrentMappingsForProject(
            @Param("projectId") Long projectId);
}
