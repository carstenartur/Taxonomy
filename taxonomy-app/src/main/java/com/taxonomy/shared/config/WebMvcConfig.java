package com.taxonomy.shared.config;

import com.taxonomy.versioning.controller.DslWorkspacePreResolutionInterceptor;
import com.taxonomy.workspace.service.ExplicitWorkspacePinValidationInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.Locale;

/**
 * Web MVC configuration for internationalization and request-bound workspace
 * isolation.
 *
 * <p>Locale resolution priority:
 * <ol>
 *   <li>{@code ?lang=de} query parameter (persisted to cookie)</li>
 *   <li>{@code lang} cookie</li>
 *   <li>{@code Accept-Language} header</li>
 *   <li>Fallback: English</li>
 * </ol>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ExplicitWorkspacePinValidationInterceptor pinValidationInterceptor;
    private final DslWorkspacePreResolutionInterceptor dslWorkspaceInterceptor;

    public WebMvcConfig(
            ExplicitWorkspacePinValidationInterceptor pinValidationInterceptor,
            DslWorkspacePreResolutionInterceptor dslWorkspaceInterceptor) {
        this.pinValidationInterceptor = pinValidationInterceptor;
        this.dslWorkspaceInterceptor = dslWorkspaceInterceptor;
    }

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("lang");
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());

        // Any explicit tab pin is an authorization-sensitive request identity.
        // Validate it for every API before endpoint-specific exception handling
        // can convert a denied pin into a shared or otherwise ambiguous context.
        // Workspace switching is the recovery path from an obsolete tab pin and
        // validates the requested target independently in WorkspaceManager.
        registry.addInterceptor(pinValidationInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/workspace/*/switch");

        registry.addInterceptor(dslWorkspaceInterceptor)
                .addPathPatterns(
                        "/api/dsl/current",
                        "/api/dsl/history",
                        "/api/dsl/git/head",
                        "/api/dsl/hypotheses/**",
                        "/api/analyze",
                        "/api/search/graph",
                        "/api/projects/**",
                        "/api/solutions/**",
                        "/api/products/**");
    }
}
