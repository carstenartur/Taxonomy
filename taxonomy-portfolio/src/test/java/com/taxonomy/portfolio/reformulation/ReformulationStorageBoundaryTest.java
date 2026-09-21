package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;

class ReformulationStorageBoundaryTest {
    @Test void malformedCheckpointIdentitiesAreRejectedBeforePersistence() {
        ReformulationStorageBoundaryChecks.invalidIdentity();
    }
    @Test void emptyAndOversizedUtf8ResultsAreRejectedBeforePersistence() {
        ReformulationStorageBoundaryChecks.invalidResult();
    }
    @Test void repeatedCancellationPreservesTheFirstActorAndInstant() throws Exception {
        ReformulationStorageBoundaryChecks.firstCancellationSurvivesRepeatedAndDifferentActors();
    }
}
