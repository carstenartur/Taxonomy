package com.taxonomy.shared.controller;

import com.taxonomy.shared.config.I18nConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Render the actual views and follow their help links through the production renderer. */
class ContextualHelpTest {
    private static final Map<String, String> VIEWS = Map.ofEntries(
            Map.entry("projects", "PROJECT_REQUIREMENT_PORTFOLIO/portfolio-projects"),
            Map.entry("requirement-detail", "PROJECT_REQUIREMENT_PORTFOLIO/requirement-clarification-workflow"),
            Map.entry("portfolio-import", "PROJECT_REQUIREMENT_PORTFOLIO/portfolio-import"),
            Map.entry("portfolio-matrices", "PROJECT_REQUIREMENT_PORTFOLIO/portfolio-matrices"),
            Map.entry("portfolio-versioning", "PROJECT_REQUIREMENT_PORTFOLIO/portfolio-versioning"),
            Map.entry("portfolio-reports", "PROJECT_REQUIREMENT_PORTFOLIO/portfolio-reports"),
            Map.entry("architecture-editor", "ARCHITECTURE_EDITOR"),
            Map.entry("architecture-workbench", "USER_GUIDE/architecture-source-context"),
            Map.entry("integrations", "ARCHITECTURE_EDITOR/tool-integrations"),
            Map.entry("document-templates", "DOCUMENT_TEMPLATES"),
            Map.entry("document-template-detail", "DOCUMENT_TEMPLATES/template-versions"),
            Map.entry("document-template-local-edit", "DOCUMENT_TEMPLATES/template-editing"),
            Map.entry("document-template-part-comparison", "DOCUMENT_TEMPLATES/template-versions"),
            Map.entry("document-template-restore", "DOCUMENT_TEMPLATES/template-versions"),
            Map.entry("webdav-credentials", "DOCUMENT_TEMPLATES/template-access"),
            Map.entry("browser-sessions", "SECURITY/browser-sessions"),
            Map.entry("backup-jobs", "OPERATIONS_GUIDE/portable-exports"),
            Map.entry("fragments/user-management-layout", "SECURITY/user-management"));
    // Selection happens in the source view before Thymeleaf resolves a shared header's th:replace.
    private static final Set<String> HEADER_VIEWS = Set.of(
            "projects", "requirement-detail", "portfolio-versioning",
            "architecture-editor", "architecture-workbench", "integrations");

    @ParameterizedTest
    @ValueSource(strings = {"en", "de"})
    void standaloneViewsOpenTheirOwnLocalizedHelpWithoutReplacingWork(String language) {
        for (var view : VIEWS.entrySet()) {
            String selector = HEADER_VIEWS.contains(view.getKey()) ? "header" : "nav";
            String html = render(view.getKey(), selector, language);
            assertTrue(html.contains("/taxonomy/?lang=" + language + "#help/" + view.getValue() + "\""), view.getKey());
            assertTrue(html.contains("target=\"_blank\""), view.getKey() + " must preserve unsaved work");
            assertTrue(html.contains("rel=\"noopener noreferrer\""), view.getKey());
            assertTrue(html.contains(language.equals("de") ? "Hilfe zu dieser Sicht" : "Help for this view"), view.getKey());
            assertTarget(view.getValue(), language);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "de"})
    void workspacePanesAndAllTaxonomyModesHaveRealSectionTargets(String language) {
        String header = render("index", "header", language);
        assertTrue(header.contains("/taxonomy/?lang=" + language + "#help/USER_GUIDE/guide-section-4\""),
                "Analysis help remains in the shared header without a duplicate pane action");
        assertTrue(header.contains("target=\"_blank\""), "Opening analysis help must preserve the active work");
        String html = header + render("index", ".view-help", language);
        for (String target : Set.of("USER_GUIDE/guide-section-4", "USER_GUIDE/guide-section-7",
                "USER_GUIDE/guide-section-13", "USER_GUIDE/guide-section-10", "USER_GUIDE/guide-section-8",
                "USER_GUIDE/guide-section-19", "USER_GUIDE/guide-section-18", "USER_GUIDE/guide-section-21", "PREFERENCES")) {
            assertTrue(html.contains("#help/" + target + "\""), "Workspace help missing: " + target);
            assertTarget(target, language);
        }
        for (String mode : Set.of("list", "tabs", "sunburst", "tree", "decision", "summary")) {
            assertTarget("USER_GUIDE/taxonomy-view-" + mode, language);
        }
    }

    private String render(String template, String selector, String language) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver); engine.setMessageSource(new I18nConfig().messageSource());
        var servlet = new MockServletContext(); servlet.setContextPath("/taxonomy");
        var request = new MockHttpServletRequest(servlet);
        request.setContextPath("/taxonomy"); request.setRequestURI("/taxonomy/");
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(request, new MockHttpServletResponse());
        var context = new WebContext(exchange, Locale.forLanguageTag(language));
        context.setVariable("template", Map.of("templateId", "fixture", "displayName", "Fixture"));
        context.setVariable("templateId", "fixture");
        return engine.process(template, Set.of(selector), context);
    }

    private void assertTarget(String target, String language) {
        var previous = LocaleContextHolder.getLocaleContext();
        try {
            LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
            String[] parts = target.split("/", 2);
            var response = new HelpController(new I18nConfig().messageSource()).getDoc(parts[0]);
            assertEquals(200, response.getStatusCode().value(), target);
            if (parts.length == 2) {
                assertTrue(Pattern.compile("id=\"" + Pattern.quote(parts[1]) + "\"")
                        .matcher(response.getBody()).find(), language + ": missing help section " + target);
            }
        } finally {
            LocaleContextHolder.setLocaleContext(previous);
        }
    }
}
