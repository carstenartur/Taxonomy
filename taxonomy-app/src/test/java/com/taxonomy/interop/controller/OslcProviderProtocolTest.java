package com.taxonomy.interop.controller;

import com.taxonomy.exchange.OslcRdf;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.oslc.OslcProviderService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OslcProviderProtocolTest {
    private final OslcProviderService service = mock(OslcProviderService.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final OslcProviderController controller = new OslcProviderController(service, resolver);
    private final RepositoryContext context = RepositoryContext.workspace("repository", "workspace", "draft", "alice");
    @BeforeEach void fixture() {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(context);
        when(service.catalog(eq(context), any())).thenReturn(new OslcRdf().type("https://provider.example/catalog", OslcRdf.OSLC + "ServiceProviderCatalog"));
    }
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/oslc/scopes/scope/catalog"); request.setScheme("https"); request.setServerName("provider.example"); request.setServerPort(443);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request)); return request;
    }
    @Test void explicitQualityZeroCannotBeOverriddenByWildcards() {
        assertEquals("text/turtle", OslcProviderController.negotiate("application/rdf+xml;q=0, */*;q=0.8"));
        assertEquals("application/ld+json", OslcProviderController.negotiate("text/turtle;q=0.3, application/ld+json;q=0.9"));
        assertEquals("application/rdf+xml", OslcProviderController.negotiate(null));
        assertEquals(406, assertThrows(IntegrationProblem.class, () -> OslcProviderController.negotiate("text/html")).status());
        assertThrows(IntegrationProblem.class, () -> OslcProviderController.negotiate("*/*;q=0"));
    }
    @Test void conditionalReadsAndConfigurationAreCheckedWithoutChangingResources() {
        var first = controller.catalog("scope", request()); assertEquals(200, first.getStatusCode().value());
        String etag = first.getHeaders().getETag(); assertNotNull(etag);
        var same = request(); same.addHeader("If-None-Match", "\"different\", W/" + etag);
        var cached = controller.catalog("scope", same); assertEquals(304, cached.getStatusCode().value()); assertNull(cached.getBody());
        var stale = request(); stale.addHeader("If-Match", "W/" + etag);
        assertEquals(412, assertThrows(IntegrationProblem.class, () -> controller.catalog("scope", stale)).status());
        var matching = request(); matching.addHeader("If-Match", etag); matching.addHeader("Configuration-Context", first.getHeaders().getFirst("Configuration-Context"));
        assertEquals(200, controller.catalog("scope", matching).getStatusCode().value());
        var unavailable = request(); unavailable.addHeader("Configuration-Context", "https://provider.example/foreign-config");
        assertEquals(404, assertThrows(IntegrationProblem.class, () -> controller.catalog("scope", unavailable)).status());
        verify(service, times(5)).authorize(context, "scope");
    }
}
