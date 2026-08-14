package com.taxonomy.relations.controller;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ReviewAction;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ReviewResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionOutcome;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionResult;
import com.taxonomy.relations.service.RelationProposalService;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProposalApiControllerRepositoryScopeTest {

    private static final String HEAD_A = "a".repeat(40);
    private static final String HEAD_B = "b".repeat(40);
    private static final String HEAD_C = "c".repeat(40);

    private RelationProposalService proposalService;
    private GitAuthoritativeProposalReviewService reviewService;
    private RelationBranchProjectionReadinessService readinessService;
    private WorkspaceResolver workspaceResolver;
    private SystemRepositoryService repositoryService;
    private RepositoryMembershipService membershipService;
    private ProposalApiController controller;

    @BeforeEach
    void setUp() {
        proposalService = mock(RelationProposalService.class);
        reviewService = mock(GitAuthoritativeProposalReviewService.class);
        readinessService = mock(RelationBranchProjectionReadinessService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        repositoryService = mock(SystemRepositoryService.class);
        membershipService = mock(RepositoryMembershipService.class);
        controller = new ProposalApiController(
                proposalService,
                reviewService,
                readinessService,
                workspaceResolver,
                repositoryService,
                membershipService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsUseTheExactResolvedRepositoryContext() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-b", "main", "alice");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(context);
        when(proposalService.getAllProposalsInContext(context)).thenReturn(List.of());

        ResponseEntity<List<RelationProposalDto>> response = controller.getAllProposals();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(proposalService).getAllProposalsInContext(context);
        verify(proposalService, never()).getAllProposals();
    }

    @Test
    void centralReadContextRejectsNonMaintainerProposalGeneration() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "main", "reader");
        SystemRepository repository = repository("repo-a");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(context);
        when(repositoryService.getRepository("repo-a")).thenReturn(repository);
        when(membershipService.canMaintain(repository, "reader")).thenReturn(false);

        ResponseEntity<List<RelationProposalDto>> response = controller.proposeRelations(
                validProposalBody());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(proposalService, never()).proposeRelationsInContext(
                any(), any(), anyInt(), any());
    }

    @Test
    void repositoryMaintainerReceivesAnExplicitCentralWriteContext() {
        RepositoryContext readContext = RepositoryContext.centralRead(
                "repo-a", "main", "maintainer");
        SystemRepository repository = repository("repo-a");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(readContext);
        when(repositoryService.getRepository("repo-a")).thenReturn(repository);
        when(membershipService.canMaintain(repository, "maintainer")).thenReturn(true);
        when(proposalService.proposeRelationsInContext(
                        any(), any(), anyInt(), any()))
                .thenReturn(List.of());

        ResponseEntity<List<RelationProposalDto>> response = controller.proposeRelations(
                validProposalBody());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<RepositoryContext> contextCaptor =
                ArgumentCaptor.forClass(RepositoryContext.class);
        verify(proposalService).proposeRelationsInContext(
                eq("BP"),
                eq(RelationType.RELATED_TO),
                eq(3),
                contextCaptor.capture());
        assertThat(contextCaptor.getValue().repositoryId()).isEqualTo("repo-a");
        assertThat(contextCaptor.getValue().workspaceId()).isNull();
        assertThat(contextCaptor.getValue().scope()).isEqualTo(RepositoryScope.CENTRAL_WRITE);
        assertThat(contextCaptor.getValue().username()).isEqualTo("maintainer");
    }

    @Test
    void globalAdminOverrideStillTargetsOnlyTheSelectedRepository() {
        authenticateAdmin("admin");
        RepositoryContext readContext = RepositoryContext.centralRead(
                "repo-b", "draft", "admin");
        SystemRepository repository = repository("repo-b");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(readContext);
        when(repositoryService.getRepository("repo-b")).thenReturn(repository);
        when(proposalService.proposeRelationsInContext(
                        any(), any(), anyInt(), any()))
                .thenReturn(List.of());

        ResponseEntity<List<RelationProposalDto>> response = controller.proposeRelations(
                validProposalBody());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<RepositoryContext> contextCaptor =
                ArgumentCaptor.forClass(RepositoryContext.class);
        verify(proposalService).proposeRelationsInContext(
                any(), any(), anyInt(), contextCaptor.capture());
        assertThat(contextCaptor.getValue().repositoryId()).isEqualTo("repo-b");
        assertThat(contextCaptor.getValue().branch()).isEqualTo("draft");
        assertThat(contextCaptor.getValue().scope()).isEqualTo(RepositoryScope.CENTRAL_WRITE);
        verify(membershipService, never()).canMaintain(repository, "admin");
    }

    @Test
    void workspaceReviewCommitsGitFirstAndReturnsAuthorityEtag() throws Exception {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-b", "workspace-b1", "feature/b1", "alice");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(context);
        when(readinessService.inspect(context)).thenReturn(ready(HEAD_A));
        when(reviewService.accept(
                eq(42L), eq(context), eq(HEAD_A), any(CommandMetadata.class)))
                .thenReturn(reviewResult(
                        context,
                        42L,
                        ReviewAction.ACCEPT,
                        ProposalStatus.ACCEPTED,
                        HEAD_A,
                        HEAD_B,
                        ChangeKind.ADDED,
                        true));

        ResponseEntity<Map<String, Object>> response = controller.acceptProposal(42L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG))
                .isEqualTo('"' + HEAD_B + '"');
        assertThat(response.getBody())
                .containsEntry("authoritativeCommitId", HEAD_B)
                .containsEntry("projectionStatus", "PROJECTED");
        ArgumentCaptor<CommandMetadata> metadataCaptor =
                ArgumentCaptor.forClass(CommandMetadata.class);
        verify(reviewService).accept(
                eq(42L), eq(context), eq(HEAD_A), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue().causationId())
                .isEqualTo("legacy-proposal-accept-42-" + HEAD_A);
        verify(repositoryService, never()).getRepository(any());
        verify(membershipService, never()).canMaintain(any(), any());
    }

    @Test
    void centralReaderCannotUseReviewEndpointToProbeAProposalIdentifier() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "main", "reader");
        SystemRepository repository = repository("repo-a");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(context);
        when(repositoryService.getRepository("repo-a")).thenReturn(repository);
        when(membershipService.canMaintain(repository, "reader")).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.acceptProposal(42L);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(reviewService, never()).accept(any(), any(), any(), any());
    }

    @Test
    void bulkReviewFeedsEachSuccessfulCommitIntoTheNextExpectedHead()
            throws Exception {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a1", "draft", "alice");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(context);
        when(readinessService.inspect(context)).thenReturn(ready(HEAD_A));
        when(reviewService.accept(
                eq(10L), eq(context), eq(HEAD_A), any(CommandMetadata.class)))
                .thenReturn(reviewResult(
                        context,
                        10L,
                        ReviewAction.ACCEPT,
                        ProposalStatus.ACCEPTED,
                        HEAD_A,
                        HEAD_B,
                        ChangeKind.ADDED,
                        true));
        when(reviewService.accept(
                eq(11L), eq(context), eq(HEAD_B), any(CommandMetadata.class)))
                .thenReturn(reviewResult(
                        context,
                        11L,
                        ReviewAction.ACCEPT,
                        ProposalStatus.ACCEPTED,
                        HEAD_B,
                        HEAD_C,
                        ChangeKind.UPDATED,
                        true));

        ResponseEntity<Map<String, Object>> response = controller.bulkAction(Map.of(
                "ids", List.of(10, 11),
                "action", "ACCEPT"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG))
                .isEqualTo('"' + HEAD_C + '"');
        assertThat(response.getBody())
                .containsEntry("projected", 2)
                .containsEntry("failed", 0)
                .containsEntry("authoritativeCommitId", HEAD_C)
                .containsEntry("complete", true);
        verify(reviewService).accept(
                eq(10L), eq(context), eq(HEAD_A), any(CommandMetadata.class));
        verify(reviewService).accept(
                eq(11L), eq(context), eq(HEAD_B), any(CommandMetadata.class));
    }

    private static Readiness ready(String head) {
        return new Readiness(
                ReadinessState.READY,
                head,
                head,
                List.of());
    }

    private static ReviewResult reviewResult(
            RepositoryContext context,
            long proposalId,
            ReviewAction action,
            ProposalStatus status,
            String previousHead,
            String authoritativeHead,
            ChangeKind changeKind,
            boolean relationPresent) {
        CommandResult authority = new CommandResult(
                context.repositoryId(),
                context.workspaceId(),
                context.branch(),
                context.scope(),
                previousHead,
                authoritativeHead,
                changeKind,
                changeKind != ChangeKind.UNCHANGED,
                "test-causation-" + proposalId);
        ProjectionResult projection = new ProjectionResult(
                ProjectionOutcome.CREATED,
                authoritativeHead,
                relationPresent);
        return new ReviewResult(
                proposalId,
                action,
                status,
                new MutationResult(authority, projection));
    }

    private static Map<String, String> validProposalBody() {
        return Map.of(
                "sourceCode", "BP",
                "relationType", "RELATED_TO",
                "limit", "3");
    }

    private static SystemRepository repository(String repositoryId) {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId(repositoryId);
        return repository;
    }

    private static void authenticateAdmin(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username,
                        "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
