package com.taxonomy.composition.architecture;

import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.workspace.service.WorkspaceActorAccess;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Reuses the current authoritative identity, including revocation of an old browser session. */
@Component
public final class SharedWorkspaceActorAccess implements WorkspaceActorAccess {
    private final PrincipalIdentityService identities;

    public SharedWorkspaceActorAccess(PrincipalIdentityService identities) { this.identities = identities; }

    @Override public void requireActive(String username) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var principal = identities.require(authentication);
        if (!username.equals(authentication.getName()) || identities.find(principal)
                .filter(identity -> username.equals(identity.scopeKey())).isEmpty()) {
            throw new AccessDeniedException("Verified active principal required for shared editing");
        }
    }
}
