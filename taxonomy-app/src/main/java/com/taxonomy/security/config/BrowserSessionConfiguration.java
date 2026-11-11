package com.taxonomy.security.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
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
        http.with(new BrowserNavigationCacheConfigurer(), Customizer.withDefaults());
        // Register through the form/OIDC authentication filters, not the implicit
        // SessionManagementFilter, which would also persist HTTP Basic and bearer-token logins.
        // The concurrency DSL retains fixation protection, last-request updates
        // and unlimited browser sessions without opting into implicit authentication.
        http.sessionManagement(session -> session.requireExplicitAuthenticationStrategy(true)
                .sessionConcurrency(concurrency -> concurrency.maximumSessions(-1).sessionRegistry(registry)));
    }

    private static class BrowserNavigationCacheConfigurer
            extends AbstractHttpConfigurer<BrowserNavigationCacheConfigurer, HttpSecurity> {
        @Override
        public void init(HttpSecurity http) {
            var configuredCache = http.getSharedObject(RequestCache.class);
            http.setSharedObject(RequestCache.class, new BrowserNavigationCache(configuredCache));
        }
    }

    /** Keep Spring's normal saved navigation while parallel API challenges leave the login session alone. */
    private record BrowserNavigationCache(RequestCache delegate) implements RequestCache {
        @Override
        public void saveRequest(HttpServletRequest request, HttpServletResponse response) {
            if (!SecurityConfig.isRestClient(request)) {
                delegate.saveRequest(request, response);
            }
        }

        @Override
        public SavedRequest getRequest(HttpServletRequest request, HttpServletResponse response) {
            return delegate.getRequest(request, response);
        }

        @Override
        public HttpServletRequest getMatchingRequest(HttpServletRequest request, HttpServletResponse response) {
            return delegate.getMatchingRequest(request, response);
        }

        @Override
        public void removeRequest(HttpServletRequest request, HttpServletResponse response) {
            delegate.removeRequest(request, response);
        }
    }
}
