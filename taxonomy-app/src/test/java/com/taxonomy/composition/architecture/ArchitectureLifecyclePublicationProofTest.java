package com.taxonomy.composition.architecture;

import com.taxonomy.versioning.service.CommitIndexService;
import com.taxonomy.versioning.service.ConflictDetectionService;
import com.taxonomy.versioning.service.DslOperationsFacade;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.RepositoryStateGuard;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A durable command receipt proves its original publication only while Git retains that version. */
@SpringBootTest
@WithMockUser(username = "alice", roles = "ADMIN")
class ArchitectureLifecyclePublicationProofTest extends LifecycleTestSupport {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replayRequiresTheOriginalCheckpointToRemainReachableOrAnExplicitReconciliationProof(boolean undoLast) throws Exception {
        var context = workspace("publication-proof");
        var repository = git.resolveRepository(context);
        String base = repository.getHeadCommit("draft");
        var originalRequest = newRequirement(context, null, "PUBLICATION", "REQ-1", "Original published decision A");
        var original = changes.save(context, originalRequest);
        assertThat(original.publication().state()).isEqualTo("CHECKPOINTED");
        String originalCommit = original.publication().checkpoint().commitId();
        assertThat(originalCommit).isNotEqualTo(base);

        if (undoLast) {
            var resolver = mock(WorkspaceResolver.class);
            when(resolver.resolveCurrentUsername()).thenReturn("alice");
            when(resolver.resolveCurrentRepositoryContext()).thenReturn(context);
            var versions = new DslOperationsFacade(git, mock(CommitIndexService.class), mock(ConflictDetectionService.class),
                    mock(RepositoryStateGuard.class), mock(RepositoryStateService.class), resolver, editor);
            assertThat(versions.undoLast("draft")).isEqualTo(base);
            assertThat(repository.isAncestor(originalCommit, repository.getHeadCommit("draft"))).isFalse();
            rejected("NOT_FOUND", () -> editor.read(context, originalCommit));
        } else {
            var later = changes.save(context, change(context, original.projectId(), original.requirementId(), "Later published decision B", null));
            assertThat(later.publication().state()).isEqualTo("CHECKPOINTED");
            assertThat(repository.isAncestor(originalCommit, repository.getHeadCommit("draft"))).isTrue();
            assertThat(editor.read(context, originalCommit).dsl()).contains("Original published decision A");
        }

        var retained = capture(context);
        var retainedRequirement = projects.getRequirement(original.projectId(), original.requirementId(), "alice", scope(context));
        var retainedVersions = projects.listRequirementVersions(original.projectId(), original.requirementId(), "alice", scope(context));
        var replay = changes.save(context, originalRequest);
        assertThat(replay.requirement().currentVersion()).isEqualTo(original.requirement().currentVersion());
        if (undoLast) {
            assertThat(replay.publication().state()).isEqualTo("RECONCILIATION_REQUIRED");
            assertThat(replay.publication().checkpoint()).isNull();
            assertThat(replay.publication().code()).isEqualTo("VERSION_CHANGED");
        } else {
            assertThat(replay.publication().state()).isEqualTo("CHECKPOINTED");
            assertThat(replay.publication().checkpoint().commitId()).isEqualTo(originalCommit);
            assertThat(replay.publication().context()).isEqualTo(original.publication().context());
        }
        assertUnchanged(context, retained);
        assertThat(projects.getRequirement(original.projectId(), original.requirementId(), "alice", scope(context))).isEqualTo(retainedRequirement);
        assertThat(projects.listRequirementVersions(original.projectId(), original.requirementId(), "alice", scope(context))).isEqualTo(retainedVersions);
    }
}
