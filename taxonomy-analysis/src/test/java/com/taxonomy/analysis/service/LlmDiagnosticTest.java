package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;

/** Normal suite entry points for the executable production-boundary checks. */
class LlmDiagnosticTest {
    @Test void failedReplyRemainsInspectable() throws Exception {
        LlmDiagnosticChecks.failedReplyRemainsInspectable();
    }
    @Test void malformedJsonKeepsVisibleFailureAndOriginalEvidence() {
        LlmDiagnosticChecks.malformedJsonKeepsVisibleFailureAndOriginalEvidence();
    }
    @Test void diagnosticLengthsAndLimits() throws Exception {
        LlmDiagnosticChecks.diagnosticLengthsAndLimits();
    }
    @Test void transportFailureIsNotAnEmptySuccess() throws Exception {
        LlmDiagnosticChecks.transportFailureIsNotAnEmptySuccess();
    }
    @Test void nonJsonIsActionableAndNotSuccess() throws Exception {
        LlmDiagnosticChecks.nonJsonIsActionableAndNotSuccess();
    }
    @Test void quotedBracesAndFencesRemainIntact() throws Exception {
        LlmDiagnosticChecks.quotedBracesAndFencesRemainIntact();
    }
    @Test void malformedOuterObjectIsNotSalvaged() throws Exception {
        LlmDiagnosticChecks.malformedOuterObjectIsNotSalvaged();
    }
}
