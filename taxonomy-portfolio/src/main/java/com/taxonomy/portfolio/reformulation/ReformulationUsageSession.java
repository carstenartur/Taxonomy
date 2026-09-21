package com.taxonomy.portfolio.reformulation;

import jakarta.persistence.*;
import java.time.Instant;

/** Distinguishes measured zero from runs for which recording was never enabled. */
@Entity
@Table(name = "reformulation_usage_session")
public class ReformulationUsageSession {
    @Id @Column(name = "run_id", length = 36, updatable = false) private String runId;
    @Column(name = "proposal_id", nullable = false, length = 36, updatable = false) private String proposalId;
    @Column(name = "scope_key", nullable = false, updatable = false, length = com.taxonomy.portfolio.model.PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH) private String scopeKey;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "from_first_attempt", nullable = false, updatable = false) private boolean fromFirstAttempt;
    @Column(name = "schema_version", nullable = false, updatable = false) private int schemaVersion = 1;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_reform_usage_run"))
    private ReformulationRun run;
    protected ReformulationUsageSession() {}
    ReformulationUsageSession(String runId, String proposalId, String scopeKey, Instant createdAt, boolean fromFirstAttempt) {
        this.runId = runId; this.proposalId = proposalId; this.scopeKey = scopeKey; this.createdAt = createdAt; this.fromFirstAttempt = fromFirstAttempt;
    }
    boolean fromFirstAttempt() { return fromFirstAttempt; }
    Instant recordedSince() { return createdAt; }
    boolean matches(String proposal, String scope) { return proposalId.equals(proposal) && scopeKey.equals(scope); }
}
