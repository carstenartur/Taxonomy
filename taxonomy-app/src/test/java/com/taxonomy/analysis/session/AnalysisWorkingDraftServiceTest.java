package com.taxonomy.analysis.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.taxonomy.analysis.session.AnalysisDraftDtos.SaveAnalysisDraftRequest;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisWorkingDraftServiceTest {

    @Mock
    private AnalysisWorkingDraftRepository repository;

    @Mock
    private WorkspaceManager workspaceManager;

    @Mock
    private SystemRepositoryService systemRepositoryService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AnalysisWorkingDraftService service;
    private UserWorkspace workspace;

    @BeforeEach
    void setUp() {
        service = new AnalysisWorkingDraftService(
                repository,
                workspaceManager,
                systemRepositoryService,
                objectMapper,
                50_000);
        workspace = new UserWorkspace();
        workspace.setWorkspaceId("ws-1");
        workspace.setUsername("Alice");
        workspace.setCurrentBranch("feature/architecture");
        workspace.setSourceRepositoryId("repo-1");
        when(workspaceManager.getWorkspaceById("ws-1")).thenReturn(workspace);
        lenient().when(systemRepositoryService.getPrimaryRepository())
                .thenReturn(primaryRepository());
    }

    @Test
    void createsOneExactTenantScopedDraft() {
        when(repository.findByScopeKeyAndUsername(any(), any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ObjectNode payload = objectMapper.createObjectNode()
                .put("businessText", "Provide a resilient command platform");
        var view = service.save("Alice", " ws-1 ",
                new SaveAnalysisDraftRequest(payload, null));

        ArgumentCaptor<AnalysisWorkingDraft> captor =
                ArgumentCaptor.forClass(AnalysisWorkingDraft.class);
        verify(repository).saveAndFlush(captor.capture());
        AnalysisWorkingDraft stored = captor.getValue();
        String expectedScope = PortfolioScope.key(
                "Alice",
                new WorkspaceContext(
                        "Alice", "ws-1", "feature/architecture", "repo-1"));

        assertThat(stored.getScopeKey()).isEqualTo(expectedScope);
        assertThat(stored.getUsername()).isEqualTo("alice");
        assertThat(stored.getWorkspaceId()).isEqualTo("ws-1");
        assertThat(view.payload().path("businessText").asText())
                .isEqualTo("Provide a resilient command platform");
        assertThat(view.version()).isZero();
    }

    @Test
    void refusesToOverwriteDraftWithoutTheObservedVersion() {
        AnalysisWorkingDraft existing = existingDraft();
        when(repository.findByScopeKeyAndUsername(any(), any()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.save(
                "Alice",
                "ws-1",
                new SaveAnalysisDraftRequest(
                        objectMapper.createObjectNode().put("businessText", "changed"),
                        null)))
                .isInstanceOf(AnalysisDraftConflictException.class)
                .hasMessageContaining("expected none")
                .hasMessageContaining("current 0");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updatesDraftWhenTheBrowserStillOwnsTheCurrentVersion() {
        AnalysisWorkingDraft existing = existingDraft();
        when(repository.findByScopeKeyAndUsername(any(), any()))
                .thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        var view = service.save(
                "Alice",
                "ws-1",
                new SaveAnalysisDraftRequest(
                        objectMapper.createObjectNode().put("businessText", "changed"),
                        0L));

        assertThat(view.payload().path("businessText").asText()).isEqualTo("changed");
        verify(repository).saveAndFlush(existing);
    }

    @Test
    void rejectsAWorkspaceOwnedByAnotherUser() {
        workspace.setUsername("Bob");

        assertThatThrownBy(() -> service.read("Alice", "ws-1"))
                .isInstanceOf(AccessDeniedException.class);

        verify(repository, never()).findByScopeKeyAndUsername(any(), any());
    }

    @Test
    void deleteRequiresTheVersionSeenByTheCallingTab() {
        AnalysisWorkingDraft existing = existingDraft();
        when(repository.findByScopeKeyAndUsername(any(), any()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.delete("Alice", "ws-1", 1L))
                .isInstanceOf(AnalysisDraftConflictException.class)
                .hasMessageContaining("expected 1")
                .hasMessageContaining("current 0");

        verify(repository, never()).delete(any());
    }

    private AnalysisWorkingDraft existingDraft() {
        String scope = PortfolioScope.key(
                "Alice",
                new WorkspaceContext(
                        "Alice", "ws-1", "feature/architecture", "repo-1"));
        return new AnalysisWorkingDraft(
                scope,
                "ws-1",
                "alice",
                "{\"businessText\":\"original\"}",
                java.time.Instant.parse("2026-08-19T20:00:00Z"));
    }

    private static SystemRepository primaryRepository() {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId("repo-1");
        repository.setDefaultBranch("main");
        return repository;
    }
}
