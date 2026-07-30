package com.taxonomy.shared.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.List;

/**
 * Configures the {@link MessageSource} for internationalization.
 * Message bundles are stored under {@code classpath:i18n/}.
 */
@Configuration
public class I18nConfig {

    /**
     * Canonical default-locale bundle names. The browser translation endpoint
     * uses the same list to enumerate every key rather than silently exposing
     * only the original monolithic bundle.
     */
    public static final List<String> MESSAGE_BASENAMES = List.of(
            "messages",
            "messages_document_import",
            "messages_jgit_storage",
            "messages_observability",
            "messages_task_focus");

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasenames(MESSAGE_BASENAMES.stream()
                .map(name -> "classpath:i18n/" + name)
                .toArray(String[]::new));
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return ms;
    }
}
