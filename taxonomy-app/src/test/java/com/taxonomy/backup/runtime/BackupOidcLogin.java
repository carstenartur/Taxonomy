package com.taxonomy.backup.runtime;

import com.taxonomy.security.keycloak.PrincipalOidcUser;
import com.taxonomy.security.model.AppPrincipal;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/** Supplies the authenticated session identity for a registered OIDC binding. */
final class BackupOidcLogin {
    private BackupOidcLogin() {}

    static RequestPostProcessor forPrincipal(AppPrincipal principal, String issuer, String subject) {
        var authorities = AuthorityUtils.createAuthorityList("ROLE_USER");
        var issuedAt = Instant.now();
        var idToken = new OidcIdToken("fixture-id-token", issuedAt, issuedAt.plusSeconds(300),
                Map.of("iss", issuer, "sub", subject));
        var user = new PrincipalOidcUser(authorities, new DefaultOidcUser(authorities, idToken), principal);
        return authentication(new OAuth2AuthenticationToken(user, authorities, "keycloak"));
    }
}
