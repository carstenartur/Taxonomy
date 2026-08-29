package com.taxonomy.shared.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.List;

/** Configures the classpath message bundles used by Thymeleaf and the browser API. */
@Configuration
public class I18nConfig {

    public static final List<String> MESSAGE_BASENAMES = List.of(
            "messages_onboarding",
            "messages",
            "messages_catalogue_overlay",
            "messages_document_import",
            "messages_document_templates",
            "messages_document_template_detail",
            "messages_webdav_credentials",
            "messages_security",
            "messages_jgit_storage",
            "messages_observability",
            "messages_portfolio",
            "messages_search",
            "messages_task_focus");

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source =
                new ReloadableResourceBundleMessageSource();
        source.setBasenames(MESSAGE_BASENAMES.stream()
                .map(name -> "classpath:i18n/" + name)
                .toArray(String[]::new));
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
