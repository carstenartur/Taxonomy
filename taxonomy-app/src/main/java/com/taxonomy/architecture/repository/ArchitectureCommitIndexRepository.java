package com.taxonomy.architecture.repository;

import com.taxonomy.architecture.model.ArchitectureCommitIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for tenant-scoped architecture commit-index projections.
 *
 * <p>Full-text search is handled by Hibernate Search in the service layer, but
 * every relational lookup carries the exact repository/workspace scope. No
 * commit or branch is globally unique across logical repositories.</p>
 */
@Repository
public interface ArchitectureCommitIndexRepository
        extends JpaRepository<ArchitectureCommitIndex, Long> {

    Optional<ArchitectureCommitIndex>
            findByRepositoryIdAndWorkspaceScopeKeyAndBranchAndCommitId(
                    String repositoryId,
                    String workspaceScopeKey,
                    String branch,
                    String commitId);

    List<ArchitectureCommitIndex>
            findByRepositoryIdAndWorkspaceScopeKeyAndBranchOrderByCommitTimestampDesc(
                    String repositoryId,
                    String workspaceScopeKey,
                    String branch);

    List<ArchitectureCommitIndex>
            findByRepositoryIdAndWorkspaceScopeKey(
                    String repositoryId,
                    String workspaceScopeKey);

    boolean existsByRepositoryIdAndWorkspaceScopeKeyAndBranchAndCommitId(
            String repositoryId,
            String workspaceScopeKey,
            String branch,
            String commitId);

    long countByRepositoryIdAndWorkspaceScopeKey(
            String repositoryId,
            String workspaceScopeKey);
}
