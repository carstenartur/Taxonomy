package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Persistence access for explicit repository-scoped user roles. */
public interface RepositoryMembershipRepository
        extends JpaRepository<RepositoryMembership, Long> {

    Optional<RepositoryMembership> findByRepositoryIdAndUsername(
            String repositoryId, String username);

    List<RepositoryMembership> findByRepositoryIdOrderByUsernameAsc(String repositoryId);

    long countByRepositoryIdAndRole(String repositoryId, RepositoryRole role);
}
