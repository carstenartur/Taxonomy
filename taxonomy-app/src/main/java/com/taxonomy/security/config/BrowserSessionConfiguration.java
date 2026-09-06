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
        // The standard composite keeps session-fixation protection and registers
        // form/OIDC logins. The standard filter updates last-request timestamps.
        // -1 preserves unlimited concurrent sessions; no eviction UI is exposed.
        http.sessionManagement(session -> session.maximumSessions(-1).sessionRegistry(registry));
    }
}
