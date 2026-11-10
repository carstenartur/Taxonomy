package com.taxonomy.architecture.controller;

import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.Objects;

/** Requires explicit selection and reuses the application's authorized read resolver. */
@Component
public class ObligationRequestContext {
    private final WorkspaceResolver resolver;
    public ObligationRequestContext(WorkspaceResolver resolver) { this.resolver = resolver; }
    public RepositoryContext resolve(String repositoryId, String branch, String commitId, HttpServletRequest request) {
        if (repositoryId == null || repositoryId.isBlank() || branch == null || branch.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repositoryId and branch must be explicit");
        try { WorkspaceDslReadPort.normalizeCommitId(commitId); }
        catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()); }
        String workspace = com.taxonomy.workspace.service.WorkspaceContextResolver.requestedWorkspaceId(request);
        if (workspace == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workspace selection must be explicit; blank selects central");
        String selectedWorkspace = workspace.isBlank() ? null : workspace;
        var context = resolver.resolveCurrentRepositoryContextForRead();
        if (!repositoryId.equals(context.repositoryId()) || !branch.equals(context.branch()) || !Objects.equals(selectedWorkspace, context.workspaceId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Requested context does not match authorized selected context");
        return context;
    }
}
