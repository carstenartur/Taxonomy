package com.taxonomy.portfolio.dto;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotSelectionTest {
    @Test void absentScoresRetainTheSnapshotAndItsCurrentMappingCounts() {
        var selection = new SnapshotSelection("snapshot", AnalysisStatus.PARTIAL, 125, null, 1, 2, 3);
        assertTrue(selection.scores().isEmpty());
        assertEquals("snapshot", selection.id());
        assertEquals(AnalysisStatus.PARTIAL, selection.status());
        assertEquals(2, selection.elementMappingCount());
        assertEquals(3, selection.relationMappingCount());
    }

    @Test void capturedScoresCannotBeChangedByTheirCallerOrASelectionConsumer() {
        var scores = new LinkedHashMap<>(Map.of("source", 75d));
        var selection = new SnapshotSelection("snapshot", AnalysisStatus.SUCCESS, 125, scores, 0, 2, 3);
        scores.put("source", 0d);
        scores.put("later", 100d);
        assertEquals(Map.of("source", 75d), selection.scores());
        assertThrows(UnsupportedOperationException.class, () -> selection.scores().put("source", 0d));
    }
}
