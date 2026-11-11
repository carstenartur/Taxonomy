package com.taxonomy;

import com.taxonomy.catalog.controller.TaxonomyController;
import com.taxonomy.catalog.service.TaxonomyService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Renders the actual analysis page with the model supplied by its controller. */
final class IndexPageFixture {
    private IndexPageFixture() {}

    static String render() {
        assertThat(IndexPageFixture.class.getResource("/templates/index.html"))
                .as("index.html must be available on the classpath").isNotNull();
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var servlet = new MockServletContext();
        var request = new MockHttpServletRequest(servlet);
        request.setRequestURI("/");
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(request, new MockHttpServletResponse());
        var taxonomy = mock(TaxonomyService.class);
        when(taxonomy.getFullTree()).thenReturn(List.of());
        var model = new ExtendedModelMap();
        String view = new TaxonomyController(taxonomy).index(model);
        return engine.process(view, new WebContext(exchange, Locale.ENGLISH, model.asMap()));
    }
}
