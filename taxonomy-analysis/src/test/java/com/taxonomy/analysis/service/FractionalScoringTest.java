package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;

class FractionalScoringTest {
    @Test void equalWeightsRetainFractionsAtEveryBudgetScale() { FractionalScoringChecks.equalWeightsRetainFractionalPrecision(); }
    @Test void rootAndRecoveredProductRepliesKeepDecimals() throws Exception { FractionalScoringChecks.decimalRootAndRecoveredProductResponsesAreAccepted(); }
    @Test void provisionalRelationsDoNotFailWhenFormattingDecimalScores() { FractionalScoringChecks.provisionalReasoningAcceptsDecimalScores(); }
    @Test void parserAcceptsHalfAPercent() throws Exception { FractionalScoringChecks.parserAcceptsHalfAPercent(); }
    @Test void normalizationPreservesBothPositiveChildren() { FractionalScoringChecks.normalizationPreservesBothPositiveChildren(); }
    @Test void effectiveProductRelevanceKeepsSmallContribution() { FractionalScoringChecks.effectiveProductRelevanceKeepsSmallContribution(); }
    @Test void customizedPromptsCarryTheDecimalContract() { FractionalScoringChecks.customizedPromptsCarryTheDecimalContract(); }
    @Test void exportDeclaresTheDecimalCapableVersion() { FractionalScoringChecks.exportUsesAVersionThatOldIntegerReadersMustReject(); }
    @Test void hierarchyAndJsonRoundTripPreserveFractions() throws Exception { FractionalScoringChecks.hierarchyAndSerializationRetainFractions(); }
    @Test void invalidNumbersDoNotBecomeNegativeEvidence() throws Exception { FractionalScoringChecks.numericFailuresDoNotBecomeZeroEvidence(); }
}
