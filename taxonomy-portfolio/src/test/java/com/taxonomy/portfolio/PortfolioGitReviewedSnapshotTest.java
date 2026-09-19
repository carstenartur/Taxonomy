package com.taxonomy.portfolio;

import com.taxonomy.portfolio.service.*;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.WorkspacePortfolioDocumentAdapter;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PortfolioGitReviewedSnapshotTest {
    private static final String REVIEWED = "meta { version: \"2.0\"; }\nproject REVIEWED {}\n";
    private static final String ADVANCED = "meta { version: \"2.0\"; }\nproject NOT_REVIEWED {}\n";
    private final WorkspaceContext context = new WorkspaceContext("alice", "ws-a", "draft", "repo-a");
    private final DslGitRepositoryFactory factory = mock(DslGitRepositoryFactory.class);
    private final DslGitRepository repository = mock(DslGitRepository.class);
    private final SemanticGitMergeService merges = mock(SemanticGitMergeService.class);
    private final PortfolioGitService core = mock(PortfolioGitService.class);

    private PortfolioGitApplicationService service() throws Exception {
        when(factory.resolveRepository(context)).thenReturn(repository);
        when(repository.getGitRepository()).thenReturn(mock(Repository.class));
        // A branch may advance immediately after the caller validates its HEAD.
        when(repository.getHeadCommit("target")).thenReturn("reviewed-head", "advanced-head");
        when(repository.getDslAtHead("target")).thenReturn(ADVANCED);
        when(repository.getDslAtCommit("reviewed-head")).thenReturn(REVIEWED);
        var result = new PortfolioGitService.MaterializeResult(1, 2, 3, List.of());
        when(core.materializeHead(anyString(), eq("alice"), eq(context))).thenReturn(result);
        when(core.materialize(anyString(), eq("alice"), eq(context))).thenReturn(result);
        return new PortfolioGitApplicationService(core, mock(ProjectPortfolioService.class),
                mock(SolutionPortfolioService.class), mock(ProductCatalogService.class),
                mock(ProjectConflictService.class), new WorkspacePortfolioDocumentAdapter(factory, merges, publicationVersionsFixture()));
    }

    @Test
    void materializationUsesReviewedBytesWithoutResolvingTheRepositoryAgain() throws Exception {
        var service = service();
        var other = mock(DslGitRepository.class);
        when(factory.resolveRepository(context)).thenReturn(repository, other);
        var result = service.materialize("target", "reviewed-head", context);
        assertThat(result.commitId()).isEqualTo("reviewed-head");
        assertThat(result.projectsUpserted()).isEqualTo(1);
        verify(core).materialize(REVIEWED, "alice", context);
        verify(core, never()).materializeHead(anyString(), anyString(), any());
        verify(repository, never()).getDslAtHead(anyString());
        verify(factory, times(1)).resolveRepository(context);
        verifyNoInteractions(other);
    }

    @Test
    void previewFingerprintBelongsToTheDisplayedImmutableCommit() throws Exception {
        var service = service();
        when(core.contributeTo(anyString(), eq("alice"), eq(context))).thenReturn("previous");
        var preview = service.previewMaterialize("target", context);
        assertThat(preview.targetHead()).isEqualTo("reviewed-head");
        assertThat(preview.targetFingerprint()).isEqualTo(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                        REVIEWED.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThat(preview.addedPreview()).contains("project REVIEWED {}").doesNotContain("project NOT_REVIEWED {}");
        verify(repository).getDslAtCommit("reviewed-head");
        verify(repository, never()).getDslAtHead(anyString());
    }

    @Test
    void mergeMaterializesTheReturnedMergeCommitEvenWhenTheBranchAdvances() throws Exception {
        var service = service();
        when(repository.getHeadCommit("source")).thenReturn("source-head");
        when(merges.mergeBranches(repository, "source", "target", "alice", "merge"))
                .thenReturn(new SemanticGitMergeService.MergeOutcome(true, "merge-head", false, List.of(), null));
        when(repository.getDslAtCommit("merge-head")).thenReturn(REVIEWED);
        var result = service.merge("source", "target", "merge", context);
        assertThat(result.mergeCommitId()).isEqualTo("merge-head");
        verify(core).materialize(REVIEWED, "alice", context);
        verify(core, never()).materializeHead(anyString(), anyString(), any());
        verify(repository, never()).getDslAtHead(anyString());
        verify(factory, times(1)).resolveRepository(context);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"  "})
    void emptyCommitContentKeepsTheNoMaterializationWarning(String content) throws Exception {
        var service = service();
        when(repository.getDslAtCommit("reviewed-head")).thenReturn(content);
        var result = service.materialize("target", "reviewed-head", context);
        assertThat(result.commitId()).isEqualTo("reviewed-head");
        assertThat(result.projectsUpserted()).isZero();
        assertThat(result.warnings()).isEqualTo(1);
        verifyNoInteractions(core);
    }

    @Test
    void immutableReadFailureDoesNotMaterializeOrFallBackToTheBranch() throws Exception {
        var service = service();
        var failure = new java.io.IOException("snapshot unavailable");
        when(repository.getDslAtCommit("reviewed-head")).thenThrow(failure);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.materialize("target", "reviewed-head", context)).isSameAs(failure);
        verifyNoInteractions(core);
        verify(repository, never()).getDslAtHead(anyString());
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
