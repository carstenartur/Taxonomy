package com.taxonomy.portfolio;

import com.taxonomy.security.model.StablePrincipal;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;

/** Run service-level admissions under the same selected actor and workspace as a portfolio request. */
public final class PortfolioAnalysisWorkspace {
    private PortfolioAnalysisWorkspace() { }

    public static WorkspaceContext select(WorkspaceResolver resolver, String actor) {
        authenticateLegacyFixture(actor);
        return resolver.resolveCurrentContext();
    }

    public static UserWorkspace provisionSelected(WorkspaceManager manager, String actor, String workspaceId) {
        UserWorkspace selected = manager.getWorkspaceById(workspaceId);
        if (selected != null && selected.getOwnerPrincipalId() == null) {
            authenticateLegacyFixture(actor);
        }
        UserWorkspace workspace = manager.provisionWorkspaceRepository(actor, workspaceId);
        manager.switchWorkspace(actor, workspaceId);
        return workspace;
    }

    private static void authenticateLegacyFixture(String actor) {
        var login = SecurityContextHolder.getContext().getAuthentication();
        if (login != null && login.isAuthenticated()
                && (login instanceof StablePrincipal || login.getPrincipal() instanceof StablePrincipal)) {
            if (!actor.equals(login.getName())) {
                throw new IllegalStateException("Log in as the selected portfolio actor first");
            }
            return;
        }
        TestSecurityContextHolder.setAuthentication(new TestingAuthenticationToken(actor, null, "ROLE_ARCHITECT"));
    }
}
