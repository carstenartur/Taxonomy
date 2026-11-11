package com.taxonomy.portfolio;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;

/** Run service-level admissions under the same selected actor and workspace as a portfolio request. */
public final class PortfolioAnalysisWorkspace {
    private PortfolioAnalysisWorkspace() { }

    public static WorkspaceContext select(WorkspaceResolver resolver, String actor) {
        authenticate(actor);
        return resolver.resolveCurrentContext();
    }

    public static UserWorkspace provisionSelected(WorkspaceManager manager, String actor, String workspaceId) {
        authenticate(actor);
        UserWorkspace workspace = manager.provisionWorkspaceRepository(actor, workspaceId);
        manager.switchWorkspace(actor, workspaceId);
        return workspace;
    }

    private static void authenticate(String actor) {
        TestSecurityContextHolder.setAuthentication(new TestingAuthenticationToken(actor, null, "ROLE_ARCHITECT"));
    }
}
