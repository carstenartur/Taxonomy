package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

@Tag("ci-app-shard-recovery")
class ReformulationUsageWorkerTest {
    @TempDir Path directory;
    @Test void actualWorkerCommitsAdmissionBeforeEachHttpRequest() throws Exception {
        ReformulationUsageWorkerHarness.complete(directory.resolve("complete"));
    }
    @Test void processDeathLeavesAnUnknownOutcomeWhileRecoveryReusesTheSavedQuestion() throws Exception {
        ReformulationUsageWorkerHarness.killAndRecover(directory.resolve("restart"));
    }
}
