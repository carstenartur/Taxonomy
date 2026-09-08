package com.taxonomy.interop.oslc;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.exchange.OslcRdf;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.persistence.IntegrationStore.Connection;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OslcTransportTest {
    private HttpServer server;
    private OslcTransport transport;
    private URI base;
    private final RepositoryContext context = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
    private final AtomicInteger requests = new AtomicInteger();
    private Connection connection(String organization) { return new Connection(UUID.randomUUID(), organization, "Reference OSLC", "oslc-rm-2.1", "1", AuthorityMode.LINK_ONLY,
            new ExternalScope("Reference provider", base.toString(), base.resolve("configuration-a").toString()), 1L, "reference", 0, null, null, "alice"); }
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.start(); base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rm/");
        var profiles = new OslcRemoteProfiles(); profiles.setRemotes(Map.of("reference", profile(base, true)));
        var repositories = mock(SystemRepositoryService.class); var repository = new SystemRepository(); repository.setOwnerType(RepositoryOwnerType.USER); repository.setOwnerId("alice");
        when(repositories.getRepository("repo")).thenReturn(repository);
        transport = new OslcTransport(profiles, repositories, new MockEnvironment().withProperty("OSLC_FIXTURE_TOKEN", "fixture-token"));
    }
    private OslcRemoteProfiles.RemoteProfile profile(URI uri, boolean privateNetworks) { return new OslcRemoteProfiles.RemoteProfile("repo", "USER:alice", uri, "OSLC_FIXTURE_TOKEN", privateNetworks, true); }
    @AfterEach void close() { transport.close(); server.stop(0); }
    @Test void realHttpReadCarriesOnlyConfiguredCredentialAndConditionalVersion() {
        byte[] body = new OslcRdf().type(base.resolve("requirement-1").toString(), OslcRdf.RM + "Requirement").xml();
        server.createContext("/rm/requirement-1", exchange -> {
            requests.incrementAndGet();
            if (!"Bearer fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    || !"\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-Match"))
                    || !base.resolve("configuration-a").toString().equals(exchange.getRequestHeaders().getFirst("Configuration-Context"))) { exchange.sendResponseHeaders(412, -1); exchange.close(); return; }
            exchange.getResponseHeaders().set("Content-Type", "application/rdf+xml; charset=UTF-8"); exchange.getResponseHeaders().set("ETag", "\"v1\"");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        var response = transport.read(context, connection("USER:alice"), "requirement-1", "\"v1\"");
        assertArrayEquals(body, response.content()); assertEquals("\"v1\"", response.etag()); assertEquals(1, requests.get());
        assertEquals("REMOTE_STALE", assertThrows(IntegrationProblem.class, () -> transport.read(context, connection("USER:alice"), "requirement-1", "\"stale\"")).code());
        assertEquals(404, assertThrows(IntegrationProblem.class, () -> transport.read(context, connection("USER:bob"), "requirement-1", null)).status());
        assertEquals(2, requests.get(), "Foreign scope must fail before transport");
    }
    @Test void redirectsRateLimitsAndMalformedMediaNeverBecomeSynchronizationSuccess() {
        for (int status : List.of(302, 401, 404, 412, 429, 500)) server.createContext("/rm/status-" + status, exchange -> {
            requests.incrementAndGet(); exchange.getResponseHeaders().set("Location", base.resolve("forbidden").toString()); exchange.sendResponseHeaders(status, -1); exchange.close();
        });
        server.createContext("/rm/forbidden", exchange -> { requests.addAndGet(1000); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        for (int status : List.of(302, 401, 404, 412, 429, 500)) assertThrows(IntegrationProblem.class, () -> transport.read(context, connection("USER:alice"), "status-" + status, null));
        assertEquals(6, requests.get(), "No automatic redirect or retry");
        server.createContext("/rm/wrong-media", exchange -> { exchange.getResponseHeaders().set("Content-Type", "text/html"); exchange.sendResponseHeaders(200, 0); exchange.close(); });
        assertEquals("REMOTE_MEDIA_TYPE", assertThrows(IntegrationProblem.class, () -> transport.read(context, connection("USER:alice"), "wrong-media", null)).code());
    }
    @Test void originPathQueryAndAddressPolicyRejectsSsrfWithoutNetworkAccess() throws Exception {
        for (String resource : List.of("https://foreign.example/rm/", "../outside", "/rm/%2e%2e/secret", "/rm/%252e%252e/secret?token=secret", "http://user:password@127.0.0.1/rm/", "requirement?token=secret"))
            assertThrows(IntegrationProblem.class, () -> OslcTransport.target(profile(base, true), resource), resource);
        for (String address : List.of("127.0.0.1", "10.0.0.1", "192.168.0.1", "100.64.0.1", "::1", "fc00::1", "169.254.169.254"))
            assertFalse(OslcTransport.allowedAddress(InetAddress.getByName(address), false), address);
        assertFalse(OslcTransport.allowedAddress(InetAddress.getByName("169.254.169.254"), true));
        assertTrue(OslcTransport.allowedAddress(InetAddress.getByName("127.0.0.1"), true));
        assertTrue(OslcTransport.allowedAddress(InetAddress.getByName("8.8.8.8"), false));
        assertEquals(0, requests.get());
    }
}
