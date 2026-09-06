package com.taxonomy.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/** Reuse Spring Security's local session lifecycle; do not impose a login limit. */
@Configuration(proxyBeanMethods = false)
public class BrowserSessionConfiguration {
    @Bean
    SessionRegistry browserSessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    HttpSessionEventPublisher browserSessionEvents() {
        return new HttpSessionEventPublisher();
    }

    static void configure(HttpSecurity http, SessionRegistry registry) throws Exception {
        // Register through the form/OIDC authentication filters, not the implicit
        // SessionManagementFilter, which would also persist Basic/Bearer logins.
        // The concurrency DSL retains fixation protection, last-request updates
        // and unlimited browser sessions without opting into implicit authentication.
        http.sessionManagement(session -> session.requireExplicitAuthenticationStrategy(true)
                .sessionConcurrency(concurrency -> concurrency.maximumSessions(-1).sessionRegistry(registry)));
    }
}
