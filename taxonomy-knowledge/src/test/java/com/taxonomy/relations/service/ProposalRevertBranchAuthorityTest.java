package com.taxonomy.relations.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RemoveRelation;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.DslWorkspaceVersionAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProposalRevertBranchAuthorityTest {
    @Test
    void inheritedAcceptedReviewCannotReopenTheCentralInboxOnAnotherBranch() throws Exception {
        var primary = RepositoryContext.centralWrite("repository-a", "main", "reviewer");
        var draft = RepositoryContext.centralWrite("repository-a", "draft", "reviewer");
        var row = new ProposalReviewStateStore.ProposalSnapshot(17L, "APP-1", "SVC-1", RelationType.USES,
                ProposalStatus.ACCEPTED, 0.8, "reviewed", "generated");
        var store = mock(ProposalReviewStateStore.class);
        when(store.require(17L, draft)).thenReturn(row);
        var copied = "relation APP-1 USES SVC-1 {\n  status: accepted;\n  provenance: proposal-review;\n"
                + "  x-proposal-id: 17;\n  x-proposal-review-branch: main;\n  x-proposal-review-scope: "
                + ProposalBranchAdoptionService.reviewOrigin(primary).get("x-proposal-review-scope") + ";\n}\n";
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var git = repositories.resolveRepository(primary);
            String initial = git.commitDsl("main", copied, "reviewer", "Accept proposal");
            assertThat(git.createBranch("draft", "main")).isEqualTo(initial);
            var commands = new ArchitectureRelationGitCommandService(new DslWorkspaceVersionAdapter(repositories));
            var projection = mock(RelationDecisionProjectionService.class);
            var mutation = new GitAuthoritativeRelationMutationService(commands, projection, mock(), mock(), mock());
            var service = new GitAuthoritativeProposalReviewService(store, mutation);
            assertThatThrownBy(() -> service.revert(17L, draft, initial, new CommandMetadata("revert-17")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(git.getHeadCommit("draft")).isEqualTo(initial);
            assertThat(git.getHeadCommit("main")).isEqualTo(initial);
            verifyNoInteractions(projection);
            verify(store, never()).transition(any(), any(), any(), any());
            var originalUndo = new RemoveRelation(new RelationIdentity("APP-1", "USES", "SVC-1"),
                    new CommandMetadata("undo-on-main"), Map.of("x-proposal-id", "17", "status", "accepted",
                    "provenance", "proposal-review"), ProposalBranchAdoptionService.reviewOrigin(primary));
            assertThat(commands.execute(primary, initial, originalUndo).commitCreated()).isTrue();
            assertThat(git.getHeadCommit("draft")).isEqualTo(initial);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "rejected"})
    void ownedDecisionCanBeRemovedAtItsExpectedHeadWithoutChangingOtherSource(String status) throws Exception {
        var context = RepositoryContext.workspace("repository-a", "workspace-a", "main", "reviewer");
        String rest = "# Retain comments and formatting exactly.\nelement OTHER type Capability {\n  title: \"R&D\";\n}\n";
        String source = rest + "relation APP-1 USES SVC-1 {\n  status: " + status
                + ";\n  provenance: proposal-review;\n  x-proposal-id: 17;\n}\n";
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var git = repositories.resolveRepository(context);
            String before = git.commitDsl("main", rest, "editor", "Before the review");
            String reviewed = git.commitDsl("main", source, "reviewer", "Apply the review");
            var command = new RemoveRelation(new RelationIdentity("APP-1", "USES", "SVC-1"),
                    new CommandMetadata("revert-17"),
                    Map.of("x-proposal-id", "17", "status", status, "provenance", "proposal-review"));
            var commands = new ArchitectureRelationGitCommandService(new DslWorkspaceVersionAdapter(repositories));
            assertThatThrownBy(() -> commands.execute(context, before, command)).isInstanceOf(BranchHeadConflictException.class);
            assertThat(git.getHeadCommit("main")).isEqualTo(reviewed);
            var reverted = commands.execute(context, reviewed, command);
            assertThat(reverted.commitCreated()).isTrue();
            assertThat(reverted.previousHeadCommit()).isEqualTo(reviewed);
            assertThat(git.getDslAtCommit(reverted.authoritativeCommitId())).isEqualTo(rest);
            assertThat(git.getDslAtCommit(reviewed)).isEqualTo(source);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "status: accepted;", "status: rejected;\n  provenance: proposal-review;\n  x-proposal-id: 17;",
            "status: accepted;\n  provenance: manual;\n  x-proposal-id: 17;",
            "status: accepted;\n  provenance: proposal-review;\n  x-proposal-id: 23;",
            "status: accepted;\n  provenance: proposal-review;\n  x-proposal-id: 17;\n  x-proposal-id: 23;",
            "status: accepted;\n  provenance: proposal-review;\n  filed {\n    x-proposal-id: 17;\n  }"})
    void undoOnlyChangesTheOwnedDecisionAtTheSelectedBranchHead(String otherDecision) throws Exception {
        var selected = RepositoryContext.workspace("repository-a", "workspace-a", "draft", "reviewer");
        var store = mock(ProposalReviewStateStore.class);
        var projection = mock(RelationDecisionProjectionService.class);
        var row = new ProposalReviewStateStore.ProposalSnapshot(17L, "APP-1", "SVC-1", RelationType.USES,
                ProposalStatus.ACCEPTED, 0.8, "reviewed", "generated");
        when(store.require(17L, selected)).thenReturn(row);
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var git = repositories.resolveRepository(selected);
            String reviewed = "relation APP-1 USES SVC-1 {\n  status: accepted;\n  provenance: proposal-review;\n  x-proposal-id: 17;\n}\n";
            String main = git.commitDsl("main", reviewed, "reviewer", "Accept proposal");
            String draftDsl = otherDecision.isEmpty() ? "# independent draft\n"
                    : "relation APP-1 USES SVC-1 {\n  " + otherDecision + "\n}\n";
            String draft = git.commitDsl("draft", draftDsl, "editor", "Independent branch state");
            var commands = new ArchitectureRelationGitCommandService(new DslWorkspaceVersionAdapter(repositories));
            var mutation = new GitAuthoritativeRelationMutationService(commands, projection, mock(), mock(), mock());
            var service = new GitAuthoritativeProposalReviewService(store, mutation);

            assertThatThrownBy(() -> service.revert(17L, selected, draft, new CommandMetadata("revert-17")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(git.getHeadCommit("draft")).isEqualTo(draft);
            assertThat(git.getDslAtHead("draft")).isEqualTo(draftDsl);
            assertThat(git.getHeadCommit("main")).isEqualTo(main);
            assertThat(git.getDslAtHead("main")).isEqualTo(reviewed);
            verifyNoInteractions(projection);
            verify(store, never()).transition(any(), any(), any(), any());
        }
    }
}
