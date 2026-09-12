package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Legacy HTTP read compatibility only; repository routing and request pre-resolution fail closed. */
@Component
public class DslReadWorkspaceContextResolver {
    private static final Logger log = LoggerFactory.getLogger(DslReadWorkspaceContextResolver.class);
    private final WorkspaceResolver workspaceResolver;
    private final RepositoryStateService repositoryStateService;

    public DslReadWorkspaceContextResolver(WorkspaceResolver workspaceResolver,
                                           RepositoryStateService repositoryStateService) {
        this.workspaceResolver = workspaceResolver;
        this.repositoryStateService = repositoryStateService;
    }

    public WorkspaceContext resolve(String username) {
        try {
            repositoryStateService.ensureWorkspaceState(username);
            return workspaceResolver.resolveCurrentContext();
        } catch (Exception e) {
            log.warn("Falling back to shared workspace context for user '{}' due to: {}",
                    username, e.toString(), e);
            return WorkspaceContext.SHARED;
        }
    }

}
