package com.taxonomy.composition.plugins;

import com.taxonomy.shared.config.GlobalExceptionHandler;
import com.taxonomy.versioning.controller.DslApiController;
import com.taxonomy.versioning.controller.DslReadWorkspaceContextResolver;
import com.taxonomy.versioning.service.CommitIndexService;
import com.taxonomy.versioning.service.ConflictDetectionService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.versioning.service.SemanticDslOperationsFacade;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.GitNativeSyncIntegrationService;
import com.taxonomy.workspace.service.RepositoryStateGuard;
import com.taxonomy.workspace.service.SyncIntegrationService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AbsentPortfolioGitAdapterTest {
    @Test
    void mergeReturnsServiceUnavailableBeforeProvisioningOrGitMutation() throws Exception {
        var repositories = mock(DslGitRepositoryFactory.class);
        var index = mock(CommitIndexService.class);
        var conflicts = mock(ConflictDetectionService.class);
        var guard = mock(RepositoryStateGuard.class);
        var state = mock(RepositoryStateService.class);
        var resolver = mock(WorkspaceResolver.class);
        var merge = mock(SemanticGitMergeService.class);
        var versions = mock(WorkspaceArchitectureVersionPort.class);
        var reads = mock(DslReadWorkspaceContextResolver.class);
        var facade = new SemanticDslOperationsFacade(repositories, index, conflicts, guard, state,
                resolver, merge, new AbsentPortfolioGitAdapter(), versions);
        var mvc = standaloneSetup(new DslApiController(facade, resolver, reads))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();

        mvc.perform(post("/api/dsl/merge").param("fromBranch", "review").param("intoBranch", "draft"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.path").value("/api/dsl/merge"));

        verifyNoInteractions(repositories, index, conflicts, guard, state, resolver, merge, versions, reads);
    }

    @Test
    void everySynchronizationWriteRejectsAnAbsentPortfolioBeforeResolvingItsTarget() {
        var states = mock(SyncStateRepository.class);
        var workspaces = mock(UserWorkspaceRepository.class);
        var system = mock(SystemRepositoryService.class);
        var repositories = mock(DslGitRepositoryFactory.class);
        var merge = mock(SemanticGitMergeService.class);
        var resolver = mock(WorkspaceContextResolver.class);
        var versions = mock(WorkspaceArchitectureVersionPort.class);
        var service = new GitNativeSyncIntegrationService(states, workspaces, system, repositories, merge,
                new AbsentPortfolioGitAdapter(), resolver, versions);
        clearInvocations(repositories); // The base constructor only obtains a repository handle.

        assertUnavailable(() -> service.syncFromShared("alice", "draft"));
        assertUnavailable(() -> service.publishToShared("alice", "draft"));
        assertUnavailable(() -> service.syncFromSharedToWorkspace("alice", "workspace-a"));
        assertUnavailable(() -> service.publishFromWorkspaceToShared("alice", "workspace-a"));
        for (var strategy : SyncIntegrationService.DivergedStrategy.values()) {
            assertUnavailable(() -> service.resolveDiverged("alice", "draft", strategy));
        }

        verifyNoInteractions(states, workspaces, system, repositories, merge, resolver, versions);
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
            assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(failure.getReason()).isEqualTo("Portfolio feature is required for this operation");
        });
    }
}
