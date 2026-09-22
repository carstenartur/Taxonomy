package com.taxonomy.analysis.reformulation;

import org.junit.jupiter.api.Test;

class ParallelReformulationTest {
    @Test void failedChildCannotHoldTheCallerIndefinitely() throws Exception {
        WalkUpShutdownChecks.failedChildDoesNotWaitForeverForSibling();
    }
    @Test void interruptedCallerDoesNotJoinUncooperativeChildrenForever() throws Exception {
        WalkUpShutdownChecks.interruptedCallerDoesNotWaitForeverForChildren();
    }
    @Test void independentSubtreesOverlapWithoutChangingTheSerialDocument() throws Exception {
        ParallelReformulationChecks.independentSubtreesOverlapAndPreserveTheSerialDocument();
    }
    @Test void readyWorkIsBoundedAndParentsJoinTheirChildren() throws Exception {
        WalkUpExecutionChecks.boundsReadyWorkAndJoinsParents();
    }
    @Test void failureDrainsSiblingsBeforeReturningAndNeverStartsDescendants() throws Exception {
        WalkUpExecutionChecks.failureDrainsSiblingsAndDoesNotStartDescendants();
    }
    @Test void malformedPlansAndLimitsStartNoWork() {
        WalkUpExecutionChecks.malformedPlansAndLimitsFailBeforeWork();
    }
    @Test void capturedProviderDoesNotLeakAfterFailure() throws Exception {
        WalkUpExecutionChecks.providerOverrideIsCapturedAndClearedOnFailure();
    }
    @Test void parallelDispatchCannotEscapeAnOpenCallerTransaction() {
        WalkUpExecutionChecks.parallelismCannotBypassTheCallerTransactionGuard();
    }
}
