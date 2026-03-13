package com.taxonomy;

import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * Test-only configuration that:
 * <ol>
 *   <li>Applies Spring Security's MockMvc configurer so that {@code @WithMockUser}
 *       is honoured (Spring Boot 4 no longer auto-applies this).</li>
 *   <li>Adds a CSRF token to every request by default, so existing POST/PUT/DELETE
 *       tests do not need individual {@code .with(csrf())} calls.</li>
 * </ol>
 * Picked up automatically by component scanning because it resides in the same base package
 * as {@link TaxonomyApplication}.
 */
@Configuration
public class TestSecuritySupport {

    @Bean
    MockMvcBuilderCustomizer securityMockMvcCustomizer() {
        return builder -> {
            builder.apply(SecurityMockMvcConfigurers.springSecurity());
            builder.defaultRequest(MockMvcRequestBuilders.get("/").with(csrf()));
        };
    }
}
