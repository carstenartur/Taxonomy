package com.taxonomy.shared.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the LLM quota filter exclusively inside Spring Security.
 *
 * <p>Registering the {@link RateLimitFilter} as an ordinary servlet filter as
 * well would execute it before authentication and a second time inside the
 * security chain. The security configurations place it after authenticated
 * principal wrapping for both form-login and Keycloak modes.</p>
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitFilterConfiguration {

    @Bean
    FilterRegistrationBean<RateLimitFilter> disableContainerRateLimitFilter(
            RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
