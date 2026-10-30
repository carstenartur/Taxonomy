package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A source that has not bootstrapped is not a failed workspace allocation. */
class WorkspaceDefaultBootstrapReadinessTest {
    private static final String USER = "bootstrap-workspace-owner";
    private static final String REPOSITORY = "bootstrap-workspace-source";
    private static final String SOURCE = "meta { language: \"taxdsl\"; version: \"bootstrapped\"; }";

    @BeforeEach
    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void implicitDefaultRemainsUnclaimedUntilTheSourceCheckpointExists() throws Exception {
        try (var fixture = new Fixture()) {
            String workspaceId = fixture.saved.get().getWorkspaceId();
            assertNull(fixture.factory.getSystemRepository().getHeadCommit("draft"));

            for (int request = 0; request < 2; request++) {
                var failure = assertThrows(RuntimeException.class,
                        () -> fixture.resolver.resolveRepositoryContextForUser(USER));
                assertEquals(WorkspaceProvisioningStatus.NOT_PROVISIONED,
                        fixture.saved.get().getProvisioningStatus(),
                        "A source awaiting bootstrap must not poison the automatic workspace as FAILED");
                assertEquals(409, assertInstanceOf(ResponseStatusException.class, failure).getStatusCode().value());
                assertEquals(workspaceId, fixture.saved.get().getWorkspaceId());
                assertNull(fixture.saved.get().getProvisioningError());
                assertNull(fixture.saved.get().getCurrentCommit());
                assertNull(fixture.saved.get().getProvisionedAt());
            }
            verify(fixture.rows, never()).claimProvisioning(anyString(), anyString(), any(), anyCollection());
            verify(fixture.rows, never()).save(any());
            verify(fixture.factory, never()).openWorkspaceRepository(anyString());
            verify(fixture.factory, never()).getWorkspaceRepository(anyString());

            String sourceCommit = fixture.factory.getSystemRepository().commitDsl(
                    "draft", SOURCE, "system", "Initial taxonomy materialization");
            assertEquals(RepositoryContext.workspace(REPOSITORY, workspaceId, "draft", USER),
                    fixture.resolver.resolveRepositoryContextForUser(USER));

            var ready = fixture.saved.get();
            assertEquals(workspaceId, ready.getWorkspaceId(), "Retry must use the originally selected UUID");
            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertEquals(sourceCommit, ready.getBaseCommit());
            assertEquals("draft", ready.getCurrentBranch());
            assertNull(ready.getProvisioningError());
            assertNotNull(ready.getProvisionedAt());
            var destination = fixture.factory.openWorkspaceRepository(workspaceId);
            assertEquals(SOURCE, destination.getDslAtHead("draft"));
            assertEquals(List.of("draft"), destination.getBranchNames());
            assertEquals(1, destination.getDslHistory("draft").size());
            var provenance = destination.getProvenance(ready.getCurrentCommit());
            assertEquals("fork", provenance.operation());
            assertEquals(REPOSITORY, provenance.sources().getFirst().repositoryId());
            assertEquals(sourceCommit, provenance.sources().getFirst().commitId());
            verify(fixture.rows, times(1)).claimProvisioning(eq(workspaceId), eq(USER),
                    eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection());
        }
    }

