package com.taxonomy.shared.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps the LLM quota filter exclusively inside the Spring Security chains. */
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
