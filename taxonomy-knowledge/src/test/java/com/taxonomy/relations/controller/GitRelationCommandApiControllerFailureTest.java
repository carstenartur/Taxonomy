package com.taxonomy.relations.controller;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.ReadOnlyRepositoryContextException;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.ProjectionPendingException;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionOutcome;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionResult;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GitRelationCommandApiControllerFailureTest {
    private static final String PREVIOUS = "a".repeat(40);
    private static final String CURRENT = "b".repeat(40);
    private final GitAuthoritativeRelationMutationService mutation = mock(GitAuthoritativeRelationMutationService.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final SystemRepositoryService repositories = mock(SystemRepositoryService.class);
    private final RepositoryMembershipService membership = mock(RepositoryMembershipService.class);
    private final GitRelationCommandApiController controller =
            new GitRelationCommandApiController(mutation, resolver, repositories, membership);
    private final RepositoryContext workspace = RepositoryContext.workspace("repo-a", "workspace-a", "draft", "alice");

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(workspace);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new ReadOnlyRepositoryContextException("read only"), 403),
                Arguments.of(new IllegalArgumentException("invalid command"), 400),
                Arguments.of(new IllegalStateException("conflicting command"), 409),
                Arguments.of(new IOException("Git unavailable"), 503));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void bothMutationOperationsTranslatePortFailuresWithoutClaimingACommit(Exception error, int status)
            throws Exception {
        when(mutation.upsert(eq(workspace), eq(PREVIOUS), any(), any())).thenThrow(error);
        when(mutation.remove(eq(workspace), eq(PREVIOUS), any(), any())).thenThrow(error);
        for (boolean remove : new boolean[] {false, true}) {
            var response = mutate(remove);
            assertThat(response.getStatusCode().value()).isEqualTo(status);
            assertThat(response.getHeaders().getETag()).isNull();
            assertThat(response.getBody()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictReturnsActualHeadOrItsAbsenceForBothOperations(boolean branchExists) throws Exception {
        String actual = branchExists ? CURRENT : null;
        var conflict = new BranchHeadConflictException("draft", PREVIOUS, actual, "Head changed");
        when(mutation.upsert(eq(workspace), eq(PREVIOUS), any(), any())).thenThrow(conflict);
        when(mutation.remove(eq(workspace), eq(PREVIOUS), any(), any())).thenThrow(conflict);
        for (boolean remove : new boolean[] {false, true}) {
            var response = mutate(remove);
            assertThat(response.getStatusCode().value()).isEqualTo(412);
            assertThat(response.getHeaders().getETag()).isEqualTo(branchExists ? '"' + CURRENT + '"' : null);
            assertThat(response.getBody().projectionStatus()).isEqualTo("PRECONDITION_FAILED");
            assertThat(response.getBody().expectedHeadCommit()).isEqualTo(PREVIOUS);
            assertThat(response.getBody().actualHeadCommit()).isEqualTo(actual);
        }
    }

    @Test
    void upsertRetainsCommittedAuthorityWhenProjectionFails() throws Exception {
        when(mutation.upsert(eq(workspace), eq(PREVIOUS), any(), any()))
                .thenThrow(new ProjectionPendingException(authority(), new IllegalStateException("projection")));
        var response = mutate(false);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getETag()).isEqualTo('"' + CURRENT + '"');
        assertThat(response.getBody().projectionStatus()).isEqualTo("PENDING_REBUILD");
        assertThat(response.getBody().authoritativeCommitId()).isEqualTo(CURRENT);
    }

    @Test
    void removeRequiresPreconditionAndRejectsInvalidRelationTypeBeforeMutation() {
        assertThat(controller.remove("BP", "SUPPORTS", "CP", null, null, "request").getStatusCode().value())
                .isEqualTo(428);
        assertThat(controller.remove("BP", "INVALID", "CP", '"' + PREVIOUS + '"', null, "request")
                .getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(mutation);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void centralReadersCannotWriteWithOrWithoutAnAuthenticationObject(boolean authenticated) {
        var central = RepositoryContext.centralRead("repo-a", "draft", "alice");
        var repository = new SystemRepository();
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(central);
        when(repositories.getRepository("repo-a")).thenReturn(repository);
        if (authenticated) authenticate("ROLE_USER");
        assertThat(mutate(false).getStatusCode().value()).isEqualTo(403);
        assertThat(mutate(true).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(mutation);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void centralMaintainerOrAdminIsUpgradedOnlyForTheSelectedRepository(boolean admin) throws Exception {
        var central = RepositoryContext.centralRead("repo-a", "draft", "alice");
        var repository = new SystemRepository();
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(central);
        when(repositories.getRepository("repo-a")).thenReturn(repository);
        if (admin) authenticate("ROLE_ADMIN");
        else when(membership.canMaintain(repository, "alice")).thenReturn(true);
        var writeContext = RepositoryContext.centralWrite("repo-a", "draft", "alice");
        var authority = new CommandResult("repo-a", null, "draft", RepositoryScope.CENTRAL_WRITE,
                PREVIOUS, CURRENT, ChangeKind.REMOVED, true, "request");
        when(mutation.remove(eq(writeContext), eq(PREVIOUS), any(), any()))
                .thenReturn(new MutationResult(authority, new ProjectionResult(ProjectionOutcome.UPDATED, CURRENT, false)));
        var response = mutate(true);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().scope()).isEqualTo("CENTRAL_WRITE");
        assertThat(response.getBody().workspaceId()).isNull();
        assertThat(response.getBody().relationPresent()).isFalse();
        if (admin) verifyNoInteractions(membership);
    }

    private ResponseEntity<GitRelationCommandApiController.MutationResponse> mutate(boolean remove) {
        return remove
                ? controller.remove("BP", "SUPPORTS", "CP", '"' + PREVIOUS + '"', null, "request")
                : controller.upsert("BP", "SUPPORTS", "CP", '"' + PREVIOUS + '"', null, "request", null);
    }

    private static CommandResult authority() {
        return new CommandResult("repo-a", "workspace-a", "draft", RepositoryScope.WORKSPACE,
                PREVIOUS, CURRENT, ChangeKind.UPDATED, true, "request");
    }

    private static void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "alice", "unused", List.of(new SimpleGrantedAuthority(role))));
    }
}
