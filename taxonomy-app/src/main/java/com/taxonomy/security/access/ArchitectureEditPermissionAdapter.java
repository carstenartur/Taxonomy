package com.taxonomy.security.access;

import com.taxonomy.editor.ArchitectureEditPermission;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Exposes the shared REST editing permission to the workspace editor. */
@Component
public class ArchitectureEditPermissionAdapter implements ArchitectureEditPermission {
    @Override
    public boolean mayEdit(Authentication authentication) {
        return ApiAccessPolicy.hasPermission(authentication, ApiPermission.ARCHITECTURE_WRITE);
    }
}
