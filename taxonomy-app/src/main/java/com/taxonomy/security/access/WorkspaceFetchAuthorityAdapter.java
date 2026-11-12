package com.taxonomy.security.access;

import com.taxonomy.backup.IdentityBinding;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.keycloak.PrincipalJwtAuthenticationToken;
import com.taxonomy.security.keycloak.PrincipalOidcUser;
import com.taxonomy.security.model.PrincipalBinding;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.workspace.service.WorkspaceFetchAuthority;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
public class WorkspaceFetchAuthorityAdapter implements WorkspaceFetchAuthority {
    private final PrincipalIdentityService identities;

    public WorkspaceFetchAuthorityAdapter(PrincipalIdentityService identities) { this.identities = identities; }

    @Override public String currentActor(String username) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var principal = identities.requireCurrent(authentication);
        if (!username.equals(authentication.getName())) throw denied();
        return principal.value().toString();
    }

    @Override public Grant authorize(String username) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = currentActor(username);
        if (!ApiAccessPolicy.hasPermission(authentication, ApiPermission.ANALYSIS_RUN)) throw denied();
        if (authentication.getPrincipal() instanceof PrincipalUserDetails local) {
            return new Grant(actor, "LOCAL", identities.currentLocalAuthority(local.principalId(), username).bindingKey());
        }
        if (authentication instanceof PrincipalJwtAuthenticationToken jwt) {
            return external(actor, jwt.getToken().getIssuer().toString(), jwt.getToken().getSubject());
        }
        if (authentication.getPrincipal() instanceof PrincipalOidcUser oidc) {
            return external(actor, oidc.getIssuer().toString(), oidc.getSubject());
        }
        throw denied();
    }

    private static Grant external(String actor, String issuer, String subject) {
        return new Grant(actor, "OIDC", PrincipalBinding.key(new IdentityBinding(IdentityBinding.Kind.OIDC, issuer, subject)));
    }

    @Override public void checkPublication(Grant original, String username, boolean unattended) {
        if (!unattended) {
            if (!authorize(username).principalId().equals(original.principalId())) throw denied();
            return;
        }
        var id = new PrincipalId(UUID.fromString(original.principalId()));
        if (!"LOCAL".equals(original.loginKind())) {
            if (!identities.isEnabled(id)) throw denied();
            // External role/scope grants are in a verified login assertion. A worker cannot renew that assertion.
            throw new FreshLoginRequired();
        }
        var local = identities.currentLocalAuthority(id, username);
        if (!local.bindingKey().equals(original.bindingKey()) || ApiPermission.ANALYSIS_RUN.roles().stream()
                .noneMatch(role -> local.roles().contains("ROLE_" + role))) throw denied();
    }

    private static AccessDeniedException denied() { return new AccessDeniedException("Workspace fetch permission is unavailable"); }
}
