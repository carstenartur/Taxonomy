package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.UUID;

/** Same real lifecycle with desktop/390px interaction and explicit browser adoption. */
class ReformulationScenarioBrowserTest {
    @Test void realAnalysedOfferSupportsKeyboardDraftRetentionDownloadAndExplicitAdoption() throws Exception {
        Path output = Files.createDirectories(Path.of("target/reformulation-scenario-acceptance", "browser-" + UUID.randomUUID()));
        ReformulationScenarioAcceptanceTest.launch(output, "browser");
        ReformulationScenarioAcceptanceTest.launch(output, "read");
    }
}
