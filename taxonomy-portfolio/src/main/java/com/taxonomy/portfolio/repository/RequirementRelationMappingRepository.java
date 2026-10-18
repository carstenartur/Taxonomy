package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.RequirementRelationMapping;
import com.taxonomy.portfolio.dto.PortfolioDtos.RelationMappingView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RequirementRelationMappingRepository
        extends JpaRepository<RequirementRelationMapping, Long> {

    @Query("""
            select new com.taxonomy.portfolio.dto.PortfolioDtos$RelationMappingView(
                mapping.id, mapping.snapshotId, mapping.sourceCode, mapping.targetCode, mapping.relationType,
                mapping.relationOrigin, mapping.relationCategory, mapping.relevance, mapping.confidence,
                mapping.presenceReason, mapping.reviewStatus, mapping.decisionBy, mapping.decisionAt,
                mapping.decisionComment)
              from RequirementRelationMapping mapping
             where mapping.snapshotId = :snapshotId and mapping.scopeKey = :scopeKey
             order by mapping.sourceCode asc, mapping.targetCode asc
            """)
    List<RelationMappingView> findViewsBySnapshotIdAndScopeKey(
            @Param("snapshotId") String snapshotId, @Param("scopeKey") String scopeKey);

    List<RequirementRelationMapping>
            findBySnapshotIdAndScopeKeyOrderBySourceCodeAscTargetCodeAsc(
                    String snapshotId, String scopeKey);

    Optional<RequirementRelationMapping> findByIdAndScopeKeyAndSnapshotProjectId(
            Long id, String scopeKey, Long projectId);

    /** Compatibility signatures retained while all callers migrate. */
    @Deprecated(forRemoval = false)
    List<RequirementRelationMapping> findBySnapshotIdOrderBySourceCodeAscTargetCodeAsc(
            String snapshotId);

    @Deprecated(forRemoval = false)
    Optional<RequirementRelationMapping> findByIdAndSnapshotProjectId(Long id, Long projectId);
}
