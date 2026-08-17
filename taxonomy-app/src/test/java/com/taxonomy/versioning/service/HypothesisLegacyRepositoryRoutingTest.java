package com.taxonomy.versioning.service;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.SystemRepositoryService;
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

    private HypothesisService service;

    @BeforeEach
    void setUp() {
        service = new HypothesisService(
                hypothesisRepository,
                evidenceRepository,
                relationService,
                nodeRepository,
                repositoryFactory,
                systemRepositoryService,
                userWorkspaceRepository);
    }

    @Test
    void centralCompatibilityContextKeepsTheExplicitSelectedRepository() {
        when(systemRepositoryService.getRepository("repo-b"))
                .thenReturn(selectedRepository);
        when(selectedRepository.getRepositoryId()).thenReturn("repo-b");
        when(hypothesisRepository.findCentralByRepository("repo-b"))
                .thenReturn(List.of());

        assertThat(service.findAll(new WorkspaceContext(
                "alice", null, "main", "repo-b")))
                .isEmpty();

        verify(systemRepositoryService).getRepository("repo-b");
        verify(systemRepositoryService, never()).getPrimaryRepository();
        verify(hypothesisRepository).findCentralByRepository("repo-b");
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
    void explicitLegacySentinelRetainsPrimaryRepositoryCompatibility() {
        when(systemRepositoryService.getPrimaryRepository())
                .thenReturn(primaryRepository);
        when(primaryRepository.getRepositoryId()).thenReturn("primary-repo");
        when(hypothesisRepository.findCentralByRepository("primary-repo"))
                .thenReturn(List.of());

        assertThat(service.findAll(new WorkspaceContext(
                "alice", null, "draft")))
                .isEmpty();

        verify(systemRepositoryService).getPrimaryRepository();
        verify(systemRepositoryService, never()).getRepository("legacy-primary");
        verify(hypothesisRepository).findCentralByRepository("primary-repo");
    }
}
