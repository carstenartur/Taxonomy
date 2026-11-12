package com.taxonomy.portfolio;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JUnit-owned contracts for the production portfolio polling adapter and API. */
@Tag("ci-app-shard-recovery")
class PortfolioJobLifecycleTest {

    @TempDir
    Path directory;

    @Test
    void registeredJobWithoutDtoProjectIdPollsItsValidatedResource() throws Exception {
        run("registeredJobUsesValidatedProjectIdentity");
    }

    @Test
    void restoredJobWithoutDtoProjectIdPollsOnlyItsValidatedResource() throws Exception {
        run("restoredJobUsesValidatedProjectIdentity");
    }

    @Test
    void leavingPortfolioCancelsScheduledPollWithoutDeletingServerJobHistory() throws Exception {
        run("departureCancelsScheduledPoll");
    }

    @Test
    void leavingPortfolioAbortsInFlightPollWithoutSchedulingErrorRetry() throws Exception {
        run("departureAbortsInFlightPoll");
    }

    @Test
    void nativeDepartureCheckpointsCannotPersistCancellationOrScheduleAnotherPoll() throws Exception {
        run("departureCheckpointsDoNotPublishCancellation");
    }

    @Test
    void lateResponseFromDepartedDocumentCannotRewriteRetainedJobState() throws Exception {
        run("departedResponseCannotRewriteHistory");
    }

    @Test
    void returningFromBackForwardCacheResumesRetainedJobExactlyOnce() throws Exception {
        run("backForwardCacheResumesOnce");
    }

    @Test
    void activePortfolioReportsRealPollingFailureAndSchedulesBoundedRetry() throws Exception {
        run("activeFailureRetainsBoundedRetry");
    }

    @Test
    void leavingPortfolioCancelsScheduledJobDiscovery() throws Exception {
        run("departureCancelsScheduledDiscovery");
    }

    @Test
    void leavingPortfolioAbortsDiscoveryThroughThePortfolioApiTransport() throws Exception {
        run("departureAbortsInFlightDiscovery");
    }

    @Test
    void lateDiscoveryResponseFromDepartedDocumentCannotRegisterJobs() throws Exception {
        run("departedDiscoveryResponseCannotRegisterJobs");
    }

    @Test
    void changingSelectionBeforeProjectLoadsRejectsOldDiscoveredJobs() throws Exception {
        run("changedSelectionCannotRegisterOldDiscovery");
    }

    @Test
    void selectingAnotherProjectAbortsAndInvalidatesPreviousDiscovery() throws Exception {
        run("replacedDiscoveryAbortsAndRejectsOldResponse");
    }

    @Test
    void beginningSelectionCancelsDiscoveryEvenWhenPortfolioRefreshFails() throws Exception {
        run("selectionStartCancelsDiscoveryBeforeFailedRefresh");
    }

    @Test
    void returningToProjectBeforeSelectionRefreshCompletesCannotReviveOldDiscovery() throws Exception {
        run("pendingSelectionsCannotReviveOldDiscovery");
    }

    @Test
    void returningFromBackForwardCacheResumesDiscoveryExactlyOnce() throws Exception {
        run("backForwardCacheResumesDiscoveryOnce");
    }

    @Test
    void activeDiscoveryFailuresKeepTheExistingBoundedRetries() throws Exception {
        run("activeDiscoveryFailureRetainsBoundedRetries");
    }

    @Test
    void actionableJobsSurviveTheHistoryLimitAndAreRestoredAfterReload() throws Exception {
        run("actionableJobsSurviveHistoryLimitAndReload");
    }

    private void run(String contract) throws Exception {
        Path script = copyResource("/portfolio/job-lifecycle-contract.cjs");
        Path canonical = copyResource("/static/js/api/taxonomy-api-client.js");
        Path api = copyResource("/static/js/api/portfolio-api.js");
        Path adapter = copyResource("/static/js/portfolio/taxonomy-portfolio-async.js");
        Path discovery = copyResource("/static/js/portfolio/portfolio-analysis-job-synchronizer.js");
        String node = System.getProperty("taxonomy.node.executable");
        assertNotNull(node, "Maven must provide its pinned Node runtime");
        Path log = directory.resolve(contract + ".log");
        Process process = new ProcessBuilder(List.of(node, script.toString(), contract,
                api.toString(), adapter.toString(), discovery.toString(), canonical.toString()))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Contract timed out: " + log);
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("PORTFOLIO_JOB_LIFECYCLE_OK " + contract), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Contract process did not stop");
            }
        }
    }

    private Path copyResource(String name) throws Exception {
        Path path = directory.resolve(Path.of(name).getFileName());
        try (var resource = getClass().getResourceAsStream(name)) {
            assertNotNull(resource, name);
            Files.copy(resource, path);
        }
        return path;
    }
}
