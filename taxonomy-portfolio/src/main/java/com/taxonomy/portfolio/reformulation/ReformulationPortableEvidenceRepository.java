package com.taxonomy.portfolio.reformulation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReformulationPortableEvidenceRepository
        extends JpaRepository<ReformulationPortableEvidence, String> {

    Optional<ReformulationPortableEvidence> findByScopeKeyAndEvidenceHash(
            String scopeKey, String evidenceHash);

    List<ReformulationPortableEvidence>
            findByScopeKeyOrderByProjectKeyAscRequirementKeyAscTargetVersionNumberAscEvidenceHashAsc(
                    String scopeKey);
}
