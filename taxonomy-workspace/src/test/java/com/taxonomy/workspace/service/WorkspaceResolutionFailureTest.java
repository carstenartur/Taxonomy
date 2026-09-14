package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** Real-manager tests: absence, failed lookup and unfinished provisioning are distinct. */
class WorkspaceResolutionFailureTest {
    enum Selection { ACTIVE, LEGACY, HEADER, QUERY }

    private UserWorkspaceRepository rows;
    private SystemRepositoryService repositories;
    private WorkspaceManager manager;
    private WorkspaceContextResolver resolver;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        rows = mock(UserWorkspaceRepository.class);
        repositories = mock(SystemRepositoryService.class);
        manager = new WorkspaceManager(rows, 10, repositories, mock(DslGitRepository.class));
        resolver = new WorkspaceContextResolver(manager, repositories, rows);
        when(repositories.getPrimaryRepository()).thenReturn(central());
        when(repositories.getRepository("repo-a")).thenReturn(central());
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @EnumSource(Selection.class)
    void lookupFailurePropagatesWithoutSelectingAnotherRepository(Selection selection) {
        select(selection, WorkspaceProvisioningStatus.READY);
        var outage = new DataAccessResourceFailureException("workspace lookup unavailable");
        if (selection == Selection.LEGACY) {
            when(rows.findByUsernameAndSharedFalse("alice")).thenThrow(outage);
        } else {
            when(rows.findByWorkspaceId("workspace-a")).thenThrow(outage);
        }
        assertSame(outage, assertThrows(DataAccessResourceFailureException.class,
                () -> resolver.resolveRepositoryContextForUser("alice")));
        assertSame(outage, assertThrows(DataAccessResourceFailureException.class,
                () -> resolver.resolveForUser("alice")));
        verifyNoInteractions(repositories);
        verify(rows, never()).save(any());
        if (selection != Selection.LEGACY) {
            verify(rows, never()).findByUsernameAndSharedFalse(any());
        }
    }

    @ParameterizedTest
    @MethodSource("unfinishedSelections")
    void unfinishedWorkspaceCannotBecomeARepositoryContext(
            Selection selection, WorkspaceProvisioningStatus status) {
        select(selection, status);
        assertNotReady();
    }

    @ParameterizedTest
    @EnumSource(Selection.class)
    void missingProvisioningStateFailsClosed(Selection selection) {
        select(selection, null);
        assertNotReady();
    }

    @ParameterizedTest
    @EnumSource(Selection.class)
    void readyWorkspaceRetainsItsExactRepositoryAndBranch(Selection selection) {
        select(selection, WorkspaceProvisioningStatus.READY);
        assertThat(resolver.resolveRepositoryContextForUser("alice"))
                .isEqualTo(RepositoryContext.workspace("repo-a", "workspace-a", "feature/a", "alice"));
        assertThat(resolver.resolveForUser("alice").currentBranch()).isEqualTo("feature/a");
        verify(repositories, never()).getPrimaryRepository();
    }

    @Test
    void successfulEmptyLookupRetainsCentralReadCompatibility() {
        when(rows.findByUsernameAndSharedFalse("alice")).thenReturn(Optional.empty());
        assertThat(resolver.resolveRepositoryContextForUser("alice"))
                .isEqualTo(RepositoryContext.centralRead("repo-a", "main", "alice"));
        assertThat(resolver.resolveForUser("alice")).isEqualTo(WorkspaceContext.SHARED);
    }

    private void assertNotReady() {
        assertThat(assertThrows(ResponseStatusException.class,
                () -> resolver.resolveRepositoryContextForUser("alice"))
                .getStatusCode().value()).isEqualTo(409);
        assertThat(assertThrows(ResponseStatusException.class,
                () -> resolver.resolveForUser("alice"))
                .getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(repositories);
        verify(rows, never()).save(any());
    }

    private void select(Selection selection, WorkspaceProvisioningStatus status) {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setUsername("alice");
        workspace.setWorkspaceId("workspace-a");
        workspace.setSourceRepositoryId("repo-a");
        workspace.setCurrentBranch("feature/a");
        workspace.setProvisioningStatus(status);
        if (selection == Selection.LEGACY) {
            when(rows.findByUsernameAndSharedFalse("alice")).thenReturn(Optional.of(workspace));
        } else {
            when(rows.findByWorkspaceId("workspace-a")).thenReturn(Optional.of(workspace));
            if (selection == Selection.ACTIVE) {
                manager.getOrCreateWorkspace("alice", "workspace-a");
            } else {
                MockHttpServletRequest request = new MockHttpServletRequest();
                if (selection == Selection.HEADER) {
                    request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, "workspace-a");
                } else {
                    request.addParameter(WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER, "workspace-a");
                }
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            }
        }
    }

    static Stream<Arguments> unfinishedSelections() {
        return Arrays.stream(Selection.values()).flatMap(selection -> Stream.of(
                WorkspaceProvisioningStatus.NOT_PROVISIONED,
                WorkspaceProvisioningStatus.PROVISIONING,
                WorkspaceProvisioningStatus.FAILED).map(status -> Arguments.of(selection, status)));
    }

    private static SystemRepository central() {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId("repo-a");
        repository.setDefaultBranch("main");
        return repository;
    }
}
