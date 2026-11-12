package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Legacy HTTP read compatibility only; failed selection may not become a different repository. */
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
            WorkspaceContext selected = workspaceResolver.resolveCurrentContext();
            if (selected == null) {
                throw new IllegalStateException("Selected workspace context missing");
            }
            return selected;
        } catch (AccessDeniedException | AuthenticationException | ResponseStatusException denied) {
            // Preserve the original security decision and HTTP status.
            throw denied;
        } catch (RuntimeException unavailable) {
            // A failed selection must not be replaced by the historic shared repository.
            // Avoid logging usernames, repository IDs or potentially sensitive exception text.
            log.warn("DSL read context unavailable: {}", unavailable.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Selected repository context unavailable");
        }
    }

}
