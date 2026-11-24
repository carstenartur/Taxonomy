package com.taxonomy.workspace.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The automatic workspace has no initial source checkpoint to copy yet. */
public final class InitialWorkspaceSourcePending extends ResponseStatusException {
    public static final String CODE = "INITIAL_SOURCE_PENDING";

    public InitialWorkspaceSourcePending() {
        super(HttpStatus.CONFLICT,
                "Source repository is still initializing; retry when its initial checkpoint is available");
        getBody().setProperty("code", CODE);
    }
}
