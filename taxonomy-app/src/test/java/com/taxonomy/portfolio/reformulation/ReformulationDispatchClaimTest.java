package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ReformulationDispatchClaimTest {
    @Test
    @Timeout(120)
    void repeatedDispatchCannotFailTheWorkerThatAlreadyOwnsTheRun() throws Exception {
        // Same JVM in the Maven test suite so normal coverage instrumentation applies.
        ReformulationDispatchClaimDriver.main(new String[0]);
    }
}
