package com.taxonomy.security.access;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.versioning.service.HistoryIndexGrant;
import com.taxonomy.workspace.service.WorkspaceFetchAuthority;
import com.taxonomy.workspace.service.WorkspaceFetchAuthority.Grant;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
public class HistoryIndexGrantAdapter implements HistoryIndexGrant {
    private final WorkspaceFetchAuthority actor;
    private final PrincipalIdentityService identities;

    public HistoryIndexGrantAdapter(WorkspaceFetchAuthority actor, PrincipalIdentityService identities) {
        this.actor = actor;
        this.identities = identities;
    }

    @Override
    public String currentActor(String username) {
        return actor.currentActor(username);
    }

    @Override
    public Grant authorize(String username, boolean purge) {
        String principal = currentActor(username);
        var login = SecurityContextHolder.getContext().getAuthentication();
        if (!ApiAccessPolicy.hasPermission(login, permission(purge))) throw denied();
        if (login.getPrincipal() instanceof PrincipalUserDetails local) {
            return new Grant(principal, "LOCAL", identities.currentLocalAuthority(local.principalId(), username).bindingKey());
        }
        String binding = WorkspaceFetchAuthorityAdapter.externalBinding(login);
        return new Grant(principal, principal.equals(binding) ? "LEGACY_OIDC" : "OIDC", binding);
    }

    @Override
    public void verify(Grant original, String username, boolean purge, boolean background) {
        if (!background) {
            if (!original.equals(authorize(username, purge))) throw denied();
            return;
        }
        if ("LEGACY_OIDC".equals(original.loginKind())) throw new WorkspaceFetchAuthority.FreshLoginRequired();
        var id = new PrincipalId(UUID.fromString(original.principalId()));
        if (!"LOCAL".equals(original.loginKind())) {
            if (!identities.isEnabled(id)) throw denied();
            throw new WorkspaceFetchAuthority.FreshLoginRequired();
        }
        var local = identities.currentLocalAuthority(id, username);
        if (!local.bindingKey().equals(original.bindingKey()) || permission(purge).roles().stream()
                .noneMatch(role -> local.roles().contains("ROLE_" + role))) throw denied();
    }

    private static ApiPermission permission(boolean purge) { return purge ? ApiPermission.ARCHITECTURE_DELETE : ApiPermission.ARCHITECTURE_WRITE; }
    private static AccessDeniedException denied() { return new AccessDeniedException("History index permission is unavailable"); }
}
