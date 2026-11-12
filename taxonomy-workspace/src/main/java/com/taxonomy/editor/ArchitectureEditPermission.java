package com.taxonomy.editor;

import org.springframework.security.core.Authentication;

/** Optional application policy for the editor's visible editing capability. */
@FunctionalInterface
public interface ArchitectureEditPermission {
    boolean mayEdit(Authentication authentication);

    static boolean allowed(Authentication authentication, java.util.Optional<ArchitectureEditPermission> policy) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(authority -> "ROLE_ARCHITECT".equals(authority.getAuthority()))
                && policy.map(check -> check.mayEdit(authentication)).orElse(true);
    }
}
