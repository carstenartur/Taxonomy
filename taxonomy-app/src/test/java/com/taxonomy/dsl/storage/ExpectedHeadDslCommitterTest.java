package com.taxonomy.dsl.storage;

import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.CommitRequest;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpectedHeadDslCommitterTest {

    private final ExpectedHeadDslCommitter committer =
            new ExpectedHeadDslCommitter();

    @Test
    void createsInitialAndChildCommitsOnlyAtTheExpectedHead() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            var initial = committer.commit(repository, new CommitRequest(
                    "draft",
                    null,
                    "meta {\n  language: \"taxdsl\";\n}\n",
                    "alice",
                    "Initialize architecture"));

            assertThat(initial.previousHeadCommit()).isNull();
            assertThat(initial.refUpdateResult()).isEqualTo(RefUpdate.Result.NEW);
            assertThat(repository.getHeadCommit("draft"))
                    .isEqualTo(initial.commitId());

            var child = committer.commit(repository, new CommitRequest(
                    "draft",
                    initial.commitId(),
                    "element APP-1 type Application {\n}\n",
                    "alice",
                    "Add APP-1"));

            assertThat(child.previousHeadCommit())
                    .isEqualTo(initial.commitId());
            assertThat(child.refUpdateResult())
                    .isEqualTo(RefUpdate.Result.FAST_FORWARD);
            assertThat(repository.getHeadCommit("draft"))
                    .isEqualTo(child.commitId());
            assertThat(repository.getDslAtHead("draft"))
                    .contains("element APP-1 type Application");
            assertThat(repository.getCommitCount("draft")).isEqualTo(2);
            assertThat(repository.getHeadCommitInfo("draft").author())
                    .isEqualTo("alice");
        }
    }

    @Test
    void rejectsAStaleExpectedHeadWithoutChangingTheBranch() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            String initial = committer.commit(repository, new CommitRequest(
                    "draft", null, "meta {\n}\n", "alice", "Initial"))
                    .commitId();
            String competing = repository.commitDsl(
                    "draft",
                    "element OTHER-1 type Application {\n}\n",
                    "bob",
                    "Concurrent edit");

            assertThatThrownBy(() -> committer.commit(repository, new CommitRequest(
                    "draft",
                    initial,
                    "element APP-1 type Application {\n}\n",
                    "alice",
                    "Stale command")))
                    .isInstanceOfSatisfying(
                            BranchHeadConflictException.class,
                            conflict -> {
                                assertThat(conflict.getBranch()).isEqualTo("draft");
                                assertThat(conflict.getExpectedHeadCommit())
                                        .isEqualTo(initial);
                                assertThat(conflict.getActualHeadCommit())
                                        .isEqualTo(competing);
                            });

            assertThat(repository.getHeadCommit("draft")).isEqualTo(competing);
            assertThat(repository.getDslAtHead("draft"))
                    .contains("OTHER-1")
                    .doesNotContain("APP-1");
            assertThat(repository.getCommitCount("draft")).isEqualTo(2);
        }
    }

    @Test
    void distinguishesAbsentBranchFromAnExistingBranch() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            String nonexistentExpected = ObjectId.fromString(
                    "1111111111111111111111111111111111111111").name();

            assertThatThrownBy(() -> committer.commit(repository, new CommitRequest(
                    "draft",
                    nonexistentExpected,
                    "meta {\n}\n",
                    "alice",
                    "Unexpected parent")))
                    .isInstanceOfSatisfying(
                            BranchHeadConflictException.class,
                            conflict -> {
                                assertThat(conflict.getExpectedHeadCommit())
                                        .isEqualTo(nonexistentExpected);
                                assertThat(conflict.getActualHeadCommit()).isNull();
                            });

            String initial = committer.commit(repository, new CommitRequest(
                    "draft", null, "meta {\n}\n", "alice", "Initial"))
                    .commitId();
            assertThatThrownBy(() -> committer.commit(repository, new CommitRequest(
                    "draft",
                    null,
                    "element APP-1 type Application {\n}\n",
                    "alice",
                    "Unexpected initial command")))
                    .isInstanceOfSatisfying(
                            BranchHeadConflictException.class,
                            conflict -> {
                                assertThat(conflict.getExpectedHeadCommit()).isNull();
                                assertThat(conflict.getActualHeadCommit())
                                        .isEqualTo(initial);
                            });
        }
    }

    @Test
    void validatesBranchExpectedCommitAndRequiredContent() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitRequest(
                        " ", null, "dsl", "alice", "message"))
                .withMessageContaining("branch must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitRequest(
                        "bad branch", null, "dsl", "alice", "message"))
                .withMessageContaining("invalid branch name");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitRequest(
                        "draft", " ", "dsl", "alice", "message"))
                .withMessageContaining("must be null or a full commit ID");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitRequest(
                        "draft", "not-a-commit", "dsl", "alice", "message"));
        assertThatThrownBy(() -> new CommitRequest(
                "draft", null, null, "alice", "message"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("dslText");
    }

    @Test
    void normalizesDefaultAuthorAndMessage() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            var result = committer.commit(repository, new CommitRequest(
                    " draft ", null, "meta {\n}\n", " ", " "));

            assertThat(repository.getHeadCommit("draft"))
                    .isEqualTo(result.commitId());
            assertThat(repository.getHeadCommitInfo("draft").author())
                    .isEqualTo("taxonomy");
            assertThat(repository.getHeadCommitInfo("draft").message())
                    .isEqualTo("DSL update");
        }
    }
}
