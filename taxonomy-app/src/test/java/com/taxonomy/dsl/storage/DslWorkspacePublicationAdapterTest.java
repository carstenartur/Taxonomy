package com.taxonomy.dsl.storage;

import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceDslPublicationPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DslWorkspacePublicationAdapterTest {

    private static final RepositoryContext CONTEXT =
            RepositoryContext.workspace("repo-a", "workspace-a", "review", "alice");

    @Test
    void bindsTheExplicitContextOnceAndForwardsTheExactPublication() throws Exception {
        var factory = mock(DslGitRepositoryFactory.class);
        var repository = mock(DslGitRepository.class);
        when(factory.resolveRepository(CONTEXT)).thenReturn(repository);
        when(repository.commitDsl("draft", "# snapshot\n", "alice", "Publication"))
                .thenReturn("a".repeat(40));

        assertThat(new DslWorkspacePublicationAdapter(factory)
                .publishSnapshot(CONTEXT, "draft", "# snapshot\n", "Publication"))
                .isEqualTo("a".repeat(40));

        verify(factory).resolveRepository(CONTEXT);
        verify(repository).commitDsl("draft", "# snapshot\n", "alice", "Publication");
        verifyNoMoreInteractions(factory, repository);
    }

    @Test
    void realPublicationKeepsWorkspaceAndTargetBranchIsolated() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var selected = factory.resolveRepository(CONTEXT);
            String review = selected.commitDsl("review", "# review", "seed", "Review");
            var other = factory.resolveRepository(RepositoryContext.workspace(
                    "repo-a", "workspace-b", "review", "bob"));
            String otherDraft = other.commitDsl("draft", "# other", "seed", "Other");

            String published = new DslWorkspacePublicationAdapter(factory)
                    .publishSnapshot(CONTEXT, "draft", "# selected", "Publication");

            assertThat(selected.getHeadCommit("draft")).isEqualTo(published);
            assertThat(selected.getDslAtHead("draft")).isEqualTo("# selected");
            assertThat(selected.getHeadCommitInfo("draft").message()).isEqualTo("Publication");
            assertThat(selected.getHeadCommit("review")).isEqualTo(review);
            assertThat(other.getHeadCommit("draft")).isEqualTo(otherDraft);
            assertThat(other.getDslAtHead("draft")).isEqualTo("# other");
        }
    }

    @Test
    void centralAndForkPublicationDoNotFallBackToTheSystemRepository() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var system = factory.getSystemRepository();
            String systemHead = system.commitDsl("accepted", "# system", "seed", "System");
            var central = RepositoryContext.centralWrite("selected", "review", "maintainer");
            var fork = new RepositoryContext("fork-a", null, "review", "alice", RepositoryScope.FORK);
            var publication = new DslWorkspacePublicationAdapter(factory);

            String centralHead = publication.publishSnapshot(central, "accepted", "# central", "Central");
            String forkHead = publication.publishSnapshot(fork, "accepted", "# fork", "Fork");

            assertThat(factory.resolveRepository(central).getHeadCommit("accepted")).isEqualTo(centralHead);
            assertThat(factory.resolveRepository(central).getDslAtHead("accepted")).isEqualTo("# central");
            assertThat(factory.resolveRepository(fork).getHeadCommit("accepted")).isEqualTo(forkHead);
            assertThat(factory.resolveRepository(fork).getDslAtHead("accepted")).isEqualTo("# fork");
            assertThat(system.getHeadCommit("accepted")).isEqualTo(systemHead);
        }
    }

    @Test
    void subsequentSnapshotsKeepExistingAppendAndEmptyDocumentSemantics() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var publication = new DslWorkspacePublicationAdapter(factory);
            var repository = factory.resolveRepository(CONTEXT);
            String first = publication.publishSnapshot(CONTEXT, "draft", "# first", "First");
            String second = publication.publishSnapshot(CONTEXT, "draft", "", "Second");

            assertThat(second).isNotEqualTo(first);
            assertThat(repository.getCommitCount("draft")).isEqualTo(2);
            assertThat(repository.getDslAtCommit(first)).isEqualTo("# first");
            assertThat(repository.getDslAtCommit(second)).isEmpty();
            assertThat(repository.getHeadCommit("draft")).isEqualTo(second);
        }
    }

    @Test
    void readOnlyPublicationFailsBeforeResolvingStorage() {
        var factory = mock(DslGitRepositoryFactory.class);
        var publication = new DslWorkspacePublicationAdapter(factory);
        var context = RepositoryContext.centralRead("repo-a", "review", "reader");

        assertThatThrownBy(() -> publication.publishSnapshot(context, "draft", "# forbidden", "Forbidden"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("read-only");
        verifyNoInteractions(factory);
    }

    @Test
    void invalidInputsFailBeforeResolvingStorage() {
        var factory = mock(DslGitRepositoryFactory.class);
        var publication = new DslWorkspacePublicationAdapter(factory);

        assertThatThrownBy(() -> new DslWorkspacePublicationAdapter(null))
                .isInstanceOf(NullPointerException.class).hasMessage("repositories");
        assertThatThrownBy(() -> publication.publishSnapshot(null, "draft", "", "Message"))
                .isInstanceOf(NullPointerException.class).hasMessage("context");
        assertThatThrownBy(() -> publication.publishSnapshot(CONTEXT, null, "", "Message"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("targetBranch");
        assertThatThrownBy(() -> publication.publishSnapshot(CONTEXT, " ", "", "Message"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("targetBranch");
        assertThatThrownBy(() -> publication.publishSnapshot(CONTEXT, "draft", null, "Message"))
                .isInstanceOf(NullPointerException.class).hasMessage("dslText");
        verifyNoInteractions(factory);
    }

    @Test
    void ioFailureIsPropagatedWithoutRetryOrSuccessToken() throws Exception {
        var factory = mock(DslGitRepositoryFactory.class);
        var repository = mock(DslGitRepository.class);
        var failure = new IOException("Storage unavailable");
        when(factory.resolveRepository(CONTEXT)).thenReturn(repository);
        when(repository.commitDsl("draft", "# snapshot", "alice", "Publication")).thenThrow(failure);

        assertThatThrownBy(() -> new DslWorkspacePublicationAdapter(factory)
                .publishSnapshot(CONTEXT, "draft", "# snapshot", "Publication"))
                .isSameAs(failure);
        verify(factory).resolveRepository(CONTEXT);
        verify(repository).commitDsl("draft", "# snapshot", "alice", "Publication");
        verifyNoMoreInteractions(factory, repository);
    }

    @Test
    void springProvidesOneWorkspacePublicationImplementation() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DslGitRepositoryFactory.class, () -> new DslGitRepositoryFactory(null));
            context.register(DslWorkspacePublicationAdapter.class);
            context.refresh();

            assertThat(context.getBean(WorkspaceDslPublicationPort.class))
                    .isSameAs(context.getBean(DslWorkspacePublicationAdapter.class));
        }
    }
}
