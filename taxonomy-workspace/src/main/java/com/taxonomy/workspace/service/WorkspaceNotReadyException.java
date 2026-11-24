package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A selected working copy cannot serve repository requests until provisioning is ready. */
public final class WorkspaceNotReadyException extends ResponseStatusException {
    static final String REASON = "Workspace repository is not ready; complete provisioning before using it";

    WorkspaceNotReadyException(WorkspaceProvisioningStatus status) {
        super(HttpStatus.CONFLICT, REASON);
        getBody().setProperty("code", "WORKSPACE_NOT_READY");
        getBody().setProperty("workspaceStatus", status);
    }
}
