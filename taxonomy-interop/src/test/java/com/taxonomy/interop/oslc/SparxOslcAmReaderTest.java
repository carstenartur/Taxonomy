package com.taxonomy.interop.oslc;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.exchange.OslcRdf;
import com.taxonomy.exchange.sparx.SparxOslcAmCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.persistence.IntegrationStore.Connection;
import com.taxonomy.interop.sparx.SparxOslcAmReader;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SparxOslcAmReaderTest {
    private static final String TOKEN = "{22222222-2222-4222-8222-222222222222}";
    private static final String ID = "el_{11111111-1111-4111-8111-111111111111}";
    private HttpServer server;
    private OslcTransport transport;
    private SparxOslcAmReader reader;
    private URI base;
    private final AtomicInteger calls = new AtomicInteger();
    private final RepositoryContext context = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
    private Connection connection() { return new Connection(UUID.randomUUID(), "USER:alice", "PCS contract fixture",
            SparxOslcAmCodec.PROFILE, "1", AuthorityMode.IMPORT_COPY, new ExternalScope("PCS", base.toString(), null),
            null, "pcs", 0, null, null, "alice"); }
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model/oslc/am/");
        var profiles = new OslcRemoteProfiles();
        profiles.setRemotes(Map.of("pcs", new OslcRemoteProfiles.RemoteProfile("repo", "USER:alice", base, "PCS_TOKEN", true, true)));
        var repositories = mock(SystemRepositoryService.class); var repository = new SystemRepository();
        repository.setOwnerType(RepositoryOwnerType.USER); repository.setOwnerId("alice");
        when(repositories.getRepository("repo")).thenReturn(repository);
        transport = new OslcTransport(profiles, repositories, new MockEnvironment().withProperty("PCS_TOKEN", TOKEN));
        reader = new SparxOslcAmReader(transport);
        serve("sp/", new OslcRdf().link(base.resolve("query").toString(), OslcRdf.OSLC + "resourceType", SparxOslcAmCodec.AM + "Resource")
                .link(base.resolve("query").toString(), OslcRdf.OSLC + "queryBase", base.resolve("qc/").toString()).xml(), 200);
    }
    @AfterEach void stop() { transport.close(); server.stop(0); }

    @Test void realHttpUsesPcsTokenAndReturnsOnlySanitizedDurableEvidence() {
        String xml = new String(resource("Observation reader"), StandardCharsets.UTF_8)
                .replace("</rdf:Description>", "<ss:stereotype xmlns:ss=\"" + SparxOslcAmCodec.SS + "\">"
                        + "<ss:stereotypename><ss:name>CoreService</ss:name></ss:stereotypename>"
                        + "</ss:stereotype></rdf:Description>");
        serve("qc/", xml.getBytes(StandardCharsets.UTF_8), 200);
        var result = reader.read(context, connection(), base.resolve("sp/").toString(), null);
        assertEquals(2, calls.get()); assertEquals(1, result.artifacts().size());
        assertFalse(result.completeScope());
        assertEquals("CoreService", result.artifacts().getFirst().extensions().get("canonicalType"));
        assertFalse(result.toString().contains(TOKEN));
        assertFalse(result.toString().contains("useridentifier"));
    }
    @Test void noPartialPreviewAfterAuthenticationFailureAndNoRedirectFollowing() {
        serve("qc/", new byte[0], 401);
        assertEquals("REMOTE_UNAUTHORIZED", assertThrows(IntegrationProblem.class,
                () -> reader.read(context, connection(), base.resolve("sp/").toString(), null)).code());
        server.removeContext(base.getPath() + "qc/");
        serve("qc/", new byte[0], 302);
        assertEquals("REMOTE_MOVED", assertThrows(IntegrationProblem.class,
                () -> reader.read(context, connection(), base.resolve("sp/").toString(), null)).code());
    }
    @Test void detectsStaleEvidenceAndRejectsCredentialUrlsBeforeSending() {
        serve("qc/", resource("Changed"), 200);
        assertEquals("REMOTE_STALE", assertThrows(IntegrationProblem.class,
                () -> reader.read(context, connection(), base.resolve("sp/").toString(), "old-content-version")).code());
        int before = calls.get();
        assertThrows(IntegrationProblem.class, () -> reader.validate(context, connection(), "qc/?useridentifier=secret"));
        assertThrows(IntegrationProblem.class, () -> reader.validate(context, connection(), "qc/?oslc.select=dcterms:title"));
        assertThrows(IntegrationProblem.class, () -> reader.validate(context, connection(), "https://other.example/model/oslc/am/sp/"));
        assertEquals(before, calls.get());
    }
    @Test void refusesTokenReflectionAndPagingLoops() {
        serve("qc/", resource(TOKEN), 200);
        var reflected = assertThrows(IntegrationProblem.class, () -> reader.read(context, connection(), base.resolve("sp/").toString(), null));
        assertFalse(reflected.toString().contains(TOKEN));
        server.removeContext(base.getPath() + "qc/");
        serve("qc/", new OslcRdf().link(base.resolve("qc/").toString(), OslcRdf.OSLC + "nextPage", base.resolve("qc/").toString()).xml(), 200);
        assertThrows(IntegrationProblem.class, () -> reader.read(context, connection(), base.resolve("sp/").toString(), null));
    }
    @Test void followsAllPagesAndRejectsAFailedLaterPageInsteadOfReturningPartialData() {
        var failLastPage = new java.util.concurrent.atomic.AtomicBoolean(false);
        server.createContext(base.getPath() + "qc/", exchange -> {
            boolean second = exchange.getRequestURI().getRawQuery().contains("page=2");
            if (second && failLastPage.get()) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            byte[] content = second ? resource("Second").clone() : resource("First");
            String xml = new String(content, StandardCharsets.UTF_8);
            if (second) xml = xml.replace("11111111-1111-4111-8111-111111111111", "33333333-3333-4333-8333-333333333333");
            else xml = xml.replace("</rdf:RDF>", "<rdf:Description rdf:about=\"" + base + "qc/\">"
                    + "<oslc:nextPage xmlns:oslc=\"" + OslcRdf.OSLC + "\" rdf:resource=\"" + base + "qc/?page=2\"/>"
                    + "</rdf:Description></rdf:RDF>");
            content = xml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/rdf+xml");
            exchange.sendResponseHeaders(200, content.length); exchange.getResponseBody().write(content); exchange.close();
        });
        var result = reader.read(context, connection(), "qc/", null);
        assertEquals(2, result.artifacts().size()); assertFalse(result.completeScope());
        failLastPage.set(true);
        assertEquals("REMOTE_UNAVAILABLE", assertThrows(IntegrationProblem.class,
                () -> reader.read(context, connection(), "qc/", null)).code());
    }

    private byte[] resource(String title) {
        String uri = base + "resource/" + ID.replace("{", "%7B").replace("}", "%7D") + "/";
        return new OslcRdf().type(uri, SparxOslcAmCodec.AM + "Resource").literal(uri, OslcRdf.DCT + "identifier", ID)
                .literal(uri, OslcRdf.DCT + "type", "Component").literal(uri, OslcRdf.DCT + "title", title)
                .literal(uri, OslcRdf.DCT + "description", "Published flood observations").xml();
    }
    private void serve(String path, byte[] body, int status) {
        server.createContext(base.getPath() + path, exchange -> {
            calls.incrementAndGet();
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            if (!("OSLC " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    || !query.contains("useridentifier=" + TOKEN) || !exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(403, -1); exchange.close(); return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/rdf+xml");
            exchange.getResponseHeaders().set("Location", "https://other.example/");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
    }
}
