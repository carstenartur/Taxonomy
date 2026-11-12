package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;

import static com.taxonomy.analysis.cluster.ClusterAnalysisStoreTest.command;
import static com.taxonomy.analysis.cluster.ClusterAnalysisStoreTest.context;
import static org.junit.jupiter.api.Assertions.*;

class ClusterAnalysisRetentionTest {
    @Test void archivedExpiryKeepsOriginalLifetimeAndRestoresOnlyTheScopedMarker() throws Exception {
        try (var source = new ClusterAnalysisStoreTest.Database(); var target = new ClusterAnalysisStoreTest.Database()) {
            var operation = context("stale-archived");
            source.admit(operation);
            source.store.cancel(operation);
            var owner = command("requirement").workspaceContext();
            long captured = System.currentTimeMillis() + Duration.ofDays(31).toMillis();
            assertEquals(1, source.store.retention().sweepScope(owner, captured));
            var archive = ClusterAnalysisBackupRestoreTest.exportedArchive(source);
            assertEquals("EXPIRED", archive.run().sourceState());
            assertNull(archive.run().commandJson());
            assertTrue(archive.work().isEmpty()); assertTrue(archive.inputs().isEmpty()); assertTrue(archive.events().isEmpty());
            var targetContext = context("restored-expiry");
            var restorer = new ClusterAnalysisBackupRestorer(target.em, target.transactions, new tools.jackson.databind.ObjectMapper());
            restorer.restoreExpired(archive, targetContext, owner, java.util.Map.of());
            assertEquals(captured, target.em.find(ClusterAnalysisRun.class, targetContext.operationId()).updatedAt);
            assertThrows(ResponseStatusException.class, () -> target.admit(targetContext));
            assertTrue(target.sent.isEmpty());
            assertThrows(IllegalStateException.class, () -> restorer.restoreExpired(archive, targetContext, owner, java.util.Map.of()));
        }
    }

    @Test void scopedExpiryRemovesPayloadsAndFencesDuplicateAdmissionAndLateWorkers() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var owner = command("requirement").workspaceContext();
            var foreign = new WorkspaceContext("bob", "workspace", "draft", "repo");
            var original = context("expired-original");
            // HSQL restore V1 and existing vendor installations can retain the original enum constraint.
            new TransactionTemplate(db.transactions).executeWithoutResult(status -> db.em.createNativeQuery(
                    "alter table analysis_cluster_run add constraint legacy_cluster_states check (state in "
                            + "('QUEUED','RUNNING','RELATIONS','FINALIZING','COMPLETED','PARTIAL','CANCELLED'))").executeUpdate());
            db.admit(original);
            var late = db.task(TaxonomyShardRoot.of("CP"));
            db.store.cancel(original);
            var live = context("recent-active");
            db.admit(live);
            long now = System.currentTimeMillis();
            var tx = new TransactionTemplate(db.transactions);
            tx.executeWithoutResult(status -> db.em.createQuery(
                    "update ClusterAnalysisRun r set r.updatedAt=:at where r.id=:id")
                    .setParameter("id", original.operationId())
                    .setParameter("at", now - Duration.ofDays(31).toMillis()).executeUpdate());
            assertEquals(0, db.store.retention().sweepScope(foreign, now));
            assertNotNull(db.store.snapshot(original).result());
            assertEquals(1, db.store.retention().sweepScope(owner, now));
            assertEquals(0, db.store.retention().sweepScope(owner, now));
            tx.executeWithoutResult(status -> {
                var marker = db.em.find(ClusterAnalysisRun.class, original.operationId());
                assertTrue(marker.expired());
                assertEquals(ClusterAnalysisState.CANCELLED, marker.state);
                assertEquals("{}", marker.commandJson);
                assertNull(marker.resultJson); assertNull(marker.relationPlanJson); assertNull(marker.viewJson);
                for (String entity : List.of("ClusterAnalysisWork", "ClusterAnalysisInput", "ClusterAnalysisEvent",
                        "ClusterAnalysisCall", "ClusterAnalysisAttempt", "AnalysisDispatchIntent", "AnalysisTaskCompletionRecord")) {
                    assertEquals(0L, db.em.createQuery("select count(e) from " + entity + " e where e.operationId=:id", Long.class)
                            .setParameter("id", original.operationId()).getSingleResult(), entity);
                }
            });
            var gone = assertThrows(ResponseStatusException.class,
                    () -> db.store.findAuthorized(original.operationId(), "alice", owner));
            assertEquals(HttpStatus.GONE, gone.getStatusCode());
            assertEquals("ANALYSIS_EXPIRED", gone.getBody().getProperties().get("code"));
            assertThrows(SecurityException.class, () -> db.store.findAuthorized(original.operationId(), "bob", foreign));
            assertThrows(ResponseStatusException.class, () -> db.admit(original));
            assertFalse(db.store.start(late).executable());
            assertEquals(List.of(live), db.store.recent("alice", owner, null, null));
            assertEquals(1, db.store.recentSnapshots("alice", owner, null, null).size());
            assertTrue(db.store.findAuthorized(live.operationId(), "alice", owner).isPresent());
            long horizon = now + Duration.ofDays(91).toMillis();
            db.store.retention().sweepScope(owner, horizon);
            assertTrue(db.store.findAuthorized(original.operationId(), "alice", owner).isEmpty());
        }
    }
}
