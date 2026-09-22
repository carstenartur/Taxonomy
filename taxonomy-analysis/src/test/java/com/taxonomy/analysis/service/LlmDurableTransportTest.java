package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;

class LlmDurableTransportTest {
    @Test void startPrecedesTransport() throws Exception { LlmDurableTransportChecks.beginCommitsBeforeTransportAndCompletesExactlyOnce(); }
    @Test void failureBoundaries() throws Exception { LlmDurableTransportChecks.failedBeginNeverSendsAndFailedCompletionNeverRetries(); }
    @Test void scopesAndReplay() throws Exception { LlmDurableTransportChecks.journalCaptureAndReplayDoNotLeak(); }
}
