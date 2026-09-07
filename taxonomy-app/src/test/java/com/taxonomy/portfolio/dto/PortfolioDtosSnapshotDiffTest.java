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
        changes.put("Z", new ScoreChange(1, 2));
        changes.put("A", new ScoreChange(2, 3));
        changes.put("M", new ScoreChange(3, 4));

        SnapshotDiff diff = new SnapshotDiff(
                "old", "new", changes,
                List.of(), List.of(), List.of(), List.of(),
                false, false, false);
        changes.clear();

        assertEquals(List.of("A", "M", "Z"),
                new ArrayList<>(diff.scoreChanges().keySet()));
        assertEquals(new ScoreChange(2, 3), diff.scoreChanges().get("A"));
        assertThrows(UnsupportedOperationException.class,
                () -> diff.scoreChanges().put("B", new ScoreChange(4, 5)));
    }

    @Test
    void absentScoreChangesBecomeAnImmutableEmptyMap() {
        SnapshotDiff diff = new SnapshotDiff(
                "old", "new", null,
                List.of(), List.of(), List.of(), List.of(),
                false, false, false);

        assertEquals(Map.of(), diff.scoreChanges());
        assertThrows(UnsupportedOperationException.class,
                () -> diff.scoreChanges().put("B", new ScoreChange(4, 5)));
    }
}
