package com.taxonomy.relations.service;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.DslWorkspacePublicationAdapter;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceActorAccess;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HypothesisLegacyRepositoryRoutingTest {

    @Mock
    private RelationHypothesisRepository hypothesisRepository;
    @Mock
    private RelationEvidenceRepository evidenceRepository;
    @Mock
    private TaxonomyRelationService relationService;
    @Mock
    private TaxonomyNodeRepository nodeRepository;
    @Mock
    private DslGitRepositoryFactory repositoryFactory;
    @Mock
    private SystemRepositoryService systemRepositoryService;
    @Mock
    private UserWorkspaceRepository userWorkspaceRepository;
    @Mock
    private SystemRepository selectedRepository;
    @Mock
    private SystemRepository primaryRepository;
    @Mock
    private UserWorkspace workspace;
    @Mock(answer = org.mockito.Answers.CALLS_REAL_METHODS)
    private WorkspaceActorAccess actors;

    private HypothesisService service;

    @BeforeEach
    void setUp() {
        service = new HypothesisService(
                hypothesisRepository,
                evidenceRepository,
                relationService,
                nodeRepository,
                new DslWorkspacePublicationAdapter(repositoryFactory),
                new com.taxonomy.workspace.service.LegacyWorkspaceRepositoryContextResolver(
                        systemRepositoryService, userWorkspaceRepository, actors));
    }

    @Test
    void centralCompatibilityContextKeepsTheExplicitSelectedRepository() {
        when(systemRepositoryService.getRepository("repo-b"))
                .thenReturn(selectedRepository);
        when(selectedRepository.getRepositoryId()).thenReturn("repo-b");
        when(hypothesisRepository.findCentralByRepositoryAndBranch("repo-b", "main"))
                .thenReturn(List.of());

        assertThat(service.findAll(new WorkspaceContext(
                "alice", null, "main", "repo-b")))
                .isEmpty();

        verify(systemRepositoryService).getRepository("repo-b");
        verify(systemRepositoryService, never()).getPrimaryRepository();
        verify(hypothesisRepository).findCentralByRepositoryAndBranch("repo-b", "main");
    }

    @Test
    void workspaceCompatibilityContextRejectsARepositoryMismatch() {
        when(userWorkspaceRepository.findByWorkspaceId("ws-1"))
                .thenReturn(Optional.of(workspace));
        when(workspace.getUsername()).thenReturn("alice");
        when(workspace.getSourceRepositoryId()).thenReturn("repo-a");

        assertThatThrownBy(() -> service.findAll(new WorkspaceContext(
                "alice", "ws-1", "draft", "repo-b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Workspace repository does not match the selected repository");

        verifyNoInteractions(hypothesisRepository);
    }

    @Test
    void boundWorkspaceHypothesesRequireTheirVerifiedOwner() {
        var row = new UserWorkspace();
        row.setUsername("alice");
        row.setSourceRepositoryId("repo-a");
        var original = java.util.UUID.randomUUID();
        row.assignOwnerPrincipal(original);
        when(userWorkspaceRepository.findByWorkspaceId("ws-1")).thenReturn(Optional.of(row));
        var selection = new WorkspaceContext("alice", "ws-1", "draft", "repo-a");
        org.mockito.Mockito.doReturn(Optional.of(java.util.UUID.randomUUID()), Optional.of(original))
                .when(actors).currentOwnerPrincipal("alice");

        assertThatThrownBy(() -> service.findAll(selection))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.findAll(new WorkspaceContext("system", "ws-1", "draft", "repo-a")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(hypothesisRepository);

        assertThat(service.findAll(selection)).isEmpty();
        verify(hypothesisRepository).findVisibleByRepositoryAndWorkspaceAndBranch("repo-a", "ws-1", "draft");
    }

    @Test
    void centralSystemSelectionStillReadsThePrimaryDraftWithoutPersonalOwnership() {
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(primaryRepository);
        when(primaryRepository.getRepositoryId()).thenReturn("primary");
        assertThat(service.findAll(WorkspaceContext.SHARED)).isEmpty();
        verify(hypothesisRepository).findCentralByRepositoryAndBranch("primary", "draft");
        verifyNoInteractions(userWorkspaceRepository, actors);
    }

    @Test
    void explicitLegacySentinelRetainsPrimaryRepositoryCompatibility() {
        when(systemRepositoryService.getPrimaryRepository())
                .thenReturn(primaryRepository);
        when(primaryRepository.getRepositoryId()).thenReturn("primary-repo");
        when(hypothesisRepository.findCentralByRepositoryAndBranch("primary-repo", "draft"))
                .thenReturn(List.of());

        assertThat(service.findAll(new WorkspaceContext(
                "alice", null, "draft")))
                .isEmpty();

        verify(systemRepositoryService).getPrimaryRepository();
        verify(systemRepositoryService, never()).getRepository("legacy-primary");
        verify(hypothesisRepository).findCentralByRepositoryAndBranch("primary-repo", "draft");
    }
}
