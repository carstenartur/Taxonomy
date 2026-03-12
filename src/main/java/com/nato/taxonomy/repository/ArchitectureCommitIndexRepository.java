package com.nato.taxonomy.repository;

import com.nato.taxonomy.model.ArchitectureCommitIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for architecture commit index entries.
 *
 * <p>Simple JPA lookups only — full-text search is handled by
 * {@link com.nato.taxonomy.service.CommitIndexService} via Hibernate Search.
 */
@Repository
public interface ArchitectureCommitIndexRepository extends JpaRepository<ArchitectureCommitIndex, Long> {

    Optional<ArchitectureCommitIndex> findByCommitId(String commitId);

    List<ArchitectureCommitIndex> findByBranchOrderByCommitTimestampDesc(String branch);

    boolean existsByCommitId(String commitId);
}
