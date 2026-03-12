package com.nato.taxonomy.repository;

import com.nato.taxonomy.model.ArchitectureCommitIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for searchable architecture commit index entries.
 */
@Repository
public interface ArchitectureCommitIndexRepository extends JpaRepository<ArchitectureCommitIndex, Long> {

    Optional<ArchitectureCommitIndex> findByCommitId(String commitId);

    List<ArchitectureCommitIndex> findByBranchOrderByCommitTimestampDesc(String branch);

    /** Find commits that affected a specific element ID. */
    @Query("SELECT c FROM ArchitectureCommitIndex c WHERE c.affectedElementIds LIKE %:elementId% ORDER BY c.commitTimestamp DESC")
    List<ArchitectureCommitIndex> findByAffectedElement(String elementId);

    /** Find commits that affected a specific relation (by composite key fragment). */
    @Query("SELECT c FROM ArchitectureCommitIndex c WHERE c.affectedRelationIds LIKE %:relationKey% ORDER BY c.commitTimestamp DESC")
    List<ArchitectureCommitIndex> findByAffectedRelation(String relationKey);

    /** Full-text search across tokenized change text. */
    @Query("SELECT c FROM ArchitectureCommitIndex c WHERE LOWER(c.tokenizedChangeText) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY c.commitTimestamp DESC")
    List<ArchitectureCommitIndex> searchByTokenizedText(String query);

    /** Search by commit message. */
    @Query("SELECT c FROM ArchitectureCommitIndex c WHERE LOWER(c.message) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY c.commitTimestamp DESC")
    List<ArchitectureCommitIndex> searchByMessage(String query);

    boolean existsByCommitId(String commitId);
}
