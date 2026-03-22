package com.taxonomy.security.keycloak;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * In the Keycloak profile, redirects the {@code /change-password} page to the
 * Keycloak Account Console's security/sign-in settings, where users can manage
 * their credentials directly in the identity provider.
 */
@Controller
@Profile("keycloak")
public class KeycloakAccountRedirectController {

    @Value("${taxonomy.keycloak.admin-console-url:http://localhost:8180}")
    private String keycloakUrl;

    @GetMapping("/change-password")
    public String redirectToKeycloak() {
        return "redirect:" + keycloakUrl + "/realms/taxonomy/account/#/security/signingin";
    }
}
