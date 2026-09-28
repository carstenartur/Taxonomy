package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioAcceptanceNamingTest {
    @Test void maintainedSurfacesUseNeutralNames() throws Exception {
        ScenarioAcceptanceNamingContract.verifyNames(root());
    }

    @Test void renamedAcceptanceStillSelectsAllTestsAndBothDocumentChecks() throws Exception {
        ScenarioAcceptanceNamingContract.verifyWiring(root());
    }

    @Test void rejectsEnglishAndGermanAudienceLabelsInMaintainedText() {
        for (String value : new String[]{"Civilian scenario", "CIVILIAN_TEST", "zivile Anwendung", "Ziviler Test"}) {
            assertTrue(ScenarioAcceptanceNamingContract.outdated("docs/en/USER_GUIDE.md", value), value);
        }
    }

    @Test void preservesOnlyTheExactOriginalCatalogueBinding() {
        String fixture = "taxonomy-app/src/test/resources/scenarios/flood-information.json";
        assertFalse(ScenarioAcceptanceNamingContract.outdated(fixture, "\"BR-1228\": \"Civilian Roles\""));
        assertTrue(ScenarioAcceptanceNamingContract.outdated(fixture, "\"title\": \"Civilian Roles\""));
        assertTrue(ScenarioAcceptanceNamingContract.outdated("docs/en/USER_GUIDE.md", "Civilian Roles"));
    }

    @Test void historicalLinkTargetsDoNotPermitNonNeutralCaptions() {
        assertFalse(ScenarioAcceptanceNamingContract.outdated("docs/testing/example.md", "[Historical evidence](../qa/civilian-acceptance-evidence.json)"));
        assertTrue(ScenarioAcceptanceNamingContract.outdated("docs/testing/example.md", "[Civilian workflow](../qa/civilian-acceptance-evidence.json)"));
    }

    private static Path root() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".mvn/verification-suites.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Taxonomy checkout not found");
        return root;
    }
}
