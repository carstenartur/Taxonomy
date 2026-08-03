package com.taxonomy.versioning.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import org.junit.jupiter.api.Test;

import java.util.ConcurrentModificationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionalDslCommitterTest {

    private static final String DSL = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "test";
            }
            """;

    @Test
    void commitsOnlyOnExpectedHeadAndPreservesActor() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            String initial = repository.commitDsl("draft", DSL, "bootstrap", "Initial");
            ConditionalDslCommitter committer = new ConditionalDslCommitter();

            String next = committer.commit(
                    repository, "draft", initial, DSL, "architect", "Selective COPY");

            assertThat(repository.getHeadCommit("draft")).isEqualTo(next);
            assertThat(repository.getHeadCommitInfo("draft").author()).isEqualTo("architect");
        }
    }

    @Test
    void staleExpectedHeadFailsWithoutMovingBranch() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            String initial = repository.commitDsl("draft", DSL, "bootstrap", "Initial");
            String concurrent = repository.commitDsl("draft", DSL, "other", "Concurrent");
            ConditionalDslCommitter committer = new ConditionalDslCommitter();

            assertThatThrownBy(() -> committer.commit(
                    repository, "draft", initial, DSL, "architect", "Stale transfer"))
                    .isInstanceOf(ConcurrentModificationException.class)
                    .hasMessageContaining("moved");
            assertThat(repository.getHeadCommit("draft")).isEqualTo(concurrent);
        }
    }
}
