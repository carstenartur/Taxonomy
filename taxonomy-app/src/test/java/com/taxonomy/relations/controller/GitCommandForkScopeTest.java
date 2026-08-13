package com.taxonomy.relations.controller;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ReviewAction;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ReviewResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.RelationDecisionProjectionService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GitCommandForkScopeTest {

    private static final String PREVIOUS = "a".repeat(40);
    private static final String AUTHORITY = "b".repeat(40);

    @Test
    void relationCommandKeepsForkContextInsteadOfUpgradingToCentralWrite()
            throws Exception {
        RepositoryContext fork = fork();
        WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
        GitAuthoritativeRelationMutationService mutationService =
                mock(GitAuthoritativeRelationMutationService.class);
        SystemRepositoryService repositoryService =
                mock(SystemRepositoryService.class);
        RepositoryMembershipService membershipService =
                mock(RepositoryMembershipService.class);
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(fork);
        when(mutationService.upsert(
                eq(fork), eq(PREVIOUS), any(RelationDefinition.class),
                any(CommandMetadata.class)))
                .thenReturn(mutation());

        var response = new GitRelationCommandApiController(
                mutationService,
                workspaceResolver,
                repositoryService,
                membershipService).upsert(
                        "BP-1",
                        "SUPPORTS",
                        "CP-2",
                        '"' + PREVIOUS + '"',
                        null,
                        "fork-command",
                        null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().scope()).isEqualTo("FORK");
        verifyNoInteractions(repositoryService, membershipService);
    }

    @Test
    void proposalReviewKeepsForkContextInsteadOfUpgradingToCentralWrite()
            throws Exception {
        RepositoryContext fork = fork();
        WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
        GitAuthoritativeProposalReviewService reviewService =
                mock(GitAuthoritativeProposalReviewService.class);
        SystemRepositoryService repositoryService =
                mock(SystemRepositoryService.class);
        RepositoryMembershipService membershipService =
                mock(RepositoryMembershipService.class);
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(fork);
        when(reviewService.accept(
                eq(42L), eq(fork), eq(PREVIOUS),
                any(CommandMetadata.class)))
                .thenReturn(new ReviewResult(
                        42L,
                        ReviewAction.ACCEPT,
                        ProposalStatus.ACCEPTED,
                        mutation()));

        var response = new GitProposalReviewApiController(
                reviewService,
                workspaceResolver,
                repositoryService,
                membershipService).accept(
                        42L,
                        '"' + PREVIOUS + '"',
                        null,
                        "fork-review",
                        null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag())
                .isEqualTo('"' + AUTHORITY + '"');
        verifyNoInteractions(repositoryService, membershipService);
    }

    private static RepositoryContext fork() {
        return new RepositoryContext(
                "fork-a",
                null,
                "review",
                "alice",
                RepositoryScope.FORK);
    }

    private static MutationResult mutation() {
        return new MutationResult(
                new CommandResult(
                        "fork-a",
                        null,
                        "review",
                        RepositoryScope.FORK,
                        PREVIOUS,
                        AUTHORITY,
                        ChangeKind.UPDATED,
                        true,
                        "fork-command"),
                new RelationDecisionProjectionService.ProjectionResult(
                        RelationDecisionProjectionService.ProjectionOutcome.UPDATED,
                        AUTHORITY,
                        true));
    }
}
