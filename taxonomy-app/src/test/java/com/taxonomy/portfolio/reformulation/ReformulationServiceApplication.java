package com.taxonomy.portfolio.reformulation;

import com.taxonomy.TaxonomyApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.context.support.GenericWebApplicationContext;

/**
 * Full application fixture for isolated service-level scenarios. The servlet
 * environment retains production MVC/security configuration, but no embedded
 * HTTP listener is needed when every inbound call is made through a service.
 * Outbound provider HTTP, persistence, workers and application runners stay real.
 */
final class ReformulationServiceApplication {
    private ReformulationServiceApplication() { }

    static ConfigurableApplicationContext run(String... arguments) {
        var app = new SpringApplicationBuilder(TaxonomyApplication.class)
                .web(WebApplicationType.SERVLET)
                .contextFactory(type -> {
                    var context = new GenericWebApplicationContext();
                    context.setServletContext(new MockServletContext());
                    return context;
                }).run(arguments);
        try {
            // Fail closed if this fixture ever drops the production security configuration.
            app.getBean("springSecurityFilterChain", FilterChainProxy.class);
            return app;
        } catch (RuntimeException | Error failure) {
            app.close();
            throw failure;
        }
    }
}
