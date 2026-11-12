package com.taxonomy.analysis.cluster;

import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;

/** Retires operation-owned evidence under its original owner and scope. Portfolio snapshots are independently owned. */
public final class ClusterAnalysisRetention {
    private static final long HISTORY = Duration.ofDays(30).toMillis();
    private static final long TOMBSTONE = Duration.ofDays(90).toMillis();
    private static final int BATCH = 64;
    private static final List<String> CHILDREN = List.of("ClusterAnalysisAttempt", "ClusterAnalysisCall",
            "ClusterAnalysisEvent", "ClusterAnalysisWork", "ClusterAnalysisInput", "AnalysisTaskCompletionRecord", "AnalysisDispatchIntent");
    private static final String DUE = "((r.totalRoots=0 and r.state=CANCELLED and r.updatedAt<=:tombstone)"
            + " or (r.totalRoots>0 and r.updatedAt<=:history))";
    private static final String OWNED = "r.id=:id and r.username=:owner and r.scopeKey=:scope and r.version=:version";

    private final EntityManager em;
    private final TransactionTemplate transactions;

    ClusterAnalysisRetention(EntityManager em, TransactionTemplate transactions) {
        this.em = em;
        this.transactions = transactions;
    }

    static ResponseStatusException expired() {
        var failure = new ResponseStatusException(HttpStatus.GONE, "Analysis operation expired");
        failure.getBody().setProperty("code", "ANALYSIS_EXPIRED");
        return failure;
    }

    @Scheduled(fixedDelayString = "${taxonomy.analysis.retention.sweep-delay:PT1M}",
            initialDelayString = "${taxonomy.analysis.retention.sweep-delay:PT1M}")
    public void sweep() {
        sweep(null, System.currentTimeMillis());
    }

    public int sweepScope(WorkspaceContext scope, long now) {
        return sweep(java.util.Objects.requireNonNull(scope), now);
    }

    private int sweep(WorkspaceContext scope, long now) {
        var candidates = transactions.execute(status -> {
            var query = em.createQuery("select r.id,r.username,r.scopeKey,r.version,r.totalRoots from ClusterAnalysisRun r where "
                    + DUE + (scope == null ? "" : " and r.username=:owner and r.scopeKey=:scope")
                    + " order by r.updatedAt,r.id", Object[].class)
                    .setParameter("tombstone", now - TOMBSTONE).setParameter("history", now - HISTORY).setMaxResults(BATCH);
            if (scope != null) query.setParameter("owner", scope.username()).setParameter("scope", ClusterAnalysisStore.scopeKey(scope));
            return query.getResultList();
        });
        int changed = 0;
        for (var row : candidates) {
            // An intervening progress update, cleanup or admission changes the version.
            // The first write fences late workers before any children are removed.
            boolean expired = ((Integer) row[4]) == 0;
            boolean applied = transactions.execute(status -> {
                var sql = expired ? "delete from ClusterAnalysisRun r where "
                        : "update ClusterAnalysisRun r set r.state=CANCELLED, r.commandJson='{}', r.viewJson=null,"
                        + " r.resultJson=null,r.relationPlanJson=null,r.completedRoots=0,r.totalRoots=0,"
                        + " r.updatedAt=:now,r.revision=r.revision+1,r.version=r.version+1 where ";
                var write = em.createQuery(sql + OWNED)
                        .setParameter("id", row[0]).setParameter("owner", row[1]).setParameter("scope", row[2]).setParameter("version", row[3]);
                if (!expired) write.setParameter("now", now);
                if (write.executeUpdate() != 1) return false;
                for (String entity : CHILDREN) {
                    em.createQuery("delete from " + entity + " child where child.operationId=:id")
                            .setParameter("id", row[0]).executeUpdate();
                }
                return true;
            });
            if (applied) changed++;
        }
        return changed;
    }
}
