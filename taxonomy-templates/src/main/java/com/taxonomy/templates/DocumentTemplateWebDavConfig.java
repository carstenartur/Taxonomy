package com.taxonomy.templates;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the virtual DOTX-only WebDAV collection. */
@Configuration
public class DocumentTemplateWebDavConfig {

    @Bean
    ServletRegistrationBean<DocumentTemplateWebDavServlet> documentTemplateWebDavServlet(
            DocumentTemplateService templates,
            DocumentTemplateWebDavLockManager locks) {
        ServletRegistrationBean<DocumentTemplateWebDavServlet> registration =
                new ServletRegistrationBean<>(
                        new DocumentTemplateWebDavServlet(templates, locks),
                        "/dav/templates/*");
        registration.setName("documentTemplateWebDavServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
