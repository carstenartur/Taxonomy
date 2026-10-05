package com.taxonomy;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the exact external-database contract locally; this is not vendor acceptance. */
class ClusterAnalysisDatabaseContractTest {
    @Test void largePayloadsAndAllFourTablesSurviveANewPersistenceFactory() {
        try (var contract = ClusterAnalysisDatabaseContract.hsql()) {
            contract.largePayloadsAndAllFourTablesSurviveANewPersistenceFactory();
        }
    }
    @Test void concurrentDuplicateCompletionRollsBackAndSettlesExactlyOnce() throws Exception {
        try (var contract = ClusterAnalysisDatabaseContract.hsql()) {
            contract.concurrentDuplicateCompletionRollsBackAndSettlesExactlyOnce();
        }
    }
    @Test void bothDeliveriesReachTheJdbcInsertBarrierBeforeEitherExecutesItsInsert() throws Exception {
        var caller = Thread.currentThread();
        var inserts = new CopyOnWriteArrayList<ClusterAnalysisDatabaseContract.CompletionInsert>();
        try (var contract = ClusterAnalysisDatabaseContract.hsql()) {
            contract.observeCompletionInserts(event -> {
                // Initial rollback and the independent IP completion run on the calling thread.
                if (Thread.currentThread() != caller) inserts.add(event);
            });
            contract.concurrentDuplicateCompletionRollsBackAndSettlesExactlyOnce();
        }
        assertEquals(4, inserts.size(), "Both real JDBC inserts must reach the callback and execute boundary");
        assertFalse(inserts.get(0).executingSql());
        assertFalse(inserts.get(1).executingSql(),
                "Both delivery transactions must pass their absent-row reads before either inserts its reservation");
        assertTrue(inserts.get(2).executingSql());
        assertTrue(inserts.get(3).executingSql());
        assertNotSame(inserts.get(0).connection(), inserts.get(1).connection(),
                "The duplicate race must use two independent JDBC connections");
        assertEquals(Set.of(inserts.get(0).connection(), inserts.get(1).connection()),
                Set.of(inserts.get(2).connection(), inserts.get(3).connection()));
    }
    @Test void ownerSourceAndRequirementScopeIsAppliedBeforeTheHistoryLimit() {
        try (var contract = ClusterAnalysisDatabaseContract.hsql()) {
            contract.ownerSourceAndRequirementScopeIsAppliedBeforeTheHistoryLimit();
        }
    }
}
