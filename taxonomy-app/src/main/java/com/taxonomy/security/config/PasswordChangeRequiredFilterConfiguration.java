package com.taxonomy.security.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Keeps local password-change enforcement exclusively inside Spring Security. */
@Configuration(proxyBeanMethods = false)
@Profile("!keycloak")
public class PasswordChangeRequiredFilterConfiguration {

    @Bean
    FilterRegistrationBean<PasswordChangeRequiredFilter>
            disableContainerPasswordChangeRequiredFilter(
                    PasswordChangeRequiredFilter filter) {
        FilterRegistrationBean<PasswordChangeRequiredFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
