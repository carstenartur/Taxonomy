package com.taxonomy.portfolio.reformulation;

import jakarta.persistence.*;
import java.time.Instant;
import com.taxonomy.portfolio.reformulation.ReformulationUsageService.Start;
import com.taxonomy.portfolio.reformulation.ReformulationUsageService.Completion;

/** A committed intent to exchange, followed by at most one immutable terminal observation. */
@Entity
@Table(name = "reformulation_usage_attempt", indexes = @Index(name = "idx_reform_usage_attempt_run", columnList = "run_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_reform_usage_invocation", columnNames = {"run_id", "invocation_id", "source_kind", "retry_index"}))
public class ReformulationUsageAttempt {
    @Id @Column(length = 36, updatable = false) private String id;
    @Column(name = "run_id", nullable = false, length = 36, updatable = false) private String runId;
    @Column(name = "owner_id", nullable = false, length = 36, updatable = false) private String ownerId;
    @Column(name = "lease_epoch", nullable = false, updatable = false) private long epoch;
    @Column(name = "invocation_id", nullable = false, length = 36, updatable = false) private String invocationId;
    @Column(nullable = false, length = 32, updatable = false) private String provider;
    @Column(name = "source_kind", nullable = false, length = 24, updatable = false) private String source;
    @Column(name = "retry_index", nullable = false, updatable = false) private int retryIndex;
    @Column(name = "started_at", nullable = false, updatable = false) private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "status_code") private Integer statusCode;
    @Column(length = 24) private String outcome;
    @Column(name = "duration_millis") private Long durationMillis;
    @Column(name = "input_tokens") private Long inputTokens;
    @Column(name = "output_tokens") private Long outputTokens;
    @Column(name = "total_tokens") private Long totalTokens;
    @Column(name = "cached_input_tokens") private Long cachedInputTokens;
    @Column(name = "reasoning_tokens") private Long reasoningTokens;
    @Column(name = "invalid_usage") private Boolean invalidUsage;
    @Version @Column(name = "row_version", nullable = false) private long version;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_reform_usage_attempt_session"))
    private ReformulationUsageSession session;
    protected ReformulationUsageAttempt() {}
    ReformulationUsageAttempt(String runId, String owner, long epoch, Start start, Instant now) {
        this.id = start.id(); this.runId = runId; this.ownerId = owner; this.epoch = epoch;
        this.invocationId = start.invocationId(); this.provider = start.provider(); this.source = start.source();
        this.retryIndex = start.retryIndex(); this.startedAt = now;
    }
    boolean ownedBy(String run, String owner, long generation) { return runId.equals(run) && ownerId.equals(owner) && epoch == generation; }
    boolean matches(Start start) { return id.equals(start.id()) && invocationId.equals(start.invocationId()) && provider.equals(start.provider()) && source.equals(start.source()) && retryIndex == start.retryIndex(); }
    String source() { return source; }
    Completion result() { return completedAt == null ? null : new Completion(statusCode, outcome, durationMillis,
            inputTokens, outputTokens, totalTokens, cachedInputTokens, reasoningTokens, invalidUsage); }
    void complete(Completion result, Instant now) {
        completedAt = now; statusCode = result.statusCode(); outcome = result.outcome(); durationMillis = result.durationMillis();
        inputTokens = result.inputTokens(); outputTokens = result.outputTokens(); totalTokens = result.totalTokens();
        cachedInputTokens = result.cachedInputTokens(); reasoningTokens = result.reasoningTokens(); invalidUsage = result.invalidUsage();
    }
}
