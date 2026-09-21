package com.taxonomy.analysis.reformulation;

import org.junit.jupiter.api.Test;

class GroupedEvidenceFidelityTest {
    @Test void aggregateReceivesCompleteGroupProposals() {
        GroupedEvidenceFidelityChecks.aggregateRetainsFormulatedStatements();
    }
    @Test void uniqueLongContextIsNeverDiscarded() {
        GroupedEvidenceFidelityChecks.uniqueDiscoveryContextRemainsAvailable();
    }
    @Test void repeatedContextsUseOnlyInPromptLosslessReferences() {
        GroupedEvidenceFidelityChecks.repeatedContextsAreResolvedInsideThisPrompt();
    }
    @Test void mergedOriginsAndRepairKeepExactContext() {
        GroupedEvidenceFidelityChecks.mergedQuestionOriginsAndRepairKeepExactContext();
    }
    @Test void actualGatewayReceivesGroupWording() throws Exception {
        GroupedEvidenceFidelityChecks.realGatewayReceivesGroupFormulations();
    }
    @Test void unrepresentableAggregateRetainsItsPartsAndFailsExplicitly() throws Exception {
        GroupedEvidenceFidelityChecks.irreducibleContextFailsWithoutTruncation();
    }
}
