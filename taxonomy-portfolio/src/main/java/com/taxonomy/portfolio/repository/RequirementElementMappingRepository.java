package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.RequirementElementMapping;
import com.taxonomy.portfolio.dto.PortfolioDtos.ElementMappingView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RequirementElementMappingRepository
        extends JpaRepository<RequirementElementMapping, Long> {

    @Query("""
            select new com.taxonomy.portfolio.dto.PortfolioDtos$ElementMappingView(
                mapping.id, mapping.snapshotId, mapping.nodeCode, mapping.nodeTitle, mapping.taxonomyRoot,
                mapping.directScore, mapping.relevance, mapping.confidence, mapping.mappingOrigin,
                mapping.hierarchyPath, mapping.presenceReason, mapping.selectedForImpact,
                mapping.reviewStatus, mapping.actionStatus, mapping.actionEvidence,
                mapping.decisionBy, mapping.decisionAt, mapping.decisionComment)
              from RequirementElementMapping mapping
             where mapping.snapshotId = :snapshotId and mapping.scopeKey = :scopeKey
             order by mapping.taxonomyRoot asc, mapping.nodeCode asc
            """)
    List<ElementMappingView> findViewsBySnapshotIdAndScopeKey(
            @Param("snapshotId") String snapshotId, @Param("scopeKey") String scopeKey);

    @Query("""
            select new com.taxonomy.portfolio.repository.CurrentElementMappingReadRow(
                mapping.snapshotId, snapshot.requirementId, mapping.nodeCode, mapping.directScore, mapping.relevance)
              from RequirementElementMapping mapping
              join mapping.snapshot snapshot
              join snapshot.requirement requirement
             where snapshot.projectId = :projectId
               and snapshot.scopeKey = :scopeKey and mapping.scopeKey = :scopeKey
               and requirement.scopeKey = :scopeKey
               and requirement.currentAnalysisSnapshotId = snapshot.id
             order by requirement.requirementKey, mapping.taxonomyRoot, mapping.nodeCode
            """)
    List<CurrentElementMappingReadRow> findCurrentProposalInputsForProject(
            @Param("projectId") Long projectId, @Param("scopeKey") String scopeKey);

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
