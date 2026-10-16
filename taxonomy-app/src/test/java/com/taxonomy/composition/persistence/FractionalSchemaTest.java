package com.taxonomy.composition.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class FractionalSchemaTest {
    @TempDir Path directory;
    @Test void upgradesIntegerScoresWithoutLosingFractionsOnRestart() throws Exception {
        FractionalSchemaChecks.upgradesHistoricalIntegerColumnsAndSurvivesRestart(directory);
    }
    @Test void stagedRestoreValidatesTheCompleteModelAndReloadsFractionalScores() throws Exception {
        FractionalRestoreChecks.migratedRestoreSchemaValidatesAndPersistsDecimals();
    }
}
