package com.taxonomy.composition.reformulation;

import org.junit.jupiter.api.Test;

class LocalExecutionTest {
    @Test void lateClaimIsNotPublished() throws Exception { LocalExecutionChecks.lateClaimIsNotPublished(); }
    @Test void duplicateDeliveryOwnsNoCleanup() throws Exception { LocalExecutionChecks.duplicateDeliveryOwnsNoCleanup(); }
    @Test void retirementBeforeStartOwnsNoCleanup() throws Exception { LocalExecutionChecks.retirementBeforeStartOwnsNoCleanup(); }
    @Test void retirementDoesNotWaitForWork() throws Exception { LocalExecutionChecks.retirementDoesNotWaitForWork(); }
    @Test void retiredWorkerCannotFinalize() throws Exception { LocalExecutionChecks.retiredWorkerCannotFinalize(); }
    @Test void admittedFinalizationIsNotInterrupted() throws Exception { LocalExecutionChecks.admittedFinalizationIsNotInterrupted(); }
    @Test void detachedRunnerIsNotInterruptedDuringCleanup() throws Exception { LocalExecutionChecks.detachedRunnerIsNotInterruptedDuringCleanup(); }
    @Test void failureStillDetachesAndCleansUp() throws Exception { LocalExecutionChecks.failureStillDetachesAndCleansUp(); }
}
