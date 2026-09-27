package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.UUID;

/** Same real lifecycle with desktop/390px interaction and explicit browser adoption. */
class ReformulationCivilianBrowserTest {
    @Test void realAnalysedOfferSupportsKeyboardDraftRetentionDownloadAndExplicitAdoption() throws Exception {
        Path output = Files.createDirectories(Path.of("target/reformulation-civilian-acceptance", "browser-" + UUID.randomUUID()));
        ReformulationCivilianAcceptanceTest.launch(output, "browser");
        ReformulationCivilianAcceptanceTest.launch(output, "read");
    }
}
