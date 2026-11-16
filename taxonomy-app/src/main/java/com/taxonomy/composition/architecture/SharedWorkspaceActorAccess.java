package com.taxonomy.composition.architecture;

import com.taxonomy.backup.IdentityBinding;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.access.ApiAccessPolicy;
import com.taxonomy.security.access.ApiPermission;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.security.keycloak.KeycloakPrincipalMode;
import com.taxonomy.security.model.PrincipalBinding;
import com.taxonomy.security.model.StablePrincipal;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.workspace.service.WorkspaceActorAccess;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Reuses the current authoritative identity, including revocation of an old browser session. */
@Component
public final class SharedWorkspaceActorAccess implements WorkspaceActorAccess {
    private final PrincipalIdentityService identities;
    private final Optional<KeycloakPrincipalMode> externalMode;

    public SharedWorkspaceActorAccess(PrincipalIdentityService identities) {
        this(identities, Optional.empty());
    }

    @Autowired
    public SharedWorkspaceActorAccess(PrincipalIdentityService identities, Optional<KeycloakPrincipalMode> externalMode) {
        this.identities = identities;
        this.externalMode = externalMode;
    }

    @Override public void requireActive(String username) {
        identities.requireCurrent(matchingLogin(username));
    }

    @Override public Optional<UUID> currentNavigationPrincipal(String username) {
        var login = SecurityContextHolder.getContext().getAuthentication();
        if (login == null || !login.isAuthenticated()
                || !(login instanceof StablePrincipal || login.getPrincipal() instanceof StablePrincipal)) {
            return Optional.empty();
        }
        return currentOwnerPrincipal(username);
    }

    @Override public Optional<UUID> currentOwnerPrincipal(String username) {
        var login = matchingLogin(username);
        if (externalMode.isEmpty() && login.getPrincipal() instanceof PrincipalUserDetails) {
            return Optional.of(identities.requireCurrent(login).value());
        }
        if (login instanceof JwtAuthenticationToken jwt) {
            return externalOwner(login, jwt.getToken().getIssuer(), jwt.getToken().getSubject(), jwt.getToken().getExpiresAt());
        }
        if (login instanceof OAuth2AuthenticationToken && login.getPrincipal() instanceof OidcUser oidc) {
            return externalOwner(login, oidc.getIssuer(), oidc.getSubject(), oidc.getIdToken().getExpiresAt());
        }
        throw denied();
    }

    @Override public AnalysisOwner admitAnalysisOwner(String username, UserWorkspace workspace) {
        if (workspace == null || workspace.getOwnerPrincipalId() == null) {
            return WorkspaceActorAccess.super.admitAnalysisOwner(username, workspace);
        }
        var login = matchingLogin(username);
        UUID id = currentOwnerPrincipal(username).orElseThrow(SharedWorkspaceActorAccess::denied);
        if (!id.toString().equals(workspace.getOwnerPrincipalId()) || !username.equals(workspace.getUsername())
                || !ApiAccessPolicy.hasPermission(login, ApiPermission.ANALYSIS_RUN)) throw denied();
        var principal = new PrincipalId(id);
        if (login.getPrincipal() instanceof PrincipalUserDetails) {
            var admitted = identities.currentLocalAuthority(principal, username);
            return AnalysisOwner.forWorkspace(username, workspace.getWorkspaceId(), id, () -> {
                var current = identities.currentLocalAuthority(principal, username);
                if (!admitted.bindingKey().equals(current.bindingKey()) || ApiPermission.ANALYSIS_RUN.roles().stream()
                        .noneMatch(role -> current.roles().contains("ROLE_" + role))) throw denied();
            });
        }
        if (login instanceof JwtAuthenticationToken jwt) {
            return externalAnalysisOwner(username, workspace.getWorkspaceId(), principal, jwt.getToken().getIssuer(),
                    jwt.getToken().getSubject(), jwt.getToken().getExpiresAt());
        }
        if (login.getPrincipal() instanceof OidcUser oidc) {
            return externalAnalysisOwner(username, workspace.getWorkspaceId(), principal, oidc.getIssuer(),
                    oidc.getSubject(), oidc.getIdToken().getExpiresAt());
        }
        throw denied();
    }

    private AnalysisOwner externalAnalysisOwner(String scope, String workspace, PrincipalId principal,
            URL issuer, String subject, Instant expiry) {
        String key = PrincipalBinding.key(new IdentityBinding(IdentityBinding.Kind.OIDC, issuer.toString(), subject));
        return AnalysisOwner.forWorkspace(scope, workspace, principal.value(), () -> {
            if (!expiry.isAfter(Instant.now()) || !identities.isCurrentExternalBinding(principal, key)
                    || identities.find(principal).filter(p -> p.enabled() && scope.equals(p.scopeKey())).isEmpty()) throw denied();
        });
    }

    private Optional<UUID> externalOwner(Authentication login, URL issuer, String subject, Instant expiry) {
        if (issuer == null || subject == null || subject.isBlank()
                || expiry == null || !expiry.isAfter(Instant.now())) throw denied();
        if (login instanceof StablePrincipal || login.getPrincipal() instanceof StablePrincipal) {
            if (externalMode.orElse(null) != KeycloakPrincipalMode.STABLE) throw denied();
            return Optional.of(identities.requireCurrent(login).value());
        }
        if (externalMode.orElse(null) != KeycloakPrincipalMode.LEGACY) throw denied();
        String key = PrincipalBinding.key(new IdentityBinding(IdentityBinding.Kind.OIDC, issuer.toString(), subject));
        if (identities.hasRegisteredBinding(key)) throw denied();
        return Optional.empty();
    }

    private static Authentication matchingLogin(String username) {
        var login = SecurityContextHolder.getContext().getAuthentication();
        if (login == null || !login.isAuthenticated() || username == null || !username.equals(login.getName())) throw denied();
        return login;
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Verified active workspace principal required");
    }
}
