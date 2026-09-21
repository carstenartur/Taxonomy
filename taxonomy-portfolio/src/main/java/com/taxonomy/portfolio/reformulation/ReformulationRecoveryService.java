package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.reformulation.ReformulationDtos.Proposal;
import com.taxonomy.portfolio.reformulation.ReformulationDtos.Run;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.reformulation.ReformulationDocument;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Short transactions fence every result by database-clock lease and monotonically increasing epoch. */
@Service
public class ReformulationRecoveryService {
    public record Dispatch(Long projectId, Long requirementId, String proposalId, String actor,
                           WorkspaceContext context, Run run, String endpointHash) {}
    public record Claim(Dispatch dispatch, String owner, long epoch) {}
    private final ReformulationService proposals;
    private final ReformulationProposalRepository proposalRows;
    private final ReformulationRunRepository runRows;
    private final PortfolioJsonCodec json;
    private final Duration leaseDuration;
    private final int maxAttempts;
    @PersistenceContext private EntityManager em;

    public ReformulationRecoveryService(ReformulationService proposals, ReformulationProposalRepository proposalRows,
            ReformulationRunRepository runRows, PortfolioJsonCodec json,
            @Value("${reformulation.recovery.lease-ms:120000}") long leaseMillis,
            @Value("${reformulation.recovery.max-attempts:3}") int maxAttempts) {
        if (leaseMillis < 1000 || maxAttempts < 1 || maxAttempts > 100)
            throw new IllegalArgumentException("Invalid reformulation recovery limits");
        this.proposals = proposals; this.proposalRows = proposalRows; this.runRows = runRows; this.json = json;
        this.leaseDuration = Duration.ofMillis(leaseMillis); this.maxAttempts = maxAttempts;
    }

    /** No crash window between recording the user run and making it recoverable. */
    @Transactional
    public Dispatch enqueue(Long projectId, Long requirementId, String proposalId, long revision,
            String provider, String model, String promptVersion, String schemaVersion, String prompt,
            Map<String,String> reconciliation, String endpointHash, String actor, WorkspaceContext context) {
        var run = proposals.beginRun(projectId, requirementId, proposalId, revision, provider, model,
                promptVersion, schemaVersion, prompt, reconciliation, actor, context);
        var dispatch = new Dispatch(projectId, requirementId, proposalId, run.actor(), context, run, endpointHash);
        em.persist(new ReformulationRecoveryLease(run.id(), json.write(dispatch)));
        return dispatch;
    }

    /** Internal scheduler only; no global job list is exposed to the HTTP layer. */
    @Transactional(readOnly = true)
    public List<Dispatch> due(int limit) { return dueAfter(limit, ""); }

