package com.taxonomy.security.webdav;

import com.taxonomy.backup.IdentityBinding;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.keycloak.KeycloakPrincipalMode;
import com.taxonomy.security.model.PrincipalBinding;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.model.StablePrincipal;
import com.taxonomy.security.service.PrincipalIdentityService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
class WebDavCredentialAuthority {
    record Owner(String actor, String binding, Instant assertionExpiresAt) { }

    private final PrincipalIdentityService identities;
    private final KeycloakPrincipalMode keycloakMode;

    WebDavCredentialAuthority(PrincipalIdentityService identities, ObjectProvider<KeycloakPrincipalMode> mode) {
        this.identities = identities;
        this.keycloakMode = mode.getIfAvailable();
    }

    Owner owner(Authentication login) {
        if (keycloakMode == null && login.getPrincipal() instanceof PrincipalUserDetails) {
            var id = identities.requireCurrent(login);
            var local = identities.currentLocalAuthority(id, login.getName());
            return new Owner(id.value().toString(), local.bindingKey(), null);
        }
        if (keycloakMode == null) throw denied();
        IdentityBinding external;
        Instant expires;
        if (login instanceof JwtAuthenticationToken jwt && jwt.getToken().getIssuer() != null) {
            external = oidc(jwt.getToken().getIssuer().toString(), jwt.getToken().getSubject());
            expires = jwt.getToken().getExpiresAt();
        } else if (login instanceof OAuth2AuthenticationToken && login.getPrincipal() instanceof OidcUser oidc
                && oidc.getIssuer() != null) {
            external = oidc(oidc.getIssuer().toString(), oidc.getSubject());
            expires = oidc.getIdToken().getExpiresAt();
        } else throw denied();
        if (expires == null) throw denied();
        String binding = PrincipalBinding.key(external);
        if (login instanceof StablePrincipal || login.getPrincipal() instanceof StablePrincipal) {
            if (keycloakMode != KeycloakPrincipalMode.STABLE) throw denied();
            return new Owner(identities.requireCurrent(login).value().toString(), binding, expires);
        }
        if (keycloakMode != KeycloakPrincipalMode.LEGACY || identities.hasRegisteredBinding(binding)) throw denied();
        return new Owner(binding, binding, expires);
    }

    /** Null means the retained login no longer exists in this installation/mode. */
    List<String> currentRoles(WebDavApplicationCredential credential, List<String> granted) {
        String actor = credential.getOwnerId();
        String binding = credential.getOwnerBinding();
        if (actor == null || binding == null) return null;
        if (actor.equals(binding)) {
            return keycloakMode == KeycloakPrincipalMode.LEGACY && !identities.hasRegisteredBinding(binding) ? granted : null;
        }
        PrincipalId id;
        try { id = new PrincipalId(UUID.fromString(actor)); }
        catch (IllegalArgumentException invalid) { return null; }
        if (keycloakMode == KeycloakPrincipalMode.STABLE) {
            return identities.isCurrentExternalBinding(id, binding) ? granted : null;
        }
        if (keycloakMode != null) return null;
        try {
            var local = identities.currentLocalAuthority(id, credential.getUsername());
            return binding.equals(local.bindingKey()) ? granted.stream().filter(local.roles()::contains).toList() : null;
        } catch (AccessDeniedException removed) {
            return null;
        }
    }

    private static IdentityBinding oidc(String issuer, String subject) {
        if (issuer.isBlank() || issuer.length() > 2048 || subject == null || subject.isBlank()
                || subject.length() > 1024) throw denied();
        return new IdentityBinding(IdentityBinding.Kind.OIDC, issuer, subject);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("A current verified issuing account is required");
    }
}
