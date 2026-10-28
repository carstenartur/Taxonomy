package com.taxonomy.plugins.alfabet;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExternalScope;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.ReadFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AlfabetTransportTest {
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger redirected = new AtomicInteger();
    private static final String TOKEN = "synthetic-bearer-for-contract-test";

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void networkPolicyKeepsMetadataAndSpecialAddressesForbiddenWithPrivateAccess() throws Exception {
        for (String address : List.of("0.0.0.0", "169.254.169.254", "100.100.100.200", "224.0.0.1",
                "240.0.0.1", "::", "fe80::1", "ff02::1", "fd00:ec2::254")) {
            assertFalse(AlfabetHttpTransport.allowedAddress(InetAddress.getByName(address), true), address);
        }
        for (String address : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "::1", "fd01::1")) {
            assertFalse(AlfabetHttpTransport.allowedAddress(InetAddress.getByName(address), false), address);
            assertTrue(AlfabetHttpTransport.allowedAddress(InetAddress.getByName(address), true), address);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"oversized", "unauthorized", "media-type"})
    void rejectedResponsesAreAbortedBeforeClosingCanDrainTheBody(String mode) throws Exception {
        var release = new CountDownLatch(1);
        var sent = new CountDownLatch(1);
        var handlers = Executors.newCachedThreadPool();
        var reader = Executors.newSingleThreadExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", mode.equals("media-type") ? "text/plain" : "application/json");
                exchange.sendResponseHeaders(mode.equals("unauthorized") ? 403 : 200, 0);
                exchange.getResponseBody().write(" ".repeat(2048).getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush(); sent.countDown();
                release.await(8, TimeUnit.SECONDS);
            } catch (java.io.IOException cancelled) { /* The tested client deliberately cancels rejected responses. */ }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { sent.countDown(); exchange.close(); }
        });
        server.start();
        try {
            var result = reader.submit(() -> assertThrows(ReadFailure.class, () -> client().read(profile(true, 1024, 10))));
            assertTrue(sent.await(2, TimeUnit.SECONDS));
            var failure = result.get(2, TimeUnit.SECONDS);
            assertEquals(switch (mode) {
                case "oversized" -> "ALFABET_READ_LIMIT";
                case "unauthorized" -> "ALFABET_UNAUTHORIZED";
                default -> "ALFABET_MEDIA_TYPE";
            }, failure.code());
        } finally {
            release.countDown(); reader.shutdownNow(); handlers.shutdownNow();
            assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(handlers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void refusesPrivateAddressesUnlessExplicitlyEnabled() throws Exception {
        serve(200, "application/json", "{\"Name\":\"Applications\",\"Objects\":[]}", false);
        assertThrows(ReadFailure.class, () -> client().read(profile(false, 1024, 2)));
        assertEquals(0, calls.get());
    }

    @Test void doesNotFollowRedirectsOrRetryAndNeverHitsUpdateEndpoint() throws Exception {
        serve(307, "application/json", "{}", true);
        assertThrows(ReadFailure.class, () -> client().read(profile(true, 1024, 2)));
        assertEquals(1, calls.get());
        assertEquals(0, redirected.get());
    }

    @Test void permissionFailureIsAnErrorNotAnEmptySnapshot() throws Exception {
        serve(403, "application/json", "{\"error\":\"" + TOKEN + "\"}", false);
        var failure = assertThrows(ReadFailure.class, () -> client().read(profile(true, 1024, 2)));
        assertFalse(failure.toString().contains(TOKEN));
        assertNull(failure.getCause());
        assertEquals(1, calls.get());
    }

    @Test void doesNotRetryRateLimits() throws Exception {
        serve(429, "application/json", "{}", false);
        assertThrows(ReadFailure.class, () -> client().read(profile(true, 1024, 2)));
        assertEquals(1, calls.get());
    }

    @Test void refusesAnOversizedBody() throws Exception {
        serve(200, "application/json", " ".repeat(2048), false);
        assertThrows(ReadFailure.class, () -> client().read(profile(true, 1024, 2)));
    }

    @Test void rejectsAnHtmlLoginPage() throws Exception {
        serve(200, "text/html", "<html>Login</html>", false);
        assertThrows(ReadFailure.class, () -> client().read(profile(true, 1024, 2)));
    }

    @Test void rejectsDuplicateJsonKeysAndTrailingDocuments() throws Exception {
        serve(200, "application/json", "{\"Name\":\"Applications\",\"Objects\":[],\"Objects\":[{}]}", false);
        assertThrows(ReadFailure.class, () -> client().read(profile(true, 1024, 2)));
        server.stop(0);
        serve(200, "application/json", "{\"Name\":\"Applications\",\"Objects\":[]} {}", false);
        assertThrows(ReadFailure.class, () -> client().read(profile(true, 1024, 2)));
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
        assertThrows(ReadFailure.class, () -> client().read(profile(true, 1024, 1)));
        assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds() < 4);
    }

    @Test void boundsStalledDnsResolutionByTheReadDeadline() throws Exception {
        var profile = new AlfabetReadProfile(new ExternalScope("alfabet", "test-instance", null), "1",
                URI.create("http://stalled-resolver.test/alfabet/"), "Reader",
                new AlfabetReadProfile.Authentication(AlfabetReadProfile.Grant.BEARER, null, "ALFABET_TOKEN"),
                List.of(new AlfabetReadProfile.Report("Applications", "Application", AlfabetReadProfile.Kind.OBJECT, Map.of("name", "Name"))),
                new AlfabetReadProfile.Limits(10, 5, 50, 1024, 2048, 1), true, true);
        var resolverStarted = new CountDownLatch(1);
        var resolverRelease = new CountDownLatch(1);
        try (var transport = new AlfabetHttpTransport(profile, host -> {
            resolverStarted.countDown();
            try { resolverRelease.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { return new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})}; }
            catch (java.net.UnknownHostException impossible) { throw new IllegalStateException(impossible); }
        })) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            long started = System.nanoTime();
            var failure = assertThrows(ReadFailure.class,
                    () -> transport.post(AlfabetHttpTransport.Endpoint.OBJECTS, "{}", null, 1024, deadline));
            assertEquals("ALFABET_READ_TIMEOUT", failure.code());
            assertTrue(resolverStarted.await(1, TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500);
        } finally {
            resolverRelease.countDown();
        }
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
