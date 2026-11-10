package com.taxonomy.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** JUnit owns source bootstrap regressions against the production browser controllers and transport. */
class SourceBootstrapBrowserContractTest {
    @TempDir Path temporary;

    @Test
    void aProjectSubmittedBeforeLifecycleHydrationIsHandledAndSelected() throws Exception {
        run("portfolio-early-submit");
    }

    @Test
    void aProjectSubmittedAfterLifecycleHydrationIsHandledAndSelected() throws Exception {
        run("portfolio-ready-submit");
    }

    @Test
    void aProjectSubmittedWhileTheInitialSelectionIsPendingWaitsForThatSelection() throws Exception {
        run("portfolio-selection-pending-submit");
    }

    @Test
    void startupStaysBusyThroughTranslationsAndInitialSelectionWithoutLosingFormValues() throws Exception {
        run("portfolio-startup-busy");
    }

    @Test
    void leavingThePageDuringProjectPreparationDoesNotCreateAProject() throws Exception {
        run("portfolio-page-exit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"own-publication", "branch", "history", "pagehide", "authentication", "foreign-publication",
            "pending", "reconciliation", "failed-confirmation", "superseded-confirmation"})
    void requirementPublicationRetainsOnlyItsCurrentFormAndSource(String transition) throws Exception {
        run("portfolio-requirement-" + transition);
    }

    @Test
    void aSnapshotWaitsForDraftAndContextHydrationBeforeItsProtectedRead() throws Exception {
        run("snapshot-startup");
    }

    @Test
    void aSnapshotWaitsForTheInjectedLoaderBeforeEitherSessionGlobalExists() throws Exception {
        run("snapshot-delayed-loader");
    }

    @Test
    void aFailedInjectedLoaderFinishesWithoutStartingSnapshotMetadata() throws Exception {
        run("snapshot-loader-error");
    }

    @Test
    void leavingDuringTheInjectedLoaderDoesNotStartSnapshotMetadata() throws Exception {
        run("snapshot-loader-page-exit");
    }

    @Test
    void anExistingSessionWithUnavailableDraftStillAllowsSavedSnapshotEvidence() throws Exception {
        run("snapshot-draft-unavailable");
    }

    @Test
    void aStandaloneSnapshotDoesNotRequireAnAbsentSessionLoader() throws Exception {
        run("snapshot-standalone");
    }

    @Test
    void aSubsequentSourceChangeStillDiscardsThePendingSnapshot() throws Exception {
        run("snapshot-source-change");
    }

    @Test
    void anInitializedSnapshotStillRendersItsSavedEvidence() throws Exception {
        run("snapshot-ready");
    }

    @Test
    void leavingThePageDuringSnapshotPreparationDoesNotStartItsRead() throws Exception {
        run("snapshot-page-exit");
    }

    private void run(String scenario) throws Exception {
        Path script = temporary.resolve("source-bootstrap.cjs");
        try (var input = getClass().getResourceAsStream("/ui/source-bootstrap-contract.cjs")) {
            assertThat(input).as("source bootstrap contract driver").isNotNull();
            Files.copy(input, script);
        }
        Path resources = Path.of("..").toAbsolutePath().normalize()
                .resolve("taxonomy-app/src/main/resources");
        Path log = temporary.resolve(scenario + ".log");
        Process process = new ProcessBuilder(List.of(System.getProperty("taxonomy.node.executable"),
                script.toString(), scenario, resources.toString()))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS))
                    .as("source bootstrap browser contract completed").isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
            assertThat(Files.readString(log)).contains("SOURCE_BOOTSTRAP_COMPLETE " + scenario);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
