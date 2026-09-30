package com.taxonomy.analysis.recovery;

import org.junit.jupiter.api.Test;

class RecoveryDurabilityTest {
    @Test void skippedRelationDoesNotBlockIndependentPendingWork() { RecoveryDurabilityProbe.skippedRelationBudget(); }
    @Test void admissionFreezesCatalogueBeforeAnyProviderCall() { RecoveryDurabilityProbe.frozenAdmission(); }
    @Test void legacyRowsRequireMatchingContextAndRetainEvidence() { RecoveryDurabilityProbe.legacyCancellation(); }
    @Test void cancellationPersistsCompletedEvidenceBeforeReturning() { RecoveryDurabilityProbe.cancelledEvidence(); }
    @Test void lateWorkerCannotChangeCancelledEvidence() { RecoveryDurabilityProbe.lateCompletion(); }
    @Test void invalidResumableProviderUsesTypedValidationError() { RecoveryDurabilityProbe.invalidProvider(); }
    @Test void progressFollowsDurableCommit() throws Exception { RecoveryDurabilityProbe.publication("PARTIAL", false); }
    @Test void cancellationResponseIsNotMutatedAfterCommit() throws Exception { RecoveryDurabilityProbe.publication("CANCELLED", false); }
    @Test void failedPersistenceNeverPublishesSuccess() throws Exception { RecoveryDurabilityProbe.publication("PARTIAL", true); }
}
