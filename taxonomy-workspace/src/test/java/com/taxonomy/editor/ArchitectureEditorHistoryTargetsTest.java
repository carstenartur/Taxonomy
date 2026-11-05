package com.taxonomy.editor;

import com.taxonomy.editor.persistence.EditorJournal.OperationSummary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureEditorHistoryTargetsTest {
    private static OperationSummary entry(String id, String kind, String target, String actor, long revision, int bodyVersion) {
        return new OperationSummary(id, actor, "now", "reason", kind, target, revision - 1, revision, bodyVersion, List.of("element:one"));
    }

    @Test
    void selectsFromTheCompleteJournalWhenTheActiveEditIsOlderThanTheVisiblePage() {
        var history = new ArrayList<OperationSummary>();
        for (int i = 55; i >= 1; i--) history.add(entry("undo-" + i, "UNDO", "edit-" + i, "alice", 55 + i, 1));
        for (int i = 55; i >= 1; i--) history.add(entry("edit-" + i, "UpdateElement", null, "alice", i + 1, 1));
        history.add(entry("old-edit", "CreateElement", null, "alice", 1, 1));
        var targets = ArchitectureEditorService.historyTargets(history, "alice");
        assertThat(targets.undo().operationId()).isEqualTo("old-edit");
        assertThat(targets.redo().operationId()).isEqualTo("undo-55");
    }

    @Test
    void returnsTheLatestPersonalTargetEvenWhenItMayBeBlockedOrLegacy() {
        var targets = ArchitectureEditorService.historyTargets(List.of(
                entry("other", "UpdateElement", null, "bob", 3, 1),
                entry("legacy", "UpdateElement", null, "alice", 2, 0),
                entry("older", "CreateElement", null, "alice", 1, 1)), "alice");
        assertThat(targets.undo().operationId()).isEqualTo("legacy");
        assertThat(targets.redo()).isNull();
    }

    @Test
    void respectsInversesAndDoesNotOfferVersionImportsAsEdits() {
        var targets = ArchitectureEditorService.historyTargets(List.of(
                entry("version", "VERSION_IMPORT", null, "alice", 5, 1),
                entry("redo", "REDO", "undo", "alice", 4, 1),
                entry("undo", "UNDO", "edit", "alice", 3, 1),
                entry("edit", "UpdateElement", null, "alice", 2, 1)), "alice");
        assertThat(targets.undo().operationId()).isEqualTo("redo");
        assertThat(targets.redo()).isNull();
        assertThat(ArchitectureEditorService.historyTargets(List.of(), "alice").undo()).isNull();
    }
}
