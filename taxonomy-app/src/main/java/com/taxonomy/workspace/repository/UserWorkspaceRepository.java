package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.UserWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA repository for {@link UserWorkspace} entities.
 */
@Repository
public interface UserWorkspaceRepository extends JpaRepository<UserWorkspace, Long> {

    List<UserWorkspace> findByUsername(String username);

    Optional<UserWorkspace> findByWorkspaceId(String workspaceId);

    Optional<UserWorkspace> findByUsernameAndSharedFalse(String username);

    Optional<UserWorkspace> findBySharedTrue();

    boolean existsByUsername(String username);
}
