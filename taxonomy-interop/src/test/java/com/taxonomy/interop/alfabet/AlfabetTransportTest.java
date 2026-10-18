package com.taxonomy.interop.alfabet;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExternalScope;
import com.taxonomy.interop.IntegrationProblem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AlfabetTransportTest {
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger redirected = new AtomicInteger();
    private static final String TOKEN = "synthetic-bearer-for-contract-test";

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void refusesPrivateAddressesUnlessExplicitlyEnabled() throws Exception {
        serve(200, "application/json", "{\"Name\":\"Applications\",\"Objects\":[]}", false);
        assertThrows(IntegrationProblem.class, () -> client().read(profile(false, 1024, 2)));
        assertEquals(0, calls.get());
    }

    @Test void doesNotFollowRedirectsOrRetryAndNeverHitsUpdateEndpoint() throws Exception {
        serve(307, "application/json", "{}", true);
        assertThrows(IntegrationProblem.class, () -> client().read(profile(true, 1024, 2)));
        assertEquals(1, calls.get());
        assertEquals(0, redirected.get());
    }

    @Test void permissionFailureIsAnErrorNotAnEmptySnapshot() throws Exception {
        serve(403, "application/json", "{\"error\":\"" + TOKEN + "\"}", false);
        var failure = assertThrows(IntegrationProblem.class, () -> client().read(profile(true, 1024, 2)));
        assertFalse(failure.toString().contains(TOKEN));
        assertNull(failure.getCause());
        assertEquals(1, calls.get());
    }

    @Test void doesNotRetryRateLimits() throws Exception {
        serve(429, "application/json", "{}", false);
        assertThrows(IntegrationProblem.class, () -> client().read(profile(true, 1024, 2)));
        assertEquals(1, calls.get());
    }

    @Test void refusesAnOversizedBody() throws Exception {
        serve(200, "application/json", " ".repeat(2048), false);
        assertThrows(IntegrationProblem.class, () -> client().read(profile(true, 1024, 2)));
    }

    @Test void rejectsAnHtmlLoginPage() throws Exception {
        serve(200, "text/html", "<html>Login</html>", false);
        assertThrows(IntegrationProblem.class, () -> client().read(profile(true, 1024, 2)));
    }

    @Test void rejectsDuplicateJsonKeysAndTrailingDocuments() throws Exception {
        serve(200, "application/json", "{\"Name\":\"Applications\",\"Objects\":[],\"Objects\":[{}]}", false);
        assertThrows(IntegrationProblem.class, () -> client().read(profile(true, 1024, 2)));
        server.stop(0);
        serve(200, "application/json", "{\"Name\":\"Applications\",\"Objects\":[]} {}", false);
        assertThrows(IntegrationProblem.class, () -> client().read(profile(true, 1024, 2)));
    }

    @Test void enforcesADeadlineOnAResponseThatNeverFinishes() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try {
                while (true) {
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                    Thread.sleep(25);
                }
            } catch (Exception ended) { /* Client deadline closes the stream. */ }
            finally { exchange.close(); }
        });
        server.start();
        long start = System.nanoTime();
        assertThrows(IntegrationProblem.class, () -> client().read(profile(true, 1024, 1)));
        assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds() < 4);
    }

    private AlfabetReadClient client() { return new AlfabetReadClient(reference -> TOKEN); }
    private AlfabetReadProfile profile(boolean privateNetwork, int maxBytes, int seconds) {
        return new AlfabetReadProfile(new ExternalScope("alfabet", "test-instance", null), "1",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/alfabet/"), "Reader",
                new AlfabetReadProfile.Authentication(AlfabetReadProfile.Grant.BEARER, null, "ALFABET_TOKEN"),
                List.of(new AlfabetReadProfile.Report("Applications", "Application", AlfabetReadProfile.Kind.OBJECT, Map.of("name", "Name"))),
                new AlfabetReadProfile.Limits(10, 5, 50, maxBytes, maxBytes * 2, seconds), privateNetwork, true);
    }
    private void serve(int status, String mediaType, String body, boolean redirect) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/alfabet/api/v2/update", exchange -> {
            redirected.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", mediaType);
            if (redirect) exchange.getResponseHeaders().set("Location", "/alfabet/api/v2/update");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }
}
