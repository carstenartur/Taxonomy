package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class JunitReportVerifierTest {
    @TempDir Path root;
    @Test void acceptsExecutedSuites() throws Exception { JunitReportVerificationChecks.acceptsExecutedSuites(root); }
    @Test void rejectsMissingOrEmptyEvidence() throws Exception { JunitReportVerificationChecks.rejectsMissingOrEmptyEvidence(root); }
    @Test void rejectsFailedSkippedAndUnderExecutedSuites() throws Exception { JunitReportVerificationChecks.rejectsFailedSkippedAndUnderExecutedSuites(root); }
    @Test void rejectsMalformedOrInconsistentCounters() throws Exception { JunitReportVerificationChecks.rejectsMalformedOrInconsistentCounters(root); }
    @Test void rejectsDoctypeAndChecksAllSuites() throws Exception { JunitReportVerificationChecks.rejectsDoctypeAndChecksAllSuites(root); }
}