    @Transactional(readOnly = true)
    public List<Dispatch> dueAfter(int limit, String afterId) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid recovery page size");
        return em.createQuery("""
                select l.dispatchPayload from ReformulationRecoveryLease l
                where l.active = true and l.runId > :afterId and (l.leaseUntil is null or l.leaseUntil <= current_timestamp)
                order by l.runId
                """, String.class).setParameter("afterId", afterId).setMaxResults(limit).getResultList().stream()
                .map(value -> json.read(value, Dispatch.class)).toList();
    }

    @Transactional
    public Optional<Claim> claim(Dispatch d, String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 36) throw new IllegalArgumentException("Invalid lease owner");
        var locked = lock(d); var row = locked.lease(); var run = locked.run();
        if (!row.active()) return Optional.empty();
        if (!active(run)) { row.retire(); return Optional.empty(); }
        Instant now = databaseTime(d.run().id());
        if (!row.available(now)) return Optional.empty();
        if (row.epoch() >= maxAttempts) {
            proposals.finishRun(d.projectId(), d.requirementId(), d.proposalId(), run.id(), null,
                    "RECOVERY_ATTEMPTS_EXHAUSTED", d.actor(), d.context());
            row.retire(); return Optional.empty();
        }
        if (run.status().equals("QUEUED"))
            proposals.running(d.projectId(), d.requirementId(), d.proposalId(), run.id(), d.actor(), d.context());
        row.claim(owner, now.plus(leaseDuration));
        return Optional.of(new Claim(d, owner, row.epoch()));
    }

    /** Reads the exact requested revision, not a newer manual edit encountered after restart. */
    @Transactional(readOnly = true)
    public Proposal source(Dispatch d) {
        var p = proposals.get(d.projectId(), d.requirementId(), d.proposalId(), d.actor(), d.context());
        return new Proposal(p.id(), p.baseline(), p.creator(), p.createdAt(), p.status(),
                proposals.revision(d.projectId(), d.requirementId(), d.proposalId(), d.run().sourceRevision(), d.actor(), d.context()));
    }

    @Transactional
    public boolean heartbeat(Claim token) {
        var locked = lock(token.dispatch());
        if (!valid(locked, token)) return false;
        locked.lease().renew(databaseTime(token.dispatch().run().id()).plus(leaseDuration));
        return true;
    }
    @Transactional
    public Optional<String> checkpoint(Claim token, String kind, String fingerprint) {
        require(token); var d = token.dispatch();
        return proposals.checkpoint(d.projectId(), d.requirementId(), d.proposalId(), d.run().id(), kind, fingerprint, d.actor(), d.context());
    }
    @Transactional
    public String completeCheckpoint(Claim token, String kind, String fingerprint, String payload) {
        require(token); var d = token.dispatch();
        return proposals.completeCheckpoint(d.projectId(), d.requirementId(), d.proposalId(), d.run().id(), kind, fingerprint, payload, d.actor(), d.context());
    }
    @Transactional
    public boolean finish(Claim token, ReformulationDocument document, String failure) {
        var locked = lock(token.dispatch());
        if (!valid(locked, token)) return false;
        var d = token.dispatch();
        proposals.finishRun(d.projectId(), d.requirementId(), d.proposalId(), d.run().id(), document, failure, d.actor(), d.context());
        locked.lease().retire(); return true;
    }

    private void require(Claim token) {
        if (!valid(lock(token.dispatch()), token)) throw PortfolioException.conflict("REFORMULATION_LEASE_LOST");
    }
    private boolean valid(Locked locked, Claim token) {
        return active(locked.run()) && locked.lease().owns(token.owner(), token.epoch(), databaseTime(token.dispatch().run().id()));
    }
    private static boolean active(Run run) { return run.status().equals("RUNNING") || run.status().equals("QUEUED"); }
    private record Locked(ReformulationRecoveryLease lease, Run run) {}
    private Locked lock(Dispatch d) {
        // Existing authorization and lock order: proposal, then run, then execution ownership.
        String scope = PortfolioScope.key(d.actor(), d.context());
        proposalRows.lockScoped(d.proposalId(), d.projectId(), d.requirementId(), scope)
                .orElseThrow(() -> PortfolioException.notFound("Reformulation proposal not found"));
        proposals.get(d.projectId(), d.requirementId(), d.proposalId(), d.actor(), d.context());
        var run = runRows.lockScoped(d.run().id(), d.proposalId(), scope)
                .orElseThrow(() -> PortfolioException.notFound("Synthesis run not found"));
        var lease = em.find(ReformulationRecoveryLease.class, d.run().id(), LockModeType.PESSIMISTIC_WRITE);
        if (lease == null || !json.read(lease.payload(), Dispatch.class).equals(d))
            throw PortfolioException.conflict("REFORMULATION_DISPATCH_CHANGED");
        return new Locked(lease, json.read(run.getPayload(), Run.class));
    }
    private Instant databaseTime(String runId) {
        return em.unwrap(org.hibernate.Session.class).doReturningWork(connection -> {
            String product = connection.getMetaData().getDatabaseProductName();
            String sql = switch (product) {
                case "PostgreSQL" -> "select clock_timestamp()";
                case "Microsoft SQL Server" -> "select SYSDATETIMEOFFSET()";
                case "Oracle" -> "select SYSTIMESTAMP from dual";
                case "HSQL Database Engine" -> "values (CURRENT_TIMESTAMP)";
                default -> throw new java.sql.SQLException("Unsupported recovery clock database: " + product);
            };
            try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
                if (!result.next()) throw new java.sql.SQLException("Recovery database clock missing");
                return result.getTimestamp(1, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))).toInstant();
            }
        });
    }
}
