package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.model.PortfolioTenantIdentity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Immutable validated step output. Never stores incomplete or failed provider responses. */
@Entity
@Table(name = "reformulation_node_checkpoint", indexes = @Index(name = "idx_reform_checkpoint_proposal", columnList = "proposal_id,scope_key"))
public class ReformulationNodeCheckpoint {
    @Id @Column(length = 64, updatable = false) private String id;
    @Column(name = "proposal_id", nullable = false, updatable = false, length = 36) private String proposalId;
    @Column(name = "scope_key", nullable = false, updatable = false, length = PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH) private String scopeKey;
    @Column(name = "run_id", nullable = false, updatable = false, length = 36) private String runId;
    @Column(name = "task_kind", nullable = false, updatable = false, length = 16) private String taskKind;
    @Column(name = "input_fingerprint", nullable = false, updatable = false, length = 64) private String inputFingerprint;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "result_payload", nullable = false, updatable = false) private String resultPayload;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({@JoinColumn(name = "proposal_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "scope_key", referencedColumnName = "scope_key", insertable = false, updatable = false)})
    private ReformulationProposal proposal;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", insertable = false, updatable = false)
    private ReformulationRun run;

    protected ReformulationNodeCheckpoint() {}
    public ReformulationNodeCheckpoint(String id, String proposalId, String scopeKey, String runId,
            String taskKind, String inputFingerprint, String resultPayload, Instant createdAt) {
        this.id = id; this.proposalId = proposalId; this.scopeKey = scopeKey; this.runId = runId;
        this.taskKind = taskKind; this.inputFingerprint = inputFingerprint; this.resultPayload = resultPayload; this.createdAt = createdAt;
    }
    public String getResultPayload() { return resultPayload; }
}
