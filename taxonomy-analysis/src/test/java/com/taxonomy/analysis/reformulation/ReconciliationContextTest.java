package com.taxonomy.analysis.reformulation;

import org.junit.jupiter.api.Test;

class ReconciliationContextTest {
    @Test void dictionarySelectionRespectsUnicodeAndUtf8Budgets() { ReconciliationContextChecks.unicode(); }
    @Test void exactRepeatedContextsStayInOneRequest() { ReconciliationContextChecks.duplicates(); }
    @Test void shortAndUniqueContextsStayCompleteAndInline() { ReconciliationContextChecks.unique(); }
    @Test void dictionaryOverheadDoesNotGrowSmallInputs() { ReconciliationContextChecks.smallDuplicate(); }
    @Test void mergedOriginsAndUserAnswersRemainExact() { ReconciliationContextChecks.origins(); }
    @Test void repairUsesTheSameInputAndDictionary() { ReconciliationContextChecks.repair(); }
    @Test void fullInputFitsRealGatewayWithoutTruncation() throws Exception { ReconciliationContextChecks.gateway(false); }
    @Test void actualParserRepairPreservesTheSameInput() throws Exception { ReconciliationContextChecks.gateway(true); }
    @Test void irreducibleInputMakesNoRemoteRequest() throws Exception { ReconciliationContextChecks.budget(); }
    @Test void realPhaseBUsesAllQuestionsAndDirectedEdges() throws Exception { ReconciliationContextChecks.phaseB(); }
}
