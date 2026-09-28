package com.taxonomy.acceptance;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.UUID;

class ReformulationAuthoredScenarioTest {
    @ParameterizedTest @ValueSource(strings = {"time-recording", "cross-taxonomy"})
    void actualAnalysisPreservesNegativeNumericalAndUnmappedSource(String scenario) throws Exception {
        Path output = Files.createDirectories(Path.of("target/reformulation-scenario-acceptance", scenario + "-" + UUID.randomUUID()));
        ReformulationScenarioAcceptanceTest.launch(output, "authored-" + scenario);
    }
}
