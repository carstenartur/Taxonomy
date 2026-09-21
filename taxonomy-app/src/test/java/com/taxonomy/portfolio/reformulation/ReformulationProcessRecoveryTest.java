package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class ReformulationProcessRecoveryTest {
    @TempDir Path directory;
    @Test void resumesAutomaticallyAfterProcessDeathWithoutRepeatingCommittedChild() throws Exception {
        ReformulationRecoveryHarness.recoverAfterKill(directory);
    }
    @Test void expiredAndCancelledOwnersCannotWriteOrOverwriteHumanEdits() throws Exception {
        ReformulationRecoveryHarness.runToCompletion(directory,"boundaries","REFORMULATION_LEASE_FENCING_OK");
    }
}
