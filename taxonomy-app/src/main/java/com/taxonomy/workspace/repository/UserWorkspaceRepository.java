package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.UserWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    List<UserWorkspace> findByUsernameAndArchivedFalseOrderByLastAccessedAtDesc(String username);

    Optional<UserWorkspace> findByUsernameAndIsDefaultTrue(String username);

    Optional<UserWorkspace> findByUsernameAndDisplayName(String username, String displayName);

    long countByUsernameAndArchivedFalse(String username);

    /**
     * Count disclosure-authorized rows without materializing workspace metadata.
     * Missing and unauthorized identifiers intentionally both return zero.
     */
    @Query("""
            select count(workspace)
            from UserWorkspace workspace
            where workspace.workspaceId = :workspaceId
              and (workspace.username = :username or workspace.shared = true)
            """)
    long countVisibleWorkspaceMetadata(
            @Param("workspaceId") String workspaceId,
            @Param("username") String username);
}
