package com.nato.taxonomy.repository;

import com.nato.taxonomy.model.ArchitectureDslDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ArchitectureDslDocumentRepository extends JpaRepository<ArchitectureDslDocument, Long> {

    Optional<ArchitectureDslDocument> findByPath(String path);

    List<ArchitectureDslDocument> findByBranch(String branch);

    List<ArchitectureDslDocument> findByNamespace(String namespace);

    /** History of documents on a branch, newest first. */
    List<ArchitectureDslDocument> findByBranchOrderByParsedAtDesc(String branch);

    /** Most recent document on a branch. */
    Optional<ArchitectureDslDocument> findFirstByBranchOrderByParsedAtDesc(String branch);

    /** All distinct branch names that have documents. */
    @Query("SELECT DISTINCT d.branch FROM ArchitectureDslDocument d WHERE d.branch IS NOT NULL")
    List<String> findDistinctBranches();
}
