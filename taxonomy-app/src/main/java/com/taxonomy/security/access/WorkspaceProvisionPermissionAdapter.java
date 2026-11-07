package com.taxonomy.security.access;

import com.taxonomy.workspace.service.WorkspaceProvisionPermission;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Applies the shared provisioning capability to implicit workspace creation. */
@Component
public class WorkspaceProvisionPermissionAdapter implements WorkspaceProvisionPermission {
    @Override
    public boolean mayProvision(String actor) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && actor != null && actor.equals(authentication.getName())
                && ApiAccessPolicy.hasPermission(authentication, ApiPermission.ANALYSIS_RUN);
    }
}
