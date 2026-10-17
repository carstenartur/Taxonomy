package com.taxonomy.security.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Localized page; Spring Security continues to process credentials and redirects. */
@Controller
@Profile("!keycloak")
public class LocalLoginController {
    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
