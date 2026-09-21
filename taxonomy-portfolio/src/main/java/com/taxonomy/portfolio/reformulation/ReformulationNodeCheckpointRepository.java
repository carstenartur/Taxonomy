package com.taxonomy.portfolio.reformulation;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ReformulationNodeCheckpointRepository extends JpaRepository<ReformulationNodeCheckpoint, String> {
    Optional<ReformulationNodeCheckpoint> findByIdAndProposalIdAndScopeKey(String id, String proposalId, String scopeKey);
}
