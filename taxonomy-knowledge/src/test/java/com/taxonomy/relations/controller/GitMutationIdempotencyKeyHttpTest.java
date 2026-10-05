package com.taxonomy.relations.controller;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ReviewAction;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ReviewResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionOutcome;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionResult;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** External header limits apply before dispatching either architecture mutation API. */
class GitMutationIdempotencyKeyHttpTest {

    private static final String HEAD = "a".repeat(40);
    private static final String AUTHORITY = "b".repeat(40);
    private static final RepositoryContext CONTEXT = RepositoryContext.workspace(
            "repo-a", "workspace-a", "draft", "alice");

    private final GitAuthoritativeProposalReviewService reviews = mock(GitAuthoritativeProposalReviewService.class);
    private final GitAuthoritativeRelationMutationService mutations = mock(GitAuthoritativeRelationMutationService.class);
    private final List<CommandMetadata> dispatchedMetadata = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        WorkspaceResolver resolver = mock(WorkspaceResolver.class);
        SystemRepositoryService repositories = mock(SystemRepositoryService.class);
        RepositoryMembershipService memberships = mock(RepositoryMembershipService.class);
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(CONTEXT);
        when(reviews.accept(eq(42L), eq(CONTEXT), eq(HEAD), any()))
                .thenAnswer(invocation -> reviewed(ReviewAction.ACCEPT, invocation.getArgument(3)));
        when(reviews.reject(eq(42L), eq(CONTEXT), eq(HEAD), any()))
                .thenAnswer(invocation -> reviewed(ReviewAction.REJECT, invocation.getArgument(3)));
        when(reviews.revert(eq(42L), eq(CONTEXT), eq(HEAD), any()))
                .thenAnswer(invocation -> reviewed(ReviewAction.REVERT, invocation.getArgument(3)));
        when(mutations.upsert(eq(CONTEXT), eq(HEAD), any(), any()))
                .thenAnswer(invocation -> mutated(invocation.getArgument(3)));
        when(mutations.remove(eq(CONTEXT), eq(HEAD), any(), any()))
                .thenAnswer(invocation -> mutated(invocation.getArgument(3)));
        mvc = standaloneSetup(
                new GitProposalReviewApiController(reviews, resolver, repositories, memberships),
                new GitRelationCommandApiController(mutations, resolver, repositories, memberships))
                .build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    static Stream<Arguments> mutationRoutes() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/architecture/proposals/42/accept"),
                Arguments.of(HttpMethod.POST, "/api/architecture/proposals/42/reject"),
                Arguments.of(HttpMethod.POST, "/api/architecture/proposals/42/revert"),
                Arguments.of(HttpMethod.PUT, "/api/architecture/relations/BP/SUPPORTS/CP"),
                Arguments.of(HttpMethod.DELETE, "/api/architecture/relations/BP/SUPPORTS/CP"));
    }

    @ParameterizedTest
    @MethodSource("mutationRoutes")
    void oversizedExternalKeyCannotReachAnyMutationRoute(HttpMethod method, String endpoint) {
        assertAll(
                () -> mvc.perform(request(method, endpoint)
                                .header(HttpHeaders.IF_MATCH, '"' + HEAD + '"')
                                .header("Idempotency-Key", "a".repeat(256)))
                        .andExpect(status().isBadRequest()),
                () -> verifyNoInteractions(reviews, mutations),
                () -> assertThat(dispatchedMetadata).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("mutationRoutes")
    void maximumExternalKeyIsPreservedThroughEveryMutationRoute(HttpMethod method, String endpoint)
            throws Exception {
        String key = "Key-" + "a".repeat(124);
        mvc.perform(request(method, endpoint)
                        .header(HttpHeaders.IF_MATCH, '"' + HEAD + '"')
                        .header("Idempotency-Key", " " + key + " "))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, '"' + AUTHORITY + '"'));

        assertThat(dispatchedMetadata).containsExactly(new CommandMetadata(key));
    }

    private ReviewResult reviewed(ReviewAction action, CommandMetadata metadata) {
        ProposalStatus status = switch (action) {
            case ACCEPT -> ProposalStatus.ACCEPTED;
            case REJECT -> ProposalStatus.REJECTED;
            case REVERT -> ProposalStatus.PENDING;
        };
        return new ReviewResult(42L, action, status, mutated(metadata));
    }

    private MutationResult mutated(CommandMetadata metadata) {
        dispatchedMetadata.add(metadata);
        return new MutationResult(
                new CommandResult(CONTEXT.repositoryId(), CONTEXT.workspaceId(), CONTEXT.branch(),
                        CONTEXT.scope(), HEAD, AUTHORITY, ChangeKind.UPDATED, true, metadata.causationId()),
                new ProjectionResult(ProjectionOutcome.UPDATED, AUTHORITY, true));
    }
}
