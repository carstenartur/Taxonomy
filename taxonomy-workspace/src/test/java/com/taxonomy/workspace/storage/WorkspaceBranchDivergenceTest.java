package com.taxonomy.workspace.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceBranchDivergenceTest {
    @Test
    void comparesOnlyTheTwoSelectedBranchHistoriesWithoutCopyingObjectsOrUpdatingRefs() throws Exception {
        try (var central = new DslGitRepository(); var working = new DslGitRepository()) {
            String base = central.commitDsl("upstream", "base", "alice", "Base");
            GitHistoryTransfer.copyCommitClosure(central, working, base, List.of());
            working.createBranchAtCommit("draft", base);
            assertThat(working.getAheadBehindCounts("draft", central, "upstream")).containsExactly(0, 0);
            String unpublished = working.commitDsl("draft", "working", "alice", "Working change");
            String incoming = central.commitDsl("upstream", "source", "owner", "Source change");
            central.commitDsl("unrelated", "foreign", "owner", "Other branch");
            assertThat(working.getAheadBehindCounts("draft", central, "upstream")).containsExactly(1, 1);
            assertThat(working.getAheadBehindCounts("absent", central, "upstream")).containsExactly(0, 2);
            assertThat(working.getAheadBehindCounts("draft", central, "absent")).containsExactly(2, 0);
            assertThat(working.getAheadBehindCounts("absent", central, "absent")).containsExactly(0, 0);
            assertThat(working.getAheadBehindCounts("draft", working, "draft")).containsExactly(0, 0);
            assertThat(working.getHeadCommit("draft")).isEqualTo(unpublished);
            assertThat(central.getHeadCommit("upstream")).isEqualTo(incoming);
            assertThat(working.getGitRepository().getObjectDatabase().has(org.eclipse.jgit.lib.ObjectId.fromString(incoming))).isFalse();
            assertThat(central.getGitRepository().getObjectDatabase().has(org.eclipse.jgit.lib.ObjectId.fromString(unpublished))).isFalse();
        }
    }
}
