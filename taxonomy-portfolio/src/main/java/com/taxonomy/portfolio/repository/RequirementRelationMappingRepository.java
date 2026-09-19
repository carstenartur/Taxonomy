package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.RequirementRelationMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequirementRelationMappingRepository
        extends JpaRepository<RequirementRelationMapping, Long> {

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
