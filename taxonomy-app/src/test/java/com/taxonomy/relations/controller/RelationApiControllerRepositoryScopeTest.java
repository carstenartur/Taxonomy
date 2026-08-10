package com.taxonomy.relations.controller;

import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationApiControllerRepositoryScopeTest {

    private TaxonomyRelationService relationService;
    private WorkspaceResolver workspaceResolver;
    private SystemRepositoryService repositoryService;
    private RepositoryMembershipService membershipService;
    private RelationApiController controller;

    @BeforeEach
    void setUp() {
        relationService = mock(TaxonomyRelationService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        repositoryService = mock(SystemRepositoryService.class);
        membershipService = mock(RepositoryMembershipService.class);
        controller = new RelationApiController(
                relationService,
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
        when(relationService.getAllRelationsInContext(context)).thenReturn(List.of());

        ResponseEntity<List<TaxonomyRelationDto>> response =
                controller.getRelations(null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(relationService).getAllRelationsInContext(context);
        verify(relationService, never()).getAllRelations(any(String.class));
    }

    @Test
    void centralReadContextRejectsNonMaintainerWrites() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "main", "reader");
        SystemRepository repository = repository("repo-a");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(context);
        when(repositoryService.getRepository("repo-a")).thenReturn(repository);
        when(membershipService.canMaintain(repository, "reader")).thenReturn(false);

        ResponseEntity<TaxonomyRelationDto> response = controller.createRelation(
                validRelationBody());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(relationService, never()).createRelationInContext(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void repositoryMaintainerReceivesAnExplicitCentralWriteContext() {
        RepositoryContext readContext = RepositoryContext.centralRead(
                "repo-a", "main", "maintainer");
        SystemRepository repository = repository("repo-a");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(readContext);
        when(repositoryService.getRepository("repo-a")).thenReturn(repository);
        when(membershipService.canMaintain(repository, "maintainer")).thenReturn(true);
        when(relationService.createRelationInContext(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(new TaxonomyRelationDto());

        ResponseEntity<TaxonomyRelationDto> response = controller.createRelation(
                validRelationBody());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<RepositoryContext> contextCaptor =
                ArgumentCaptor.forClass(RepositoryContext.class);
        verify(relationService).createRelationInContext(
                org.mockito.ArgumentMatchers.eq("BP"),
                org.mockito.ArgumentMatchers.eq("CP"),
                org.mockito.ArgumentMatchers.eq(RelationType.SUPPORTS),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                contextCaptor.capture());
        assertThat(contextCaptor.getValue().repositoryId()).isEqualTo("repo-a");
        assertThat(contextCaptor.getValue().workspaceId()).isNull();
        assertThat(contextCaptor.getValue().scope()).isEqualTo(RepositoryScope.CENTRAL_WRITE);
        assertThat(contextCaptor.getValue().username()).isEqualTo("maintainer");
    }

    @Test
    void globalAdminCompatibilityOverrideStillTargetsOnlyTheSelectedRepository() {
        authenticateAdmin("admin");
        RepositoryContext readContext = RepositoryContext.centralRead(
                "repo-b", "draft", "admin");
        SystemRepository repository = repository("repo-b");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(readContext);
        when(repositoryService.getRepository("repo-b")).thenReturn(repository);
        when(relationService.createRelationInContext(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(new TaxonomyRelationDto());

        ResponseEntity<TaxonomyRelationDto> response = controller.createRelation(
                validRelationBody());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<RepositoryContext> contextCaptor =
                ArgumentCaptor.forClass(RepositoryContext.class);
        verify(relationService).createRelationInContext(
                any(), any(), any(), any(), any(), contextCaptor.capture());
        assertThat(contextCaptor.getValue().repositoryId()).isEqualTo("repo-b");
        assertThat(contextCaptor.getValue().branch()).isEqualTo("draft");
        assertThat(contextCaptor.getValue().scope()).isEqualTo(RepositoryScope.CENTRAL_WRITE);
        verify(membershipService, never()).canMaintain(repository, "admin");
    }

    @Test
    void workspaceWritePreservesTheResolvedRepositoryAndWorkspace() {
        RepositoryContext workspaceContext = RepositoryContext.workspace(
                "repo-b", "workspace-b1", "feature/b1", "alice");
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(workspaceContext);
        when(relationService.createRelationInContext(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(new TaxonomyRelationDto());

        ResponseEntity<TaxonomyRelationDto> response = controller.createRelation(
                validRelationBody());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(relationService).createRelationInContext(
                "BP",
                "CP",
                RelationType.SUPPORTS,
                null,
                null,
                workspaceContext);
        verify(repositoryService, never()).getRepository(any());
        verify(membershipService, never()).canMaintain(any(), any());
    }

    @Test
    void deleteCannotFallBackToAnUnscopedIdentifierLookup() {
        RepositoryContext workspaceContext = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(workspaceContext);

        ResponseEntity<Void> response = controller.deleteRelation(42L);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(relationService).deleteRelationInContext(42L, workspaceContext);
        verify(relationService, never()).deleteRelation(42L);
    }

    private static Map<String, String> validRelationBody() {
        return Map.of(
                "sourceCode", "BP",
                "targetCode", "CP",
                "relationType", "SUPPORTS");
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
