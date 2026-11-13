package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import java.io.IOException;
import static org.mockito.Mockito.*;

/** Run fenced callbacks in service unit tests. File-backed tests exercise the real claim and transaction boundary. */
public final class WorkspaceProvisioningTestClaims {
    private static final java.util.Map<String,java.util.UUID> OWNERS = new java.util.concurrent.ConcurrentHashMap<>();
    private WorkspaceProvisioningTestClaims() {}

    public static void withOwners(WorkspaceManager manager) {
        manager.configureActorAccess(fixtureOwners());
    }

    static WorkspaceActorAccess fixtureOwners() {
        var access = mock(WorkspaceActorAccess.class, CALLS_REAL_METHODS);
        lenient().doAnswer(call -> {
            String actor = call.getArgument(0);
            return java.util.Optional.of(OWNERS.computeIfAbsent(actor, ignored -> java.util.UUID.randomUUID()));
        }).when(access).currentOwnerPrincipal(anyString());
        return access;
    }

    static void enable(UserWorkspaceRepository rows) {
        try {
            lenient().doAnswer(call -> {
                UserWorkspace selected = call.getArgument(0);
                call.<UserWorkspaceRepository.ProvisioningStep>getArgument(2).apply(selected);
                return rows.save(selected);
            }).when(rows).withProvisioningLease(any(UserWorkspace.class), anyString(), any());
        } catch (IOException unexpected) {
            throw new AssertionError(unexpected);
        }
    }
}
