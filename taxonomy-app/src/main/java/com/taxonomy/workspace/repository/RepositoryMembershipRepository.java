package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Persistence access for explicit repository-scoped user roles. */
public interface RepositoryMembershipRepository
        extends JpaRepository<RepositoryMembership, Long> {

    Optional<RepositoryMembership> findByRepositoryIdAndUsername(
            String repositoryId, String username);

    List<RepositoryMembership> findByRepositoryIdOrderByUsernameAsc(String repositoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT membership
            FROM RepositoryMembership membership
            WHERE membership.repositoryId = :repositoryId
            ORDER BY membership.username ASC
            """)
    List<RepositoryMembership> findByRepositoryIdForUpdate(
            @Param("repositoryId") String repositoryId);

    long countByRepositoryIdAndRole(String repositoryId, RepositoryRole role);
}
