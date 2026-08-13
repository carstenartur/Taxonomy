package com.taxonomy.relations.controller;

import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationProposalService;
import com.taxonomy.relations.service.RelationReviewService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProposalApiControllerRepositoryScopeTest {

    private RelationProposalService proposalService;
    private RelationReviewService reviewService;
    private WorkspaceResolver workspaceResolver;
    private SystemRepositoryService repositoryService;
    private RepositoryMembershipService membershipService;
    private ProposalApiController controller;

    @BeforeEach
    void setUp() {
        proposalService = mock(RelationProposalService.class);
        reviewService = mock(RelationReviewService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        repositoryService = mock(SystemRepositoryService.class);
        membershipService = mock(RepositoryMembershipService.class);
        controller = new ProposalApiController(
                proposalService,
                reviewService,
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
                org.mockito.ArgumentMatchers.eq("BP"),
                org.mockito.ArgumentMatchers.eq(RelationType.RELATED_TO),
                org.mockito.ArgumentMatchers.eq(3),
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
    void workspaceReviewPreservesRepositoryWorkspaceAndUser() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-b", "workspace-b1", "feature/b1", "alice");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(context);
        when(reviewService.acceptProposal(42L, context))
                .thenReturn(new TaxonomyRelationDto());

        ResponseEntity<TaxonomyRelationDto> response = controller.acceptProposal(42L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(reviewService).acceptProposal(42L, context);
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

        ResponseEntity<TaxonomyRelationDto> response = controller.acceptProposal(42L);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(reviewService, never()).acceptProposal(any(), any());
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
