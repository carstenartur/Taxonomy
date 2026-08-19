package com.taxonomy.analysis.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Persistence boundary for exactly one mutable draft per tenant scope and user. */
public interface AnalysisWorkingDraftRepository
        extends JpaRepository<AnalysisWorkingDraft, Long> {

    Optional<AnalysisWorkingDraft> findByScopeKeyAndUsername(
            String scopeKey, String username);
}
