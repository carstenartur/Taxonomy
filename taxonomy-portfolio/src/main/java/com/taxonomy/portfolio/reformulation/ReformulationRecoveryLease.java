package com.taxonomy.portfolio.reformulation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Execution ownership only. The existing run and proposal remain the result authorities. */
@Entity
@Table(name = "reformulation_recovery_lease", indexes = @Index(name = "idx_reform_recovery_due", columnList = "active,lease_until"))
public class ReformulationRecoveryLease {
    @Id @Column(name = "run_id", length = 36) private String runId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", insertable = false, updatable = false, foreignKey = @jakarta.persistence.ForeignKey(name = "fk_reform_recovery_run"))
    private ReformulationRun run;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "dispatch_payload", nullable = false, updatable = false) private String dispatchPayload;
    @Column(name = "owner_id", length = 36) private String ownerId;
    @Column(name = "lease_epoch", nullable = false) private long epoch;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(nullable = false) private boolean active = true;
    @Version @Column(name = "row_version", nullable = false) private long version;

    protected ReformulationRecoveryLease() {}
    ReformulationRecoveryLease(String runId, String payload) { this.runId = runId; this.dispatchPayload = payload; }
    String payload() { return dispatchPayload; }
    boolean active() { return active; }
    long epoch() { return epoch; }
    boolean available(Instant now) { return active && (leaseUntil == null || !leaseUntil.isAfter(now)); }
    boolean owns(String owner, long generation, Instant now) {
        return active && owner.equals(ownerId) && generation == epoch && leaseUntil != null && leaseUntil.isAfter(now);
    }
    void claim(String owner, Instant until) { ownerId = owner; epoch++; leaseUntil = until; }
    void renew(Instant until) { leaseUntil = until; }
    void retire() { active = false; }
}
