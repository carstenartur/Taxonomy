package com.taxonomy.security.access;

import com.taxonomy.interop.IntegrationOperationPermission;
import com.taxonomy.shared.features.ConditionalOnFeature;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Applies the shared capability catalogue after the integration operation kind is known. */
@Component
@ConditionalOnFeature("interop")
public class IntegrationOperationPermissionAdapter implements IntegrationOperationPermission {
    @Override
    public boolean permits(String actor, Action action) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || actor == null || !actor.equals(authentication.getName()) || action == null) return false;
        var permission = switch (action) {
            case IMPORT -> ApiPermission.INTEGRATION_IMPORT;
            case EXPORT -> ApiPermission.INTEGRATION_EXPORT;
            case PUBLISH -> ApiPermission.ARCHITECTURE_PUBLISH;
        };
        return ApiAccessPolicy.hasPermission(authentication, permission);
    }
}
