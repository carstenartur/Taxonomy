package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dsl.storage.DslWorkspaceReadAdapter;
import com.taxonomy.dsl.storage.DslWorkspaceVersionAdapter;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BranchHeadConflictContractTest {
    @Test
    void commandReadAndNoOpPathsExposeTheSameWorkspaceOwnedConflict() throws Exception {
        var context = RepositoryContext.workspace("repo-a", "workspace-a", "review", "alice");
        try (var factory = new DslGitRepositoryFactory(null)) {
            var repository = factory.resolveRepository(context);
            String head = repository.commitDsl("review", "# before", "alice", "Initial");
            var version = new DslWorkspaceVersionAdapter(factory).openVersion(context, head);
            String newer = repository.commitDsl("review", "# after", "bob", "Concurrent");

            assertThatThrownBy(() -> version.commit("# stale", "Stale write"))
                    .isInstanceOfSatisfying(BranchHeadConflictException.class, error -> {
                        assertThat(error.getBranch()).isEqualTo("review");
                        assertThat(error.getExpectedHeadCommit()).isEqualTo(head);
                        assertThat(error.getActualHeadCommit()).isEqualTo(newer);
                    });
            assertThatThrownBy(version::verifyExpectedHead).isInstanceOf(BranchHeadConflictException.class);
            assertThatThrownBy(() -> new DslWorkspaceReadAdapter(factory).openRead(context).verifyExpectedHead(head))
                    .isInstanceOf(BranchHeadConflictException.class);
            assertThat(repository.getHeadCommit("review")).isEqualTo(newer);
            assertThat(repository.getCommitCount("review")).isEqualTo(2);
        }
    }

    @Test
    void absentHeadsAndDiagnosticMessagesRemainRepresentable() {
        var conflict = new BranchHeadConflictException("review", null, null, "Ref lock unavailable");
        assertThat(conflict).isInstanceOf(IOException.class);
        assertThat(conflict.getBranch()).isEqualTo("review");
        assertThat(conflict.getExpectedHeadCommit()).isNull();
        assertThat(conflict.getActualHeadCommit()).isNull();
        assertThat(conflict.getMessage()).isEqualTo("Ref lock unavailable");
    }
}
