package com.taxonomy.export.controller;

import org.junit.jupiter.api.Test;

class CurrentDiagramExportTest {
    @Test
    void exportsCompleteCurrentGraphWithoutAnyLlmOrDerivationService() throws Exception {
        CurrentDiagramExportRegression.exportsAllCurrentNodesWithoutScoring();
    }

    @Test
    void rejectsMissingInvalidAndOversizedSnapshotsWithoutFallbackAnalysis() {
        CurrentDiagramExportRegression.rejectsInvalidSnapshotsWithoutScoring();
    }
}
