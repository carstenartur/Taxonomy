package com.taxonomy.analysis.assessment;

import org.junit.jupiter.api.Test;

class ChildAssessmentContractTest {
    @Test
    void validatesCompleteChildBatchesWithoutImposingScoreSemantics() {
        ChildAssessmentContractChecks.run();
    }
}
