package com.taxonomy.security.webdav;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the credential filter only inside Spring Security, never as a duplicate servlet filter. */
@Configuration(proxyBeanMethods = false)
public class WebDavApplicationCredentialConfiguration {

    @Bean
    WebDavApplicationCredentialFilter webDavApplicationCredentialFilter(
            WebDavApplicationCredentialService credentials) {
        return new WebDavApplicationCredentialFilter(credentials);
    }

    @Bean
    FilterRegistrationBean<WebDavApplicationCredentialFilter>
            disableContainerWebDavApplicationCredentialFilter(
                    WebDavApplicationCredentialFilter filter) {
        FilterRegistrationBean<WebDavApplicationCredentialFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
