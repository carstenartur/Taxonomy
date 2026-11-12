package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;

/** Repository fragment shared by working-copy creation and leased retry. */
public class WorkspaceProvisioningFenceImpl implements WorkspaceProvisioningFence {
    private final EntityManager entities;

    public WorkspaceProvisioningFenceImpl(EntityManager entities) {
        this.entities = entities;
    }

    @Override
    public UserWorkspace withProvisioningLease(UserWorkspace selected, String worker, ProvisioningStep step) throws IOException {
        var current = entities.createQuery("""
                select w from UserWorkspace w where w.workspaceId = :id and w.username = :owner
                  and w.archived = false and w.shared = false and w.provisioningStatus = :running
                  and w.provisioningLeaseOwner = :worker and w.provisioningLeaseUntil > :now
                """, UserWorkspace.class).setParameter("id", selected.getWorkspaceId())
                .setParameter("owner", selected.getUsername()).setParameter("running", WorkspaceProvisioningStatus.PROVISIONING)
                .setParameter("worker", worker).setParameter("now", Instant.now())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if (current.isEmpty()) {
            throw unavailable();
        }
        // Bulk claims bypass the persistence context. A joined caller may already hold an older
        // managed instance; refresh only this locked row, preserving its other pending work.
        var workspace = current.getFirst();
        entities.refresh(workspace, LockModeType.PESSIMISTIC_WRITE);
        if (!workspace.getProvisioningLeaseUntil().isAfter(Instant.now())) {
            throw unavailable();
        }
        step.apply(workspace);
        return workspace;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Workspace provisioning was claimed or its lifecycle changed");
    }
}
