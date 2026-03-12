package com.taxonomy.repository;

import com.taxonomy.model.RequirementCoverage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RequirementCoverageRepository extends JpaRepository<RequirementCoverage, Long> {

    List<RequirementCoverage> findByNodeCode(String nodeCode);

    List<RequirementCoverage> findByRequirementId(String requirementId);

    /** Number of distinct node codes that have at least one coverage entry at or above minScore. */
    @Query("SELECT COUNT(DISTINCT c.nodeCode) FROM RequirementCoverage c WHERE c.score >= :minScore")
    long countDistinctNodeCodeByScoreGreaterThanEqual(int minScore);

    /** Number of distinct requirement IDs stored. */
    @Query("SELECT COUNT(DISTINCT c.requirementId) FROM RequirementCoverage c")
    long countDistinctRequirementIds();

    /** Returns pairs of (nodeCode, count) grouped by nodeCode. */
    @Query("SELECT c.nodeCode, COUNT(c) FROM RequirementCoverage c GROUP BY c.nodeCode")
    List<Object[]> findNodeCodeCountPairs();

    void deleteByRequirementId(String requirementId);
}
