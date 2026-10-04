package com.taxonomy;

import org.junit.jupiter.api.Test;

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
    @Test void ownerSourceAndRequirementScopeIsAppliedBeforeTheHistoryLimit() {
        try (var contract = ClusterAnalysisDatabaseContract.hsql()) {
            contract.ownerSourceAndRequirementScopeIsAppliedBeforeTheHistoryLimit();
        }
    }
}