    @Test
    void actualAllocationFailureStillRequiresAnExplicitRetry() throws Exception {
        try (var fixture = new Fixture()) {
            String workspaceId = fixture.saved.get().getWorkspaceId();
            fixture.factory.getSystemRepository().commitDsl("draft", SOURCE, "system", "Ready source");
            doThrow(new IllegalStateException("Workspace storage unavailable"))
                    .when(fixture.factory).openWorkspaceRepository(workspaceId);

            assertThrows(RuntimeException.class,
                    () -> fixture.resolver.resolveRepositoryContextForUser(USER));
            assertEquals(WorkspaceProvisioningStatus.FAILED, fixture.saved.get().getProvisioningStatus());
            assertEquals("Workspace storage unavailable", fixture.saved.get().getProvisioningError());

            doCallRealMethod().when(fixture.factory).openWorkspaceRepository(workspaceId);
            assertEquals(409, assertThrows(ResponseStatusException.class,
                    () -> fixture.resolver.resolveRepositoryContextForUser(USER)).getStatusCode().value());
            assertEquals(409, assertThrows(ResponseStatusException.class,
                    () -> fixture.manager.provisionDefaultWorkspaceRepository(USER, workspaceId)).getStatusCode().value());
            verify(fixture.rows, times(1)).claimProvisioning(eq(workspaceId), eq(USER),
                    eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection());
            verify(fixture.factory, times(1)).openWorkspaceRepository(workspaceId);

            var ready = fixture.manager.provisionWorkspaceRepository(USER, workspaceId);
            assertEquals(workspaceId, ready.getWorkspaceId());
            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertNull(ready.getProvisioningError());
            assertEquals(SOURCE, fixture.factory.openWorkspaceRepository(workspaceId).getDslAtHead(ready.getCurrentBranch()));
            verify(fixture.rows, times(2)).claimProvisioning(eq(workspaceId), eq(USER),
                    eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "shared", "archived"})
    void rejectsUnavailableWorkspacesBeforeReadingTheSource(String unavailable) {
        try (var fixture = new Fixture()) {
            var row = fixture.saved.get();
            if ("foreign".equals(unavailable)) row.setUsername("another-owner");
            if ("shared".equals(unavailable)) row.setShared(true);
            if ("archived".equals(unavailable)) row.setArchived(true);
            clearInvocations(fixture.factory, fixture.catalog);

            assertThrows(AccessDeniedException.class,
                    () -> fixture.manager.provisionDefaultWorkspaceRepository(USER, row.getWorkspaceId()));

            verifyNoInteractions(fixture.factory, fixture.catalog);
            verify(fixture.rows, never()).claimProvisioning(anyString(), anyString(), any(), anyCollection());
            assertEquals(WorkspaceProvisioningStatus.NOT_PROVISIONED, row.getProvisioningStatus());
        }
    }

    @Test
    void aReadyDefaultDoesNotInspectItsSourceAgain() {
        try (var fixture = new Fixture()) {
            var row = fixture.saved.get();
            row.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
            clearInvocations(fixture.factory, fixture.catalog);

            var ready = fixture.manager.provisionDefaultWorkspaceRepository(USER, row.getWorkspaceId());

            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertEquals(row.getWorkspaceId(), ready.getWorkspaceId());
            verifyNoInteractions(fixture.factory, fixture.catalog);
            verify(fixture.rows, never()).claimProvisioning(anyString(), anyString(), any(), anyCollection());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checksTheRecordedSourceBranchUnlessABaseCommitWasAlreadyRetained(boolean retainedBase) throws Exception {
        try (var fixture = new Fixture(); var storage = new DslGitRepository()) {
            fixture.factory.getSystemRepository().commitDsl("draft", SOURCE, "system", "Unrelated primary");
            var selected = spy(storage);
            var metadata = new SystemRepository();
            metadata.setRepositoryId("selected-source");
            metadata.setDefaultBranch("draft");
            when(fixture.catalog.getRepository("selected-source")).thenReturn(metadata);
            doReturn(selected).when(fixture.factory).getCentralRepository("selected-source");
            var row = fixture.saved.get();
            row.setSourceRepositoryId("selected-source");
            row.setSourceBranch("release/ready");
            String retained = retainedBase
                    ? selected.commitDsl("retained", SOURCE, "source-owner", "Retained checkpoint") : null;
            row.setBaseCommit(retained);
            clearInvocations(fixture.factory, fixture.catalog, selected);

            if (!retainedBase) {
                assertEquals(409, assertThrows(ResponseStatusException.class,
                        () -> fixture.manager.provisionDefaultWorkspaceRepository(USER, row.getWorkspaceId()))
                        .getStatusCode().value());
                assertEquals(WorkspaceProvisioningStatus.NOT_PROVISIONED, fixture.saved.get().getProvisioningStatus());
                verify(fixture.rows, never()).claimProvisioning(anyString(), anyString(), any(), anyCollection());
                retained = selected.commitDsl("release/ready", SOURCE, "source-owner", "Delayed selected source");
            }
            var ready = fixture.manager.provisionDefaultWorkspaceRepository(USER, row.getWorkspaceId());

            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertEquals(retained, ready.getBaseCommit());
            assertEquals("selected-source", ready.getSourceRepositoryId());
            assertEquals("release/ready", ready.getBaseBranch());
            assertEquals(SOURCE, fixture.factory.openWorkspaceRepository(row.getWorkspaceId()).getDslAtHead("draft"));
            if (retainedBase) verify(selected, never()).getHeadCommit(anyString());
            verify(fixture.catalog, never()).getPrimaryRepository();
            verify(fixture.factory, never()).getSystemRepository();
        }
    }

    @Test
    void sourceReadFailurePreservesItsCauseWithoutClaimingAnAllocation() throws Exception {
        try (var fixture = new Fixture()) {
            var source = spy(fixture.factory.getSystemRepository());
            doReturn(source).when(fixture.factory).getSystemRepository();
            var unavailable = new IOException("Source storage unavailable");
            doThrow(unavailable).when(source).getHeadCommit("draft");

            var failure = assertThrows(UncheckedIOException.class,
                    () -> fixture.resolver.resolveRepositoryContextForUser(USER));

            assertSame(unavailable, failure.getCause());
            assertEquals(WorkspaceProvisioningStatus.NOT_PROVISIONED, fixture.saved.get().getProvisioningStatus());
            assertNull(fixture.saved.get().getProvisioningError());
            verify(fixture.rows, never()).claimProvisioning(anyString(), anyString(), any(), anyCollection());
            verify(fixture.rows, never()).save(any());
            verify(fixture.factory, never()).openWorkspaceRepository(anyString());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicReference<UserWorkspace> saved = new AtomicReference<>(workspace());
        final UserWorkspaceRepository rows = mock(UserWorkspaceRepository.class);
        final DslGitRepositoryFactory factory = spy(new DslGitRepositoryFactory(null));
        final SystemRepositoryService catalog = mock(SystemRepositoryService.class);
        final WorkspaceManager manager;
        final WorkspaceContextResolver resolver;

        Fixture() {
            String id = saved.get().getWorkspaceId();
            when(rows.findByWorkspaceId(id)).thenAnswer(call -> Optional.of(snapshot(saved.get())));
            when(rows.save(any(UserWorkspace.class))).thenAnswer(call -> {
                saved.set(snapshot(call.getArgument(0)));
                return snapshot(saved.get());
            });
            when(rows.claimProvisioning(eq(id), eq(USER),
                    eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection())).thenAnswer(call -> {
                var current = snapshot(saved.get());
                if (!List.of(WorkspaceProvisioningStatus.NOT_PROVISIONED, WorkspaceProvisioningStatus.FAILED)
                        .contains(current.getProvisioningStatus())) return 0;
                current.setProvisioningStatus(WorkspaceProvisioningStatus.PROVISIONING);
                saved.set(current);
                return 1;
            });
            var source = new SystemRepository();
            source.setRepositoryId(REPOSITORY);
            source.setDefaultBranch("draft");
            source.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
            when(catalog.getPrimaryRepository()).thenReturn(source);
            when(catalog.getRepository(REPOSITORY)).thenReturn(source);
            manager = new WorkspaceManager(rows, 50, catalog, factory);
            manager.getOrCreateWorkspace(USER, id);
            resolver = new WorkspaceContextResolver(manager, catalog, rows);
        }

        @Override public void close() { factory.close(); }
    }

    private static UserWorkspace workspace() {
        var row = new UserWorkspace();
        row.setUsername(USER);
        row.setWorkspaceId(UUID.randomUUID().toString());
        row.setDefault(true);
        row.setCurrentBranch("draft");
        row.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        row.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        row.setCreatedAt(Instant.now());
        return row;
    }

    private static UserWorkspace snapshot(UserWorkspace source) {
        var copy = new UserWorkspace();
        copy.setUsername(source.getUsername());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setDefault(source.isDefault());
        copy.setShared(source.isShared());
        copy.setArchived(source.isArchived());
        copy.setCurrentBranch(source.getCurrentBranch());
        copy.setTopologyMode(source.getTopologyMode());
        copy.setProvisioningStatus(source.getProvisioningStatus());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setSourceRepositoryId(source.getSourceRepositoryId());
        copy.setSourceBranch(source.getSourceBranch());
        copy.setBaseBranch(source.getBaseBranch());
        copy.setBaseCommit(source.getBaseCommit());
        copy.setCurrentCommit(source.getCurrentCommit());
        copy.setSyncTargetBranch(source.getSyncTargetBranch());
        copy.setProvisionedAt(source.getProvisionedAt());
        copy.setProvisioningError(source.getProvisioningError());
        return copy;
    }
}
