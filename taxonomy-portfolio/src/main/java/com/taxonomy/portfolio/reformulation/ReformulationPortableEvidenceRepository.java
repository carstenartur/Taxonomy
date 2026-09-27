package com.taxonomy.portfolio.reformulation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReformulationPortableEvidenceRepository
        extends JpaRepository<ReformulationPortableEvidence, String> {

    Optional<ReformulationPortableEvidence> findByScopeKeyAndEvidenceHash(
            String scopeKey, String evidenceHash);

    @Query("""
            select evidence from ReformulationPortableEvidence evidence
            where evidence.scopeKey = :scopeKey
              and lower(evidence.projectKey) = lower(:projectKey)
              and lower(evidence.requirementKey) = lower(:requirementKey)
              and evidence.targetVersionNumber = :targetVersionNumber
              and evidence.targetTextHash = :targetTextHash
            """)
    List<ReformulationPortableEvidence> findMatchingCurrent(
            @Param("scopeKey") String scopeKey, @Param("projectKey") String projectKey,
            @Param("requirementKey") String requirementKey, @Param("targetVersionNumber") int targetVersionNumber,
            @Param("targetTextHash") String targetTextHash);

    List<ReformulationPortableEvidence>
            findByScopeKeyOrderByProjectKeyAscRequirementKeyAscTargetVersionNumberAscEvidenceHashAsc(
                    String scopeKey);
}
