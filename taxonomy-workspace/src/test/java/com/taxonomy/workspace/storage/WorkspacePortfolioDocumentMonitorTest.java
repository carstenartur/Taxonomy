package com.taxonomy.workspace.storage;

import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkspacePortfolioDocumentMonitorTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicationUsesTheLiveRepositoryMonitorAndReleasesIt(boolean merge) throws Exception {
        var factory = mock(DslGitRepositoryFactory.class);
        var repository = mock(DslGitRepository.class);
        var git = mock(Repository.class);
        var merges = mock(SemanticGitMergeService.class);
        var context = new WorkspaceContext("alice", "ws-a", "draft", "repo-a");
        when(factory.resolveRepository(context)).thenReturn(repository);
        when(repository.getGitRepository()).thenReturn(git);
        var handle = new WorkspacePortfolioDocumentAdapter(factory, merges).resolveRepository(context);
        if (merge) {
            when(merges.mergeBranches(repository, "source", "draft", "alice", "publish"))
                    .thenAnswer(invocation -> {
                        assertThat(Thread.holdsLock(git)).as("shared live repository monitor").isTrue();
                        return new SemanticGitMergeService.MergeOutcome(true, "merged", false, List.of(), null);
                    });
            assertThat(handle.mergeBranches("source", "draft", "alice", "publish").commitId()).isEqualTo("merged");
        } else {
            when(repository.commitDsl("draft", "dsl", "alice", "publish"))
                    .thenAnswer(invocation -> {
                        assertThat(Thread.holdsLock(git)).as("shared live repository monitor").isTrue();
                        return "committed";
                    });
            assertThat(handle.commitDsl("draft", "dsl", "alice", "publish")).isEqualTo("committed");
        }
        assertThat(Thread.holdsLock(git)).as("no lock held during later ORM work").isFalse();
        verify(factory).resolveRepository(context);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedPublicationPreservesTheExceptionAndReleasesTheMonitor(boolean merge) throws Exception {
        var factory = mock(DslGitRepositoryFactory.class);
        var repository = mock(DslGitRepository.class);
        var git = mock(Repository.class);
        var merges = mock(SemanticGitMergeService.class);
        var context = new WorkspaceContext("alice", "ws-a", "draft", "repo-a");
        when(factory.resolveRepository(context)).thenReturn(repository);
        when(repository.getGitRepository()).thenReturn(git);
        var handle = new WorkspacePortfolioDocumentAdapter(factory, merges).resolveRepository(context);
        var failure = new IOException("publication failed");
        org.mockito.stubbing.Answer<Object> fail = invocation -> {
            assertThat(Thread.holdsLock(git)).as("failure occurs within publication monitor").isTrue();
            throw failure;
        };
        if (merge) {
            when(merges.mergeBranches(repository, "source", "draft", "alice", "publish")).thenAnswer(fail);
            assertThatThrownBy(() -> handle.mergeBranches("source", "draft", "alice", "publish")).isSameAs(failure);
        } else {
            when(repository.commitDsl("draft", "dsl", "alice", "publish")).thenAnswer(fail);
            assertThatThrownBy(() -> handle.commitDsl("draft", "dsl", "alice", "publish")).isSameAs(failure);
        }
        assertThat(Thread.holdsLock(git)).isFalse();
    }
}
