package com.taxonomy.relations.controller;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.ProjectionPendingException;
import com.taxonomy.relations.service.RelationDecisionProjectionService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitRelationCommandApiControllerTest {

    private static final String PREVIOUS = "a".repeat(40);
    private static final String AUTHORITY = "b".repeat(40);

    @Mock
    private GitAuthoritativeRelationMutationService mutationService;

    @Mock
    private WorkspaceResolver workspaceResolver;

    @Mock
    private SystemRepositoryService repositoryService;

    @Mock
    private RepositoryMembershipService membershipService;

    private GitRelationCommandApiController controller;
    private RepositoryContext context;

    @BeforeEach
    void setUp() {
        controller = new GitRelationCommandApiController(
                mutationService,
                workspaceResolver,
                repositoryService,
                membershipService);
        context = new RepositoryContext(
                "repo-a",
                "workspace-a",
                "review",
                "alice",
                RepositoryScope.WORKSPACE);
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
    }

    @Test
    void returnsAuthoritativeCommitAsStrongEtagAfterProjection() throws Exception {
        MutationResult result = projectedResult();
        when(mutationService.upsert(
                eq(context), eq(PREVIOUS), any(RelationDefinition.class),
                any(CommandMetadata.class)))
                .thenReturn(result);

        var response = controller.upsert(
                "BP-1",
                "supports",
                "CP-2",
                '"' + PREVIOUS + '"',
                null,
                "request-17",
                new GitRelationCommandApiController.MutationBody(
                        "accepted",
                        0.9,
                        "manual-review",
                        Map.of("x-command-source", "http"),
                        "reviewed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag())
                .isEqualTo('"' + AUTHORITY + '"');
        assertThat(response.getBody())
                .extracting(
                        GitRelationCommandApiController.MutationResponse::authoritativeCommitId,
                        GitRelationCommandApiController.MutationResponse::projectionStatus,
                        GitRelationCommandApiController.MutationResponse::projectionOutcome,
                        GitRelationCommandApiController.MutationResponse::relationPresent)
                .containsExactly(
                        AUTHORITY,
                        "PROJECTED",
                        "UPDATED",
                        true);
    }

    @Test
    void requiresAnHttpPreconditionBeforeCallingMutationService() {
        var response = controller.upsert(
                "BP-1",
                "SUPPORTS",
                "CP-2",
                null,
                null,
                "request-17",
                null);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        verifyNoInteractions(mutationService);
    }

    @Test
    void mapsIfNoneMatchStarToNewBranchExpectation() throws Exception {
        CommandResult authority = new CommandResult(
                "repo-a",
                "workspace-a",
                "review",
                RepositoryScope.WORKSPACE,
                null,
                AUTHORITY,
                ChangeKind.ADDED,
                true,
                "request-18");
        RelationDecisionProjectionService.ProjectionResult projection =
                new RelationDecisionProjectionService.ProjectionResult(
                        RelationDecisionProjectionService.ProjectionOutcome.CREATED,
                        AUTHORITY,
                        true);
        when(mutationService.upsert(
                eq(context), isNull(), any(RelationDefinition.class),
                any(CommandMetadata.class)))
                .thenReturn(new MutationResult(authority, projection));

        var response = controller.upsert(
                "BP-1",
                "SUPPORTS",
                "CP-2",
                null,
                "*",
                "request-18",
                null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getETag())
                .isEqualTo('"' + AUTHORITY + '"');
    }

    @Test
    void returnsAcceptedWithAuthorityWhenProjectionNeedsRecovery()
            throws Exception {
        CommandResult authority = authority();
        when(mutationService.remove(
                eq(context), eq(PREVIOUS), any(), any(CommandMetadata.class)))
                .thenThrow(new ProjectionPendingException(
                        authority,
                        new IllegalStateException("database unavailable")));

        var response = controller.remove(
                "BP-1",
                "SUPPORTS",
                "CP-2",
                '"' + PREVIOUS + '"',
                null,
                "request-17");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getETag())
                .isEqualTo('"' + AUTHORITY + '"');
        assertThat(response.getBody().projectionStatus())
                .isEqualTo("PENDING_REBUILD");
        assertThat(response.getBody().authoritativeCommitId())
                .isEqualTo(AUTHORITY);
    }

    private static MutationResult projectedResult() {
        return new MutationResult(
                authority(),
                new RelationDecisionProjectionService.ProjectionResult(
                        RelationDecisionProjectionService.ProjectionOutcome.UPDATED,
                        AUTHORITY,
                        true));
    }

    private static CommandResult authority() {
        return new CommandResult(
                "repo-a",
                "workspace-a",
                "review",
                RepositoryScope.WORKSPACE,
                PREVIOUS,
                AUTHORITY,
                ChangeKind.UPDATED,
                true,
                "request-17");
    }
}
