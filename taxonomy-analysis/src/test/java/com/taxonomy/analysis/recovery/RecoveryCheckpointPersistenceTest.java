package com.taxonomy.analysis.recovery;

import org.junit.jupiter.api.Test;

class RecoveryCheckpointPersistenceTest {
    @Test void questionWritesRequireTheCurrentUncancelledClaim() {
        RecoveryCheckpointPersistenceProbe.checkpointClaimIsStillEnforced();
    }
    @Test void bookkeepingAndQuestionStayInOneTransaction() {
        RecoveryCheckpointPersistenceProbe.bookkeepingFailureRollsBackQuestion();
    }
    @Test void questionBookkeepingNeverHydratesTheSavedResult() {
        RecoveryCheckpointPersistenceProbe.checkpointDoesNotLoadSavedResult();
    }
    @Test void frequentClaimChecksNeverHydrateTheSavedResult() {
        RecoveryCheckpointPersistenceProbe.stateDoesNotLoadSavedResult();
    }
    @Test void questionCheckpointsDoNotRewriteTheFrozenSnapshot() {
        RecoveryCheckpointPersistenceProbe.checkpointDoesNotRewriteSavedResult();
    }
    @Test void cancellationIsFreshAndBoundToTheExecutionClaim() {
        RecoveryCheckpointPersistenceProbe.stateIsFreshAndClaimBound();
    }
}
