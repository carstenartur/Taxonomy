package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;

class AnalysisTimingTest {
    @Test void resultAndTerminalStreamsRetainMeasuredDuration() throws Exception {
        AnalysisTimingChecks.resultTiming();
    }
    @Test void terminalObservationDoesNotAdvanceDuration() throws Exception {
        AnalysisTimingChecks.frozenRunTiming();
    }
}
