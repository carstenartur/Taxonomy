package com.taxonomy.workspace.service;

import com.taxonomy.dto.WorkspaceInfo;
import com.taxonomy.versioning.service.ContextCompareService;
import com.taxonomy.versioning.service.ContextHistoryService;
import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.workspace.controller.WorkspaceController;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Detached metadata snapshots while the same server provisions the default. */
class WorkspaceConcurrentDefaultInitializationTest {
    private static final String USER = "concurrent-default-owner";
    private static final String ID = "concurrent-default-workspace";
    enum Reader { REPOSITORY, LEGACY, CURRENT_RESPONSE }

    static Stream<Arguments> readers() {
        return Stream.of(Reader.values()).flatMap(reader -> Stream.of(
                Arguments.of(reader, false), Arguments.of(reader, true)));
    }

    @ParameterizedTest
    @MethodSource("readers")
    void concurrentImplicitReadersJoinTheOngoingDefaultInitialization(Reader reader, boolean fails) throws Exception {
        var saved = new AtomicReference<>(workspace());
        var opening = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var observedInProgress = new CountDownLatch(1);
        var rows = mock(UserWorkspaceRepository.class);
        when(rows.existsByUsername(USER)).thenReturn(true);
        when(rows.findByUsernameAndSharedFalse(USER)).thenAnswer(call -> Optional.of(snapshot(saved.get())));
        when(rows.findByUsernameAndIsDefaultTrue(USER)).thenAnswer(call -> Optional.of(snapshot(saved.get())));
        when(rows.findByWorkspaceId(ID)).thenAnswer(call -> {
            var result = snapshot(saved.get());
            if (result.getProvisioningStatus() == WorkspaceProvisioningStatus.PROVISIONING) observedInProgress.countDown();
            return Optional.of(result);
        });
        when(rows.save(any(UserWorkspace.class))).thenAnswer(call -> {
            var result = snapshot(call.getArgument(0));
            saved.set(result);
            return snapshot(result);
        });
        when(rows.claimProvisioning(eq(ID), eq(USER),
                eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection())).thenAnswer(call -> {
            var claimed = snapshot(saved.get());
            if (claimed.getProvisioningStatus() != WorkspaceProvisioningStatus.NOT_PROVISIONED) return 0;
            claimed.setProvisioningStatus(WorkspaceProvisioningStatus.PROVISIONING);
            saved.set(claimed);
            return 1;
        });
        try (var factory = spy(new DslGitRepositoryFactory(null))) {
            String source = factory.getSystemRepository().commitDsl("draft",
                    "meta { language: \"taxdsl\"; version: \"captured\"; }", USER, "Initial source");
            var metadata = new SystemRepository();
            metadata.setRepositoryId("concurrent-source");
            metadata.setDefaultBranch("draft");
            metadata.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
            var catalog = mock(SystemRepositoryService.class);
            when(catalog.getPrimaryRepository()).thenReturn(metadata);
            when(catalog.getRepository("concurrent-source")).thenReturn(metadata);
            var manager = new WorkspaceManager(rows, 50, catalog, factory);
            var resolver = new WorkspaceContextResolver(manager, catalog, rows);
            var controller = new WorkspaceController(manager, new WorkspaceResolver(resolver),
                    mock(ContextCompareService.class), mock(ContextHistoryService.class),
                    mock(ContextNavigationService.class), mock(SyncIntegrationService.class),
                    mock(WorkspaceProjectionService.class), catalog);
            // First-login metadata and active navigation exist before parallel page requests.
            manager.getWorkspaceInfo(USER);
            doAnswer(call -> {
                opening.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS), "Reader never released initial provisioning");
                if (fails) throw new IllegalStateException("Source storage unavailable");
                return call.callRealMethod();
            }).when(factory).openWorkspaceRepository(ID);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var initializer = executor.submit(() -> manager.provisionDefaultWorkspaceRepository(USER, ID));
                try {
                    assertTrue(opening.await(10, TimeUnit.SECONDS), "Provisioning did not reach storage");
                    var second = executor.submit(() -> {
                        var security = SecurityContextHolder.createEmptyContext();
                        security.setAuthentication(new UsernamePasswordAuthenticationToken(USER, "unused", List.of()));
                        SecurityContextHolder.setContext(security);
                        try {
                            return switch (reader) {
                                case REPOSITORY -> resolver.resolveRepositoryContextForUser(USER);
                                case LEGACY -> resolver.resolveForUser(USER);
                                case CURRENT_RESPONSE -> controller.getCurrentWorkspace().getBody();
                            };
                        } finally { SecurityContextHolder.clearContext(); }
                    });
                    assertTrue(observedInProgress.await(10, TimeUnit.SECONDS), "Reader did not observe in-progress metadata");
                    release.countDown();
                    if (fails) {
                        assertThrows(ExecutionException.class, () -> initializer.get(10, TimeUnit.SECONDS));
                        var failure = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
                        assertEquals(409, assertInstanceOf(ResponseStatusException.class,
                                failure.getCause()).getStatusCode().value());
                        assertEquals(WorkspaceProvisioningStatus.FAILED, saved.get().getProvisioningStatus());
                        verify(rows, times(1)).claimProvisioning(eq(ID), eq(USER),
                                eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection());
                        verify(factory, times(1)).openWorkspaceRepository(ID);
                        return;
                    }
                    assertEquals(WorkspaceProvisioningStatus.READY, initializer.get(10, TimeUnit.SECONDS).getProvisioningStatus());
                    Object result = assertDoesNotThrow(() -> second.get(10, TimeUnit.SECONDS));
                    switch (reader) {
                        case REPOSITORY -> assertEquals(RepositoryContext.workspace("concurrent-source", ID, "draft", USER), result);
                        case LEGACY -> assertEquals(new WorkspaceContext(USER, ID, "draft"), result);
                        case CURRENT_RESPONSE -> {
                            WorkspaceInfo info = assertInstanceOf(WorkspaceInfo.class, result);
                            assertEquals(ID, info.workspaceId());
                            assertEquals("READY", info.provisioningStatus(), "Do not publish an unusable first browser pin");
                        }
                    }
                    assertEquals(source, saved.get().getBaseCommit());
                    assertEquals(WorkspaceProvisioningStatus.READY, saved.get().getProvisioningStatus());
                    assertEquals(1, factory.openWorkspaceRepository(ID).getDslHistory("draft").size());
                    verify(rows, times(1)).claimProvisioning(eq(ID), eq(USER),
                            eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection());
                } finally { release.countDown(); }
            }
        }
    }

    private static UserWorkspace workspace() {
        var row = new UserWorkspace();
        row.setUsername(USER);
        row.setWorkspaceId(ID);
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
        copy.setCurrentBranch(source.getCurrentBranch());
        copy.setTopologyMode(source.getTopologyMode());
        copy.setProvisioningStatus(source.getProvisioningStatus());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setSourceRepositoryId(source.getSourceRepositoryId());
        copy.setBaseCommit(source.getBaseCommit());
        copy.setCurrentCommit(source.getCurrentCommit());
        copy.setProvisionedAt(source.getProvisionedAt());
        return copy;
    }
}
