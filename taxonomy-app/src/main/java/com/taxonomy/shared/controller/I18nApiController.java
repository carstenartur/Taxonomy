package com.taxonomy.shared.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Exposes all GUI message-bundle keys as a JSON map so that client-side
 * JavaScript can perform translations via {@code TaxonomyI18n.t('key')}.
 *
 * <p>Response is cached by the browser using standard HTTP cache headers.
 */
@RestController
@RequestMapping("/api/i18n")
public class I18nApiController {

    private static final Logger log = LoggerFactory.getLogger(I18nApiController.class);

    private final MessageSource messageSource;

    public I18nApiController(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Returns all message keys and their resolved values for the given locale.
     *
     * @param locale BCP-47 language tag, e.g. {@code en} or {@code de}
     * @return key-value map of all messages
     */
    @GetMapping("/{locale}")
    public Map<String, String> getTranslations(@PathVariable String locale) {
        Locale resolved = Locale.forLanguageTag(locale);
        Map<String, String> messages = new HashMap<>();

        // Load all keys from the default (English) properties file and resolve
        // each key for the requested locale via the MessageSource.
        try {
            Properties defaultProps = new Properties();
            ClassPathResource resource = new ClassPathResource("i18n/messages.properties");
            if (resource.exists()) {
                try (InputStream in = resource.getInputStream()) {
                    defaultProps.load(in);
                }
            }
            for (String key : defaultProps.stringPropertyNames()) {
                messages.put(key, messageSource.getMessage(key, null, key, resolved));
            }
        } catch (IOException e) {
            log.warn("Failed to load i18n properties: {}", e.getMessage());
        }

        return messages;
    }
}
