package com.taxonomy.versioning.service;

import com.taxonomy.versioning.repository.ArchitectureCommitIndexRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@ExtendWith(MockitoExtension.class)
class CommitIndexServiceFailureTest {

    private static final RepositoryContext CONTEXT =
            RepositoryContext.workspace(
                    "repository-a", "workspace-a", "draft", "architect");

    @Mock
    private DslGitRepositoryFactory repositoryFactory;

    @Mock
    private ArchitectureCommitIndexRepository indexRepository;

    @Mock
    private DslGitRepository repository;

    private CommitIndexService service;

    @BeforeEach
    void setUp() {
        service = new CommitIndexService(repositoryFactory, indexRepository);
        when(repositoryFactory.resolveRepository(CONTEXT)).thenReturn(repository);
    }

    @Test
    void indexFailsClosedWhenAuthoritativeHistoryCannotBeRead() throws Exception {
        when(repository.getDslHistory("draft"))
                .thenThrow(new IOException("storage unavailable"));

        assertThatThrownBy(() -> service.indexBranch("draft", CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repository=repository-a")
                .hasMessageContaining("workspace=workspace-a")
                .hasMessageContaining("branch=draft")
                .hasCauseInstanceOf(IOException.class);
        verifyNoInteractions(indexRepository);
    }

    @Test
    void missingBranchCannotEraseTheLastKnownProjection() throws Exception {
        when(repository.getDslHistory("draft")).thenReturn(java.util.List.of());

        assertThatThrownBy(() -> service.rebuildBranch("draft", CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("draft");
        verifyNoInteractions(indexRepository);
    }

    @Test
    void movedBranchCannotReplaceTheExistingProjectionWithEarlierHistory() throws Exception {
        try (DslGitRepository git = spy(new DslGitRepository())) {
            when(repositoryFactory.resolveRepository(CONTEXT)).thenReturn(git);
            String first = git.commitDsl("draft", "initial", "architect", "first");
            doAnswer(invocation -> {
                var captured = invocation.callRealMethod();
                git.commitDsl("draft", "later", "architect", "branch advanced");
                return captured;
            }).when(git).getDslHistory("draft");

            assertThatThrownBy(() -> service.rebuildBranch("draft", CONTEXT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseInstanceOf(com.taxonomy.workspace.service.BranchHeadConflictException.class);
            org.assertj.core.api.Assertions.assertThat(git.getHeadCommit("draft")).isNotEqualTo(first);
            verifyNoInteractions(indexRepository);
        }
    }

    @Test
    void rebuildDoesNotDeleteProjectionBeforeAuthoritativeHistoryIsReadable()
            throws Exception {
        when(repository.getDslHistory("draft"))
                .thenThrow(new IOException("storage unavailable"));

        assertThatThrownBy(() -> service.rebuildBranch("draft", CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class);
        verifyNoInteractions(indexRepository);
    }
}
