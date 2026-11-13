package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A single worker's bounded claim to the recorded workspace allocation. */
record WorkspaceProvisioningClaim(UserWorkspace workspace, String worker, Instant startedAt) {
    private static final Duration LIFETIME = Duration.ofMinutes(15);

    WorkspaceProvisioningClaim(UserWorkspace workspace) {
        this(workspace, UUID.randomUUID().toString(), Instant.now());
    }

    void initialize() {
        workspace.startProvisioningLease(worker, startedAt, startedAt.plus(LIFETIME));
    }

    void observeLegacyWorker(UserWorkspaceRepository rows) {
        rows.observeLegacyProvisioning(workspace.getWorkspaceId(), workspace.getUsername(),
                WorkspaceProvisioningStatus.PROVISIONING, startedAt.plus(LIFETIME));
    }

    boolean acquire(UserWorkspaceRepository rows) {
        return rows.claimProvisioning(workspace.getWorkspaceId(), workspace.getUsername(), WorkspaceProvisioningStatus.PROVISIONING,
                List.of(WorkspaceProvisioningStatus.NOT_PROVISIONED, WorkspaceProvisioningStatus.FAILED),
                worker, startedAt, startedAt.plus(LIFETIME)) == 1;
    }

    UserWorkspace apply(UserWorkspaceRepository rows, UserWorkspaceRepository.ProvisioningStep action) throws IOException {
        return rows.withProvisioningLease(workspace, worker, action);
    }

    void fail(UserWorkspaceRepository rows, Throwable failure, UserWorkspaceRepository.ProvisioningStep cleanup) {
        try {
            apply(rows, current -> {
                try {
                    cleanup.apply(current);
                } catch (IOException | RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                current.setProvisioningStatus(WorkspaceProvisioningStatus.FAILED);
                current.setProvisioningError(message(failure));
            });
        } catch (IOException | RuntimeException lostClaimOrDatabaseFailure) {
            failure.addSuppressed(lostClaimOrDatabaseFailure);
        }
    }

    static void markProvisioned(UserWorkspace current, String commit) {
        current.setCurrentCommit(commit);
        current.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
        current.setProvisionedAt(Instant.now());
        current.setProvisioningError(null);
    }

    static void adoptIntegrationBase(DslGitRepository repository, String branch, String commit)
            throws IOException {
        String existing = repository.getHeadCommit(branch);
        if (existing == null) {
            repository.createBranchAtCommit(branch, commit);
        } else {
            repository.verifyExpectedHead(branch, commit);
        }
    }

    static String message(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return message.substring(0, Math.min(message.length(), 255));
    }
}
