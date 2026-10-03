package com.taxonomy.analysis.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.util.List;
import static com.taxonomy.analysis.backup.AnalysisRunSelection.*;
import static org.assertj.core.api.Assertions.*;

class AnalysisRunSelectionTest {
    @Test void currentSelectionUsesExactKeysAndTimestampThenIdRegardlessOfVisitOrder() throws Exception {
        var selection = new AnalysisRunSelection(false);
        var original = run("a", "alice", "repo", "workspace", "draft", 10);
        var winner = run("z", "alice", "repo", "workspace", "draft", 20);
        for (var run : List.of(original, winner, run("y", "alice", "repo", "workspace", "draft", 20),
                run("zz", "alice", "repo", "workspace", "draft", 19),
                run("b", "ALICE", "repo", "workspace", "draft", 1),
                run("c", "alice", "REPO", "workspace", "draft", 1),
                run("d", "alice", "repo", "WORKSPACE", "draft", 1),
                run("e", "alice", "repo", "workspace", "DRAFT", 1))) selection.accept(run);
        assertThat(selection.selected()).containsOnlyKeys("b", "c", "d", "e", "z");
        assertThat(selection.selected().get("z")).isEqualTo(winner);
        assertThat(selection.selected().keySet()).containsExactly("b", "c", "d", "e", "z");
    }
    @Test void historyKeepsSupersededRuns() throws Exception {
        var selection = new AnalysisRunSelection(true);
        selection.accept(run("a", "alice", "repo", "workspace", "draft", 10));
        selection.accept(run("b", "alice", "repo", "workspace", "draft", 20));
        assertThat(selection.selected()).containsOnlyKeys("a", "b");
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectionLimitFailsWithoutReturningTruncatedSuccess(boolean history) throws Exception {
        var selection = new AnalysisRunSelection(history);
        for (int i = 0; i < 100_000; i++) selection.accept(run("run-" + i, "alice", "repo", "workspace", "branch-" + i, 10));
        if (!history) {
            selection.accept(run("newest", "alice", "repo", "workspace", "branch-0", 20));
            selection.accept(run("older", "alice", "repo", "workspace", "branch-0", 1));
            assertThat(selection.selected()).containsKey("newest").doesNotContainKeys("run-0", "older");
        }
        assertThatThrownBy(() -> selection.accept(run("overflow", "alice", "repo", "workspace", "extra", 10)))
                .isInstanceOf(IOException.class).hasMessage("Analysis continuation selection limit exceeded");
        assertThat(selection.selected()).hasSize(100_000);
    }
    private static Run run(String id, String owner, String repository, String workspace, String branch, long updated) {
        return new Run(id, new Key(owner, repository, workspace, branch), "hash", updated, 0, "PAUSED", "N1");
    }
}
