package com.taxonomy.security.access;

import com.taxonomy.backup.IdentityBinding;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.model.PrincipalBinding;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.model.StablePrincipal;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.workspace.service.WorkspaceFetchAuthority;
import com.taxonomy.workspace.service.WorkspacePublicationAuthority;
import com.taxonomy.workspace.service.RepositoryScope;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
public class WorkspaceFetchAuthorityAdapter implements WorkspaceFetchAuthority, WorkspacePublicationAuthority {
    private final PrincipalIdentityService identities;

    public WorkspaceFetchAuthorityAdapter(PrincipalIdentityService identities) { this.identities = identities; }

    @Override public String currentActor(String username) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !username.equals(authentication.getName())) throw denied();
        if (authentication instanceof StablePrincipal || authentication.getPrincipal() instanceof StablePrincipal) {
            return identities.requireCurrent(authentication).value().toString();
        }
        // Legacy OIDC keeps its configured workspace name. Only the verified issuer/subject identifies a new operation.
        return externalBinding(authentication);
    }

    @Override public Grant authorize(String username, RepositoryScope scope) {
        return authorize(username, permission(scope));
    }

    @Override public Grant authorizePublication(String username) {
        return authorize(username, ApiPermission.ARCHITECTURE_PUBLISH);
    }

    private Grant authorize(String username, ApiPermission required) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = currentActor(username);
        if (!ApiAccessPolicy.hasPermission(authentication, required)) throw denied();
        if (authentication.getPrincipal() instanceof PrincipalUserDetails local) {
            return new Grant(actor, "LOCAL", identities.currentLocalAuthority(local.principalId(), username).bindingKey());
        }
        String binding = externalBinding(authentication);
        return new Grant(actor, actor.equals(binding) ? "LEGACY_OIDC" : "OIDC", binding);
    }

    private static String externalBinding(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.getToken().getIssuer() != null) {
            return binding(jwt.getToken().getIssuer().toString(), jwt.getToken().getSubject());
        }
        if (authentication instanceof OAuth2AuthenticationToken && authentication.getPrincipal() instanceof OidcUser oidc
                && oidc.getIssuer() != null) return binding(oidc.getIssuer().toString(), oidc.getSubject());
        throw denied();
    }

    private static String binding(String issuer, String subject) {
        if (subject == null || subject.isBlank()) throw denied();
        return PrincipalBinding.key(new IdentityBinding(IdentityBinding.Kind.OIDC, issuer, subject));
    }

    @Override public void checkPublication(Grant original, String username, RepositoryScope scope, boolean unattended) {
        checkGrant(original, username, permission(scope), unattended);
    }

    @Override public void checkPublicationGrant(Grant original, String username, boolean unattended) {
        checkGrant(original, username, ApiPermission.ARCHITECTURE_PUBLISH, unattended);
    }

    private void checkGrant(Grant original, String username, ApiPermission required, boolean unattended) {
        if (!unattended) {
            if (!authorize(username, required).equals(original)) throw denied();
            return;
        }
        if ("LEGACY_OIDC".equals(original.loginKind())) throw new FreshLoginRequired();
        var id = new PrincipalId(UUID.fromString(original.principalId()));
        if (!"LOCAL".equals(original.loginKind())) {
            if (!identities.isEnabled(id)) throw denied();
            // External role/scope grants are in a verified login assertion. A worker cannot renew that assertion.
            throw new FreshLoginRequired();
        }
        var local = identities.currentLocalAuthority(id, username);
        if (!local.bindingKey().equals(original.bindingKey()) || required.roles().stream()
                .noneMatch(role -> local.roles().contains("ROLE_" + role))) throw denied();
    }

    private static ApiPermission permission(RepositoryScope scope) {
        return switch (scope) {
            case WORKSPACE -> ApiPermission.ANALYSIS_RUN;
            case CENTRAL_WRITE -> ApiPermission.ARCHITECTURE_WRITE;
            default -> throw denied();
        };
    }

    private static AccessDeniedException denied() { return new AccessDeniedException("Workspace fetch permission is unavailable"); }
}
