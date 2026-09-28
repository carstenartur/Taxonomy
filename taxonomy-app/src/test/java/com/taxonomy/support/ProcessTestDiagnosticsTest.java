package com.taxonomy.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class ProcessTestDiagnosticsTest {
    @TempDir
    Path directory;

    @Test
    void largeFailureKeepsExitCodeTailAndOriginalArtifactWithoutDuplicatingTheLog() throws Exception {
        ProcessTestDiagnosticsChecks.checkLargeFailure(directory);
    }

    @Test
    void missingCompletionMarkerAlsoHasBoundedDiagnostics() throws Exception {
        ProcessTestDiagnosticsChecks.checkMissingMarker(directory);
    }

    @Test
    void markerBeforeTheTailAndAcrossReadBoundariesStillCountsButCannotHideNonzeroExit() throws Exception {
        ProcessTestDiagnosticsChecks.checkMarkerAnywhere(directory);
    }

    @Test
    void missingLogCannotHideAProcessFailureOrPretendSuccess() throws Exception {
        ProcessTestDiagnosticsChecks.checkMissingLog(directory);
    }

    @Test
    void singleLongUnicodeLineIsBoundedWithoutSplittingCharacters() throws Exception {
        ProcessTestDiagnosticsChecks.checkLongUnicodeLine(directory);
    }

    @Test
    void shortDiagnosticIsNotUnnecessarilyTruncated() throws Exception {
        ProcessTestDiagnosticsChecks.checkSmallLog(directory);
    }
}
