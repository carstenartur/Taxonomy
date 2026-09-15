package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.WorkspaceProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JPA repository for {@link WorkspaceProjection} entities.
 */
@Repository
public interface WorkspaceProjectionRepository extends JpaRepository<WorkspaceProjection, Long> {

    Optional<WorkspaceProjection> findByUsername(String username);

    Optional<WorkspaceProjection> findByWorkspaceId(String workspaceId);

    boolean existsByUsername(String username);
}
