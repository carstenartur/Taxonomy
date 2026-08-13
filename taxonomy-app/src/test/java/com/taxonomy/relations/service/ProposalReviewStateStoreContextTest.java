package com.taxonomy.relations.service;

import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProposalReviewStateStoreContextTest {

    @Test
    void acceptsCentralWorkspaceAndRepositoryIsolatedForkWrites() {
        RepositoryContext central = RepositoryContext.centralWrite(
                "repo-a", "draft", "alice");
        RepositoryContext workspace = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        RepositoryContext fork = new RepositoryContext(
                "fork-a", null, "draft", "alice", RepositoryScope.FORK);

        assertThat(ProposalReviewStateStore.requireWritableContext(central))
                .isSameAs(central);
        assertThat(ProposalReviewStateStore.requireWritableContext(workspace))
                .isSameAs(workspace);
        assertThat(ProposalReviewStateStore.requireWritableContext(fork))
                .isSameAs(fork);
    }

    @Test
    void rejectsCentralReadAndImpossibleScopeWorkspaceCombinations() {
        RepositoryContext centralRead = RepositoryContext.centralRead(
                "repo-a", "draft", "alice");

        assertThatThrownBy(() -> ProposalReviewStateStore
                .requireWritableContext(centralRead))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CENTRAL_WRITE or FORK");
        assertThatThrownBy(() -> ProposalReviewStateStore
                .requireWritableContext(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }
}
