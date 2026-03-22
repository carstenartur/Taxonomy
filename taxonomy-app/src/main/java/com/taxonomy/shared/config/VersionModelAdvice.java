package com.taxonomy.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the application version as a Thymeleaf model attribute so that
 * templates can render the correct version server-side without hardcoding.
 */
@ControllerAdvice
public class VersionModelAdvice {

    @Value("${app.display-version:unknown}")
    private String appVersion;

    @ModelAttribute("appVersion")
    public String appVersion() {
        return appVersion;
    }
}
