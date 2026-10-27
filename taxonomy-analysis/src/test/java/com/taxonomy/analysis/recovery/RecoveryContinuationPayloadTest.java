package com.taxonomy.analysis.recovery;

import org.junit.jupiter.api.Test;

class RecoveryContinuationPayloadTest {
    @Test void originalInputReadsItsFullRequestAndScopeWithoutOtherSavedPayloads() {
        RecoveryCheckpointPersistenceProbe.originalRequestUsesOnlyItsPayloadAndScope();
    }
    @Test void pausedWorkingViewKeepsItsExactFrozenSource() {
        RecoveryCheckpointPersistenceProbe.pausedWorkingViewKeepsItsExactFrozenSource();
    }
    @Test void ordinaryContinuationChecksFailureStatesWithoutLoadingAnswers() {
        RecoveryCheckpointPersistenceProbe.continueUsesQuestionStates();
    }
    @Test void localCompletionReadsQuestionMetadataAndKeepsOpenWork() {
        RecoveryCheckpointPersistenceProbe.localCompletionUsesQuestionMetadata();
    }
    @Test void uncertainCompletionPreservesRawEvidenceWithoutRewritingPayloads() {
        RecoveryCheckpointPersistenceProbe.completionMarksUncertainWithoutPayloadReads();
    }
    @Test void clusterCompletionReadsOnlyTheAcceptedCategoryDetailsItNeeds() {
        RecoveryCheckpointPersistenceProbe.clusterCompletionReadsRequiredDetailsOnly();
    }
    @Test void supersessionChecksFullQuestionIdentityWithoutLoadingOrRewritingResponses() {
        RecoveryCheckpointPersistenceProbe.supersessionUsesQuestionInputsOnly();
    }
    @Test void compatibleDistributedSupersessionAlsoUsesOnlyQuestionInputs() {
        RecoveryCheckpointPersistenceProbe.compatibleSupersessionUsesQuestionInputsOnly();
    }
    @Test void failedSupersessionBookkeepingRollsBackItsQuestionState() {
        RecoveryCheckpointPersistenceProbe.supersessionBookkeepingRemainsAtomic();
    }
    @Test void completionUsesTheStoredCatalogueWithoutEvaluatingTheFallback() {
        RecoveryCheckpointPersistenceProbe.completionUsesStoredCatalogue();
    }
    @Test void legacyCompletionEvaluatesItsCatalogueFallbackOnlyOnce() {
        RecoveryCheckpointPersistenceProbe.completionUsesMissingCatalogueFallback();
    }
    @Test void aSupersededClaimCannotEvaluateTheCatalogueFallback() {
        RecoveryCheckpointPersistenceProbe.completionRejectsClaimBeforeCatalogueFallback();
    }
}
