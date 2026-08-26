package com.taxonomy.security.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Keeps login lockout exclusively inside the form-login Spring Security chain. */
@Configuration(proxyBeanMethods = false)
@Profile("!keycloak")
@ConditionalOnProperty(name = "taxonomy.security.login-rate-limit.enabled",
        havingValue = "true", matchIfMissing = true)
public class LoginRateLimitFilterConfiguration {

    @Bean
    FilterRegistrationBean<LoginRateLimitFilter> disableContainerLoginRateLimitFilter(
            LoginRateLimitFilter filter) {
        FilterRegistrationBean<LoginRateLimitFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
