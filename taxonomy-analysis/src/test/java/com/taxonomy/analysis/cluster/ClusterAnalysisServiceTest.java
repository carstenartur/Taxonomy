package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dispatch.JpaAnalysisTaskCompletionStore;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ClusterAnalysisServiceTest {
    @Test void independentRunsOverlapAndDuplicateDeliveryKeepsOneResult() throws Exception {
        try (var db = new ClusterAnalysisStoreTest.Database(); var threads = Executors.newFixedThreadPool(3)) {
            var context = ClusterAnalysisStoreTest.context("overlap"); db.admit(context);
            var other = ClusterAnalysisStoreTest.context("overlap-other"); db.admit(other);
            var entered = new CountDownLatch(2); var release = new CountDownLatch(1); var calls = new AtomicInteger();
            ClusterAnalysisComputation scoring = (input, task, cancelled) -> {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                calls.incrementAndGet(); entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                return ClusterAnalysisStoreTest.result(task.root().code(), 40);
            };
            var first = new ClusterAnalysisService(db.store, scoring, new ClusterAnalysisSignals());
            var second = new ClusterAnalysisService(db.store, scoring, new ClusterAnalysisSignals());
            var cp = db.task(context, TaxonomyShardRoot.of("CP")); var secondCp = db.task(other, TaxonomyShardRoot.of("CP"));
            var cpFuture = threads.submit(() -> db.completions.commit(first.prepare(cp)));
            var otherFuture = threads.submit(() -> db.completions.commit(second.prepare(secondCp)));
            boolean overlapping = entered.await(5, TimeUnit.SECONDS);
            release.countDown();
            if (!overlapping) { cpFuture.get(5, TimeUnit.SECONDS); otherFuture.get(5, TimeUnit.SECONDS); }
            assertTrue(overlapping, "Both workers must enter before either may finish");
            db.store.accept(otherFuture.get(5, TimeUnit.SECONDS)); db.store.accept(cpFuture.get(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            for (var operation : List.of(context, other)) db.store.accept(db.complete(
                    db.task(operation, TaxonomyShardRoot.of("IP")), ClusterAnalysisStoreTest.result("IP", 40)));
            assertEquals(ClusterAnalysisState.COMPLETED, db.store.snapshot(context).state());
            assertEquals(ClusterAnalysisState.COMPLETED, db.store.snapshot(other).state());
            assertTrue(db.completions.find(cp).isPresent());
        }
    }

    @Test void queuedCancellationAvoidsProviderAndUncaughtComputationFailureIsExplicit() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var context = ClusterAnalysisStoreTest.context("queued-cancel"); db.admit(context);
            var calls = new AtomicInteger();
            var service = new ClusterAnalysisService(db.store, (input, task, stop) -> {
                calls.incrementAndGet(); throw new IllegalStateException("provider failure must not leak details");
            }, new ClusterAnalysisSignals());
            var cp = db.task(TaxonomyShardRoot.of("CP"));
            var prepared = service.prepare(cp);
            db.completions.commit(prepared); db.store.accept(prepared.completion());
            assertEquals(AnalysisTaskOutcome.FAILED, prepared.completion().outcome());
            db.store.cancel(context);
            var ipPrepared = service.prepare(db.task(TaxonomyShardRoot.of("IP")));
            assertEquals(AnalysisTaskOutcome.STOPPED, ipPrepared.completion().outcome());
            assertEquals(1, calls.get());
        }
    }
}
