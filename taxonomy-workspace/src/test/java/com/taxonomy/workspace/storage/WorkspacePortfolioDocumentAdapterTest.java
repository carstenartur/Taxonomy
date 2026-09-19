package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkspacePortfolioDocumentAdapterTest {
    @Test
    void readsWritesAndMergesUseOneCapturedRepository() throws Exception {
        var factory = mock(DslGitRepositoryFactory.class);
        var repository = mock(DslGitRepository.class);
        var other = mock(DslGitRepository.class);
        var merges = mock(SemanticGitMergeService.class);
        var context = new WorkspaceContext("alice", "ws-a", "draft", "repo-a");
        when(factory.resolveRepository(context)).thenReturn(repository, other);
        when(repository.getGitRepository()).thenReturn(mock(org.eclipse.jgit.lib.Repository.class));
        when(repository.getHeadCommit("draft")).thenReturn("head");
        when(repository.getDslAtHead("draft")).thenReturn("dsl");
        when(repository.getDslAtCommit("head")).thenReturn("immutable dsl");
        when(repository.commitDsl("draft", "updated", "alice", "checkpoint")).thenReturn("next");
        when(merges.mergeBranches(repository, "source", "draft", "alice", "merge"))
                .thenReturn(new SemanticGitMergeService.MergeOutcome(true, "merged", true, List.of(), null));
        var handle = new WorkspacePortfolioDocumentAdapter(factory, merges, publicationVersionsFixture()).resolveRepository(context);
        assertThat(handle.getHeadCommit("draft")).isEqualTo("head");
        assertThat(handle.getDslAtHead("draft")).isEqualTo("dsl");
        assertThat(handle.getDslAtCommit("head")).isEqualTo("immutable dsl");
        assertThat(handle.commitDsl("draft", "updated", "alice", "checkpoint")).isEqualTo("next");
        var result = handle.mergeBranches("source", "draft", "alice", "merge");
        assertThat(result.success()).isTrue();
        assertThat(result.commitId()).isEqualTo("merged");
        assertThat(result.semanticFallback()).isTrue();
        assertThat(result.conflicts()).isEmpty();
        verify(factory, times(1)).resolveRepository(context);
        verifyNoInteractions(other);
    }

    @Test
    void mergeConflictsAreReturnedUnchanged() throws Exception {
        var factory = mock(DslGitRepositoryFactory.class);
        var repository = mock(DslGitRepository.class);
        var merges = mock(SemanticGitMergeService.class);
        var context = new WorkspaceContext("alice", "ws-a", "draft", "repo-a");
        when(factory.resolveRepository(context)).thenReturn(repository);
        when(repository.getGitRepository()).thenReturn(mock(org.eclipse.jgit.lib.Repository.class));
        var conflicts = List.of("requirement:REQ-1");
        when(merges.mergeBranches(repository, "source", "draft", "alice", null))
                .thenReturn(new SemanticGitMergeService.MergeOutcome(false, null, false, conflicts, null));
        var result = new WorkspacePortfolioDocumentAdapter(factory, merges, publicationVersionsFixture()).resolveRepository(context)
                .mergeBranches("source", "draft", "alice", null);
        assertThat(result.success()).isFalse();
        assertThat(result.commitId()).isNull();
        assertThat(result.semanticFallback()).isFalse();
        assertThat(result.conflicts()).isSameAs(conflicts);
    }

    @Test
    void storageFailuresReachTheCallerWithoutFallback() throws Exception {
        var factory = mock(DslGitRepositoryFactory.class);
        var repository = mock(DslGitRepository.class);
        var merges = mock(SemanticGitMergeService.class);
        var context = new WorkspaceContext("alice", "ws-a", "draft", "repo-a");
        when(factory.resolveRepository(context)).thenReturn(repository);
        when(repository.getGitRepository()).thenReturn(mock(org.eclipse.jgit.lib.Repository.class));
        var failure = new IOException("storage unavailable");
        when(repository.getDslAtHead("draft")).thenThrow(failure);
        var handle = new WorkspacePortfolioDocumentAdapter(factory, merges, publicationVersionsFixture()).resolveRepository(context);
        assertThatThrownBy(() -> handle.getDslAtHead("draft")).isSameAs(failure);
        verify(factory, times(1)).resolveRepository(context);
        verifyNoInteractions(merges);
    }

    private static com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort publicationVersionsFixture() {
        return new com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort() {
            @Override
            public <T> T version(com.taxonomy.workspace.service.RepositoryContext context, String rationale,
                    GitAction<T> action) throws java.io.IOException {
                return action.run();
            }
        };
    }
}
