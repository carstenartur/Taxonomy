package com.taxonomy.security.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(annotations = Controller.class)
public class AccountMenuAdvice {

    @ModelAttribute("applicationAccount")
    Account account(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            return new Account(oidcUser.getPreferredUsername(), true);
        }
        return new Account(authentication.getName(), false);
    }

    public record Account(String displayName, boolean singleSignOn) { }
}
