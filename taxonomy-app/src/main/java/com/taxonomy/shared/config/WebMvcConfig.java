package com.taxonomy.shared.config;

import com.taxonomy.versioning.controller.DslWorkspacePreResolutionInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.Locale;

/** Web MVC configuration for internationalization and workspace isolation. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final DslWorkspacePreResolutionInterceptor workspaceInterceptor;

    public WebMvcConfig(DslWorkspacePreResolutionInterceptor workspaceInterceptor) {
        this.workspaceInterceptor = workspaceInterceptor;
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
        registry.addInterceptor(workspaceInterceptor)
                .addPathPatterns(
                        "/api/dsl/**",
                        "/api/analyze",
                        "/api/search/graph",
                        "/api/relations/**",
                        "/api/node/*/relations",
                        "/api/proposals/**",
                        "/api/node/*/proposals",
                        "/api/import/**",
                        "/api/context/**",
                        "/api/report/**",
                        "/api/projects/**",
                        "/api/solutions/**",
                        "/api/products/**",
                        "/api/coverage/**");
    }
}
