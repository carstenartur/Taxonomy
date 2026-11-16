package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Persistence access for explicit repository-scoped user roles. */
public interface RepositoryMembershipRepository
        extends JpaRepository<RepositoryMembership, Long> {

    Optional<RepositoryMembership> findByRepositoryIdAndUsername(
            String repositoryId, String username);

    @Query("""
            select m from RepositoryMembership m
            where m.repositoryId = :repositoryId and m.revokedAt is null
            order by m.username
            """)
    List<RepositoryMembership> findByRepositoryIdOrderByUsernameAsc(@Param("repositoryId") String repositoryId);

    /** Serialize membership changes with catalogue updates, including the first grant in an empty repository. */
    @Modifying
    @Query("""
            update SystemRepository r set r.version = r.version
            where r.repositoryId = :repositoryId and r.version = :expectedVersion
            """)
    int lockCatalogueRevision(@Param("repositoryId") String repositoryId,
                              @Param("expectedVersion") long expectedVersion);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT membership
            FROM RepositoryMembership membership
            WHERE membership.repositoryId = :repositoryId
            ORDER BY membership.username ASC
            """)
    List<RepositoryMembership> findByRepositoryIdForUpdate(
            @Param("repositoryId") String repositoryId);

    @Query("""
            select count(m) from RepositoryMembership m
            where m.repositoryId = :repositoryId and m.role = :role and m.revokedAt is null
            """)
    long countByRepositoryIdAndRole(@Param("repositoryId") String repositoryId, @Param("role") RepositoryRole role);
}
