package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.reformulation.ReformulationRecoveryService.Claim;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Set;

/** Durable attempt evidence. No prompt storage, estimated billing or authority to publish proposals. */
@Service
public class ReformulationUsageService {
    public record Start(String id, String invocationId, String provider, String source, int retryIndex) {
        public Start {
            identifier(id); identifier(invocationId);
            if (provider == null || !provider.matches("[A-Z_0-9]{1,32}") || source == null || !Set.of("HTTP", "RECORDING_REPLAY").contains(source)
                    || retryIndex < 0 || ("RECORDING_REPLAY".equals(source) && retryIndex != 0))
                throw new IllegalArgumentException("Invalid usage start");
        }
    }
    public record Completion(Integer statusCode, String outcome, long durationMillis, Long inputTokens,
                             Long outputTokens, Long totalTokens, Long cachedInputTokens, Long reasoningTokens, boolean invalidUsage) {
        public Completion {
            if (outcome == null || !Set.of("RESPONSE", "HTTP_ERROR", "TRANSPORT_ERROR").contains(outcome) || durationMillis < 0
                    || (statusCode != null && (statusCode < 100 || statusCode > 599))) throw new IllegalArgumentException("Invalid usage result");
            for (Long value : new Long[]{inputTokens, outputTokens, totalTokens, cachedInputTokens, reasoningTokens})
                if (value != null && value < 0) throw new IllegalArgumentException("Invalid reported token count");
            if ("HTTP_ERROR".equals(outcome) && (statusCode == null || statusCode < 400)) throw new IllegalArgumentException("Missing HTTP error status");
            if ("TRANSPORT_ERROR".equals(outcome) && statusCode != null) throw new IllegalArgumentException("Transport error has HTTP status");
            if ("RESPONSE".equals(outcome) && statusCode != null && statusCode >= 400) throw new IllegalArgumentException("Error status labelled response");
        }
    }
    /** Strings preserve sums beyond JavaScript's exact integer range. Null means no reported values. */
    public record TokenSum(String reported, long reports, long unknown) {}
    public record Summary(String runId, boolean recorded, boolean fromFirstAttempt, Instant recordedSince, long httpAttempts, long replays, long pendingAttempts,
                          long retries, long httpErrors, long transportErrors, long invalidUsage,
                          TokenSum inputTokens, TokenSum outputTokens, TokenSum totalTokens,
                          TokenSum cachedInputTokens, TokenSum reasoningTokens) {}
    private final ReformulationRecoveryService recovery;
    private final ProjectPortfolioService projects;
    @PersistenceContext private EntityManager em;
    public ReformulationUsageService(ReformulationRecoveryService recovery, ProjectPortfolioService projects) {
        this.recovery = recovery; this.projects = projects;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void activate(Claim token) {
        recovery.checkActive(token);
        var d = token.dispatch();
        var session = em.find(ReformulationUsageSession.class, d.run().id());
        String scope = PortfolioScope.key(d.actor(), d.context());
        if (session == null) {
            boolean fromFirst = token.epoch() == 1 && em.createQuery(
                    "select count(c) from ReformulationNodeCheckpoint c where c.runId=:run", Long.class)
                    .setParameter("run", d.run().id()).getSingleResult() == 0;
            em.persist(new ReformulationUsageSession(d.run().id(), d.proposalId(), scope, recovery.databaseTime(d.run().id()), fromFirst));
        }
        else if (!session.matches(d.proposalId(), scope)) throw PortfolioException.notFound("Usage run not found");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void start(Claim token, Start start) {
        recovery.checkActive(token); // Hold the same proposal -> run -> lease locks through admission commit.
        var d = token.dispatch();
        session(token);
        if (start == null || !d.run().provider().equals(start.provider())) throw PortfolioException.validation("Usage provider does not match captured run");
        var previous = em.find(ReformulationUsageAttempt.class, start.id());
        if (previous != null) {
            if (!previous.ownedBy(d.run().id(), token.owner(), token.epoch()) || !previous.matches(start))
                throw PortfolioException.conflict("Usage start identity conflict");
            return;
        }
        em.persist(new ReformulationUsageAttempt(d.run().id(), token.owner(), token.epoch(), start, recovery.databaseTime(d.run().id())));
        em.flush();
    }

    /** A late result may finish ONLY its own committed start, irrespective of current lease ownership. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Claim token, String attemptId, Completion result) {
        identifier(attemptId);
        var d = token.dispatch();
        projects.requireRequirement(d.projectId(), d.requirementId(), d.actor(), d.context());
        session(token);
        var attempt = em.find(ReformulationUsageAttempt.class, attemptId, LockModeType.PESSIMISTIC_WRITE);
        if (attempt == null || !attempt.ownedBy(d.run().id(), token.owner(), token.epoch())) throw PortfolioException.notFound("Usage attempt not found");
        if (result == null) throw PortfolioException.validation("Usage result is required");
        if ("RECORDING_REPLAY".equals(attempt.source()) && (!"RESPONSE".equals(result.outcome()) || result.statusCode() != null
                || result.durationMillis() != 0 || result.invalidUsage() || result.inputTokens() != null || result.outputTokens() != null
                || result.totalTokens() != null || result.cachedInputTokens() != null || result.reasoningTokens() != null))
            throw PortfolioException.validation("Replay is not new transport usage");
        var previous = attempt.result();
        if (previous != null) {
            if (!previous.equals(result)) throw PortfolioException.conflict("Usage completion is immutable");
            return;
        }
        attempt.complete(result, recovery.databaseTime(d.run().id()));
    }

    @Transactional(readOnly = true)
    public Summary summary(Long project, Long requirement, String proposal, String run, String actor, WorkspaceContext context) {
        projects.requireRequirement(project, requirement, actor, context);
        String scope = PortfolioScope.key(actor, context);
        var runFound = em.createQuery("select r.id from ReformulationRun r, ReformulationProposal p where r.id=:run and r.proposalId=p.id and r.scopeKey=:scope and p.scopeKey=:scope and p.id=:proposal and p.projectId=:project and p.requirementId=:requirement", String.class)
                .setParameter("run", run).setParameter("scope", scope).setParameter("proposal", proposal)
                .setParameter("project", project).setParameter("requirement", requirement).getResultStream().findFirst();
        if (runFound.isEmpty()) throw PortfolioException.notFound("Usage run not found");
        var recording = em.find(ReformulationUsageSession.class, run);
        boolean recorded = recording != null;
        var sums = new Sum[]{new Sum(), new Sum(), new Sum(), new Sum(), new Sum()};
        long http = 0, replay = 0, pending = 0, retries = 0, httpErrors = 0, transportErrors = 0, invalid = 0;
        // Stream scalar metadata; never read baseline, proposal text, prompts or checkpoint result LOBs.
        try (var rows = em.createQuery("select a.source, a.retryIndex, a.completedAt, a.outcome, a.inputTokens, a.outputTokens, a.totalTokens, a.cachedInputTokens, a.reasoningTokens, a.invalidUsage from ReformulationUsageAttempt a where a.runId=:run", Object[].class)
                .setParameter("run", run).getResultStream()) {
            var iterator = rows.iterator();
            while (iterator.hasNext()) {
                Object[] row = iterator.next();
                if (row[2] == null) pending++;
                if ("RECORDING_REPLAY".equals(row[0])) { replay++; continue; }
                http++; if (((Integer)row[1]) > 0) retries++;
                if ("HTTP_ERROR".equals(row[3])) httpErrors++;
                if ("TRANSPORT_ERROR".equals(row[3])) transportErrors++;
                if (Boolean.TRUE.equals(row[9])) invalid++;
                for (int i = 0; i < sums.length; i++) sums[i].add((Long)row[i + 4]);
            }
        }
        return new Summary(run, recorded, recorded && recording.fromFirstAttempt(), recorded ? recording.recordedSince() : null, http, replay, pending, retries, httpErrors, transportErrors, invalid,
                sums[0].value(), sums[1].value(), sums[2].value(), sums[3].value(), sums[4].value());
    }
    private void session(Claim token) {
        var d = token.dispatch(); var session = em.find(ReformulationUsageSession.class, d.run().id());
        if (session == null || !session.matches(d.proposalId(), PortfolioScope.key(d.actor(), d.context())))
            throw PortfolioException.notFound("Usage recording is not active for this run");
    }
    private static void identifier(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException("Invalid usage identifier");
    }
    private static final class Sum {
        BigInteger total = BigInteger.ZERO; long known, unknown;
        void add(Long value) { if (value == null) unknown++; else { total = total.add(BigInteger.valueOf(value)); known++; } }
        TokenSum value() { return new TokenSum(known == 0 ? null : total.toString(), known, unknown); }
    }
}
