package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import java.io.IOException;
import static org.mockito.Mockito.*;

/** Run fenced callbacks in service unit tests. File-backed tests exercise the real claim and transaction boundary. */
final class WorkspaceProvisioningTestClaims {
    private WorkspaceProvisioningTestClaims() {}

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
