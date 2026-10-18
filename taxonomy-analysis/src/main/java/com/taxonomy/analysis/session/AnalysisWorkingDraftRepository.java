package com.taxonomy.analysis.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.time.Instant;

/** Persistence boundary for exactly one mutable draft per tenant scope and user. */
public interface AnalysisWorkingDraftRepository
        extends JpaRepository<AnalysisWorkingDraft, Long> {

    Optional<AnalysisWorkingDraft> findByScopeKeyAndUsername(
            String scopeKey, String username);

    Optional<Revision> findProjectedByScopeKeyAndUsername(String scopeKey, String username);

    /** Closed projection keeps the large payload out of periodic observations. */
    interface Revision {
        long getRowVersion();
        Instant getUpdatedAt();
    }
}
