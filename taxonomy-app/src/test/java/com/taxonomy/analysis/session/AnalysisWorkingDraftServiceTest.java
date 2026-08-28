package com.taxonomy.analysis.session;

import com.taxonomy.analysis.session.AnalysisDraftDtos.SaveAnalysisDraftRequest;
import com.taxonomy.analysis.session.AnalysisDraftDtos.ResetAnalysisDraftRequest;
import com.taxonomy.portfolio.model.PortfolioTenantIdentity;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
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
        lenient().when(systemRepositoryService.getRepository("repo-1"))
                .thenReturn(repository("repo-1", "main"));
        lenient().when(systemRepositoryService.getPrimaryRepository())
                .thenReturn(repository("primary-repo", "primary-main"));
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
        verify(systemRepositoryService).getRepository("repo-1");
        verify(systemRepositoryService, never()).getPrimaryRepository();
    }

    @Test
    void missingWorkspaceBranchUsesItsSourceRepositoryDefault() {
        workspace.setCurrentBranch(" ");
        when(systemRepositoryService.getRepository("repo-1"))
                .thenReturn(repository("repo-1", "source-main"));
        when(repository.findByScopeKeyAndUsername(any(), any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var view = service.save(
                "Alice",
                "ws-1",
                new SaveAnalysisDraftRequest(
                        objectMapper.createObjectNode().put("businessText", "changed"),
                        null));

        ArgumentCaptor<AnalysisWorkingDraft> captor =
                ArgumentCaptor.forClass(AnalysisWorkingDraft.class);
        verify(repository).saveAndFlush(captor.capture());
        PortfolioTenantIdentity identity = PortfolioTenantIdentity.parse(
                captor.getValue().getScopeKey());
        assertThat(identity.repositoryId()).isEqualTo("repo-1");
        assertThat(identity.branch()).isEqualTo("source-main");
        assertThat(view.branch()).isEqualTo("source-main");
        verify(systemRepositoryService, never()).getPrimaryRepository();
    }

    @Test
    void unavailableExplicitSourceRepositoryFailsClosedWithoutPrimaryFallback() {
        when(systemRepositoryService.getRepository("repo-1"))
                .thenThrow(new IllegalArgumentException("not found"));

        assertThatThrownBy(() -> service.read("Alice", "ws-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Workspace source repository is not available")
                .hasMessageContaining("repo-1");

        verify(systemRepositoryService, never()).getPrimaryRepository();
        verify(repository, never()).findByScopeKeyAndUsername(any(), any());
    }

    @Test
    void legacyWorkspaceWithoutSourceUsesThePrimaryRepository() {
        workspace.setSourceRepositoryId(" ");
        workspace.setCurrentBranch(" ");
        when(repository.findByScopeKeyAndUsername(any(), any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var view = service.save(
                "Alice",
                "ws-1",
                new SaveAnalysisDraftRequest(
                        objectMapper.createObjectNode().put("businessText", "legacy"),
                        null));

        ArgumentCaptor<AnalysisWorkingDraft> captor =
                ArgumentCaptor.forClass(AnalysisWorkingDraft.class);
        verify(repository).saveAndFlush(captor.capture());
        PortfolioTenantIdentity identity = PortfolioTenantIdentity.parse(
                captor.getValue().getScopeKey());
        assertThat(identity.repositoryId()).isEqualTo("primary-repo");
        assertThat(identity.branch()).isEqualTo("primary-main");
        assertThat(view.branch()).isEqualTo("primary-main");
        verify(systemRepositoryService).getPrimaryRepository();
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
    void resetCreatesAVersionedEmptyTombstoneWithoutAnObservedVersion() {
        when(repository.findByScopeKeyAndUsername(any(), any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ObjectNode options = objectMapper.createObjectNode()
                .put("provider", "OPENAI")
                .put("interactiveMode", true);

        var view = service.reset(
                "Alice", "ws-1", new ResetAnalysisDraftRequest(options));

        ArgumentCaptor<AnalysisWorkingDraft> captor =
                ArgumentCaptor.forClass(AnalysisWorkingDraft.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(view.payload().path("draftState").asText()).isEqualTo("EMPTY");
        assertThat(view.payload().path("businessText").asText()).isEmpty();
        assertThat(view.payload().path("scores").isNull()).isTrue();
        assertThat(view.payload().path("analysisOptions").path("provider").asText())
                .isEqualTo("OPENAI");
        assertThat(captor.getValue().getPayloadJson()).contains("\"draftState\":\"EMPTY\"");
    }

    @Test
    void resetOverwritesAnExistingDraftRegardlessOfAStaleBrowserRevision() {
        AnalysisWorkingDraft existing = existingDraft();
        when(repository.findByScopeKeyAndUsername(any(), any()))
                .thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        var view = service.reset("Alice", "ws-1", new ResetAnalysisDraftRequest(null));

        assertThat(view.payload().path("draftState").asText()).isEqualTo("EMPTY");
        assertThat(view.payload().path("businessText").asText()).isEmpty();
        verify(repository).saveAndFlush(existing);
    }

    @Test
    void resetRejectsNonObjectAnalysisOptions() {
        assertThatThrownBy(() -> service.reset(
                "Alice",
                "ws-1",
                new ResetAnalysisDraftRequest(objectMapper.createArrayNode())))
                .isInstanceOf(AnalysisDraftValidationException.class)
                .hasMessageContaining("reset options must be a JSON object");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void resetAppliesTheSamePayloadLimitAsRegularDraftSaves() {
        ObjectNode options = objectMapper.createObjectNode()
                .put("opaque", "x".repeat(60_000));

        assertThatThrownBy(() -> service.reset(
                "Alice", "ws-1", new ResetAnalysisDraftRequest(options)))
                .isInstanceOf(AnalysisDraftValidationException.class)
                .hasMessageContaining("reset exceeds 50000 characters");

        verify(repository, never()).saveAndFlush(any());
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

    private static SystemRepository repository(String repositoryId, String defaultBranch) {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId(repositoryId);
        repository.setDefaultBranch(defaultBranch);
        return repository;
    }
}
