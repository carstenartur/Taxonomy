package com.taxonomy.analysis.reformulation;

import org.junit.jupiter.api.Test;

/** Executes the real gateway, prompt budget and parser against authored loopback HTTP responses. */
class BoundedNodeSynthesisTest {
    @Test void oversizedParentsPreserveDetailsAndQuestionsAndSmallInputsRemainSingleCalls() throws Exception {
        BoundedNodeSynthesisDriver.main(new String[0]);
    }
    @Test void terminalGroupsKeepFullStoredDiscoveryContext() throws Exception {
        BoundedNodeSynthesisDriver.terminals();
    }
    @Test void crossGroupPrerequisiteContractsArePresent() throws Exception {
        BoundedNodeSynthesisDriver.dependencies();
    }
    @Test void crossGroupStatementDecisionsRemainAvailable() throws Exception {
        BoundedNodeSynthesisDriver.statementDependency();
    }
    @Test void transactionViolationsAreRejectedBeforeBudgeting() throws Exception {
        BoundedNodeSynthesisDriver.transaction();
    }
    @Test void excessiveGroupCountsAreRejectedBeforeAnyRemoteRequest() throws Exception {
        BoundedNodeSynthesisDriver.bounds();
    }
}
