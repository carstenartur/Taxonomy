package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.UserWorkspace;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

/** Keep consequential provisioning effects within the current worker's workspace row lock. */
public interface WorkspaceProvisioningFence {
    @FunctionalInterface
    interface ProvisioningStep {
        void apply(UserWorkspace current) throws IOException;
    }

    @Transactional(rollbackFor = IOException.class)
    UserWorkspace withProvisioningLease(UserWorkspace selected, String worker, ProvisioningStep step) throws IOException;
}
