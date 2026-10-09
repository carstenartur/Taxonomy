package com.taxonomy.shared.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.shared.config.I18nConfig;
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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Exposes all GUI message-bundle keys as a JSON map so that client-side
 * JavaScript can perform translations via {@code TaxonomyI18n.t('key')}.
 *
 * <p>Response is cached by the browser using standard HTTP cache headers.
 */
@RestController
@Tag(name = "Localization")
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
    @Operation(summary = "Read translated GUI messages",
            description = "Resolves the default message-bundle keys for a BCP-47 locale such as de or en. Returns a key-to-message JSON object using the configured message-source fallback. Does not change the account language or LLM provider language.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public Map<String, String> getTranslations(@Parameter(description = "BCP-47 language tag, for example de or en") @PathVariable String locale) {
        Locale resolved = Locale.forLanguageTag(locale);
        Map<String, String> messages = new HashMap<>();

        for (String key : defaultMessageKeys()) {
            messages.put(key, messageSource.getMessage(key, null, key, resolved));
        }
        return messages;
    }

    private Set<String> defaultMessageKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (String basename : I18nConfig.MESSAGE_BASENAMES) {
            ClassPathResource resource = new ClassPathResource(
                    "i18n/" + basename + ".properties");
            if (!resource.exists()) {
                log.warn("Configured i18n bundle is missing: {}", resource.getPath());
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                Properties properties = new Properties();
                properties.load(in);
                keys.addAll(properties.stringPropertyNames());
            } catch (IOException exception) {
                log.warn("Failed to load i18n bundle {}: {}",
                        resource.getPath(), exception.getMessage());
            }
        }
        return keys;
    }
}
