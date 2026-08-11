package com.taxonomy.architecture.service;

import com.taxonomy.architecture.repository.ArchitectureCommitIndexRepository;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
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
