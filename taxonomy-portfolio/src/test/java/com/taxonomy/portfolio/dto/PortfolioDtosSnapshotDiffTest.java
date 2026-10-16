package com.taxonomy.portfolio.dto;

import com.taxonomy.portfolio.dto.PortfolioDtos.ScoreChange;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDiff;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PortfolioDtosSnapshotDiffTest {

    @Test
    void scoreChangesRemainCanonicalAndImmutableAtTheRestBoundary() {
        Map<String, ScoreChange> changes = new LinkedHashMap<>();
        changes.put("Z", new ScoreChange(1d, 2d));
        changes.put("A", new ScoreChange(2d, 3d));
        changes.put("M", new ScoreChange(3d, 4d));

        SnapshotDiff diff = new SnapshotDiff(
                "old", "new", changes,
                List.of(), List.of(), List.of(), List.of(),
                false, false, false);
        changes.clear();

        assertEquals(List.of("A", "M", "Z"),
                new ArrayList<>(diff.scoreChanges().keySet()));
        assertEquals(new ScoreChange(2d, 3d), diff.scoreChanges().get("A"));
        assertThrows(UnsupportedOperationException.class,
                () -> diff.scoreChanges().put("B", new ScoreChange(4d, 5d)));
    }

    @Test
    void absentScoreChangesBecomeAnImmutableEmptyMap() {
        SnapshotDiff diff = new SnapshotDiff(
                "old", "new", null,
                List.of(), List.of(), List.of(), List.of(),
                false, false, false);

        assertEquals(Map.of(), diff.scoreChanges());
        assertThrows(UnsupportedOperationException.class,
                () -> diff.scoreChanges().put("B", new ScoreChange(4d, 5d)));
    }
}
