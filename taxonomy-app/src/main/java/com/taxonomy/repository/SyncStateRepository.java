package com.taxonomy.repository;

import com.taxonomy.model.SyncState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JPA repository for {@link SyncState} entities.
 */
@Repository
public interface SyncStateRepository extends JpaRepository<SyncState, Long> {

    Optional<SyncState> findByUsername(String username);

    Optional<SyncState> findByWorkspaceId(String workspaceId);

    boolean existsByUsername(String username);
}
