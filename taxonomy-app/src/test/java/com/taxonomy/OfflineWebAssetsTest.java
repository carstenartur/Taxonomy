package com.taxonomy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Ensures that the primary browser application can be rendered without public CDNs. */
class OfflineWebAssetsTest {

    @Test
    void indexUsesOnlyPackagedBrowserLibraries() {
        String html = renderIndex();

        assertThat(html)
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("cdnjs.cloudflare.com")
                .doesNotContain("unpkg.com")
                .contains("/webjars/bootstrap/5.3.8/dist/css/bootstrap.min.css")
                .contains("/webjars/bootstrap/5.3.8/dist/js/bootstrap.bundle.min.js")
                .contains("/webjars/d3/7.9.0/dist/d3.min.js")
                .contains("/webjars/jspdf/4.2.1/dist/jspdf.umd.min.js")
                .contains("/webjars/svg2pdf.js/2.2.0/dist/svg2pdf.umd.min.js")
                .contains("/css/taxonomy-ergonomics.css")
                .containsOnlyOnce("src=\"/webjars/d3/7.9.0/dist/d3.min.js\"")
                .doesNotContain("th:replace", "<th:block");
    }

    @Test
    void everyVersionedTemplateAssetExistsInTheResolvedWebJars() {
        assertPackagedResource("/META-INF/resources/webjars/bootstrap/5.3.8/dist/css/bootstrap.min.css");
        assertPackagedResource("/META-INF/resources/webjars/bootstrap/5.3.8/dist/js/bootstrap.bundle.min.js");
        assertPackagedResource("/META-INF/resources/webjars/d3/7.9.0/dist/d3.min.js");
        assertPackagedResource("/META-INF/resources/webjars/jspdf/4.2.1/dist/jspdf.umd.min.js");
        assertPackagedResource("/META-INF/resources/webjars/svg2pdf.js/2.2.0/dist/svg2pdf.umd.min.js");
    }

    private void assertPackagedResource(String path) {
        assertThat(getClass().getResource(path))
                .as("versioned browser asset %s must be present in the resolved WebJar", path)
                .isNotNull();
    }

    private String renderIndex() {
        assertThat(getClass().getResource("/templates/index.html"))
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
        return engine.process("index", new WebContext(exchange, Locale.ENGLISH));
    }
}
