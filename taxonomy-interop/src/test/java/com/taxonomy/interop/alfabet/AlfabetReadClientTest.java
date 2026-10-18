package com.taxonomy.interop.alfabet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic protocol fixtures, not Alfabet product-compatibility evidence. No Spring or remote account. */
class AlfabetReadClientTest {
    private static final String TOKEN = "synthetic-token-not-a-real-credential";
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private final java.util.concurrent.atomic.AtomicReference<Throwable> serverFailure = new java.util.concurrent.atomic.AtomicReference<>();
    @AfterEach void stop() {
        if (server != null) server.stop(0);
        if (serverFailure.get() != null) throw new AssertionError("Request contract failed", serverFailure.get());
    }

    @Test void readsPagedObjectsUsingOnlyTheDocumentedReadPost() throws Exception {
        start((path, body) -> {
            assertEquals("/alfabet/api/v2/objects", path);
            assertEquals("Selected Applications", body.path("Report").asText());
            assertEquals("DataSet", body.path("ReportResult").asText());
            assertTrue(body.path("EmptyValues").asBoolean());
            assertEquals(2, body.path("Limit").asInt());
            return body.path("Offset").asInt() == 0
                    ? page(row("a-1", "First"), row("a-2", "Second")) : page();
        });
        var result = read(profile(2, 10, 10), Map.of("ALFABET_TOKEN", TOKEN));
        assertEquals(2, result.reports().getFirst().rows().size());
        assertEquals("a-1", result.reports().getFirst().rows().getFirst().refStr());
        assertEquals("First", result.reports().getFirst().rows().getFirst().fields().get("name"));
        assertEquals(2, requests.size());
        assertFalse(result.authoritativeInventory());
        assertEquals("REPORTS_EXHAUSTED_NOT_AN_ATOMIC_SNAPSHOT", result.evidenceStatus());
        assertThrows(UnsupportedOperationException.class, () -> result.reports().clear());
    }

    @Test void preservesMissingPropertiesInsteadOfInventingEmptyValues() throws Exception {
        start((path, body) -> page("{\"ClassName\":\"Application\",\"RefStr\":\"a-1\",\"Values\":{\"Name\":\"App\",\"Status\":null,\"Custom\":\"keep\"}}"));
        var row = read(profile(2, 10, 10), Map.of("ALFABET_TOKEN", TOKEN)).reports().getFirst().rows().getFirst();
        assertFalse(row.fields().containsKey("lifecycleStatus"));
        assertEquals("keep", row.columns().get("Custom"));
        assertTrue(row.nullColumns().contains("Status"));
    }

    @Test void authenticatesWithClientCredentialsWithoutReusingSecretsAsIdentity() throws Exception {
        start((path, body) -> page());
        var original = profile(2, 10, 10);
        var profile = original.withAuthentication(new AlfabetReadProfile.Authentication(
                AlfabetReadProfile.Grant.CLIENT_CREDENTIALS, "ALFABET_CLIENT", "ALFABET_SECRET"));
        var result = read(profile, Map.of("ALFABET_CLIENT", "reader", "ALFABET_SECRET", "synthetic-client-secret"));
        assertEquals(List.of("POST /alfabet/api/token", "POST /alfabet/api/v2/objects"), requests);
        assertFalse(result.toString().contains(TOKEN));
        assertFalse(result.toString().contains("synthetic-client-secret"));
    }

    @Test void refusesRepeatedObjectIdentityAcrossPages() throws Exception {
        start((path, body) -> page(row("a-1", "Duplicate")));
        assertThrows(IntegrationProblem.class, () -> read(profile(1, 10, 10), Map.of("ALFABET_TOKEN", TOKEN)));
        assertEquals(2, requests.size());
    }

    @Test void relationRowsMayShareTheSameBaseObjectReference() throws Exception {
        start((path, body) -> page(
                "{\"ClassName\":\"Application\",\"RefStr\":\"a-1\",\"Values\":{\"From\":\"a-1\",\"To\":\"c-1\"}}",
                "{\"ClassName\":\"Application\",\"RefStr\":\"a-1\",\"Values\":{\"From\":\"a-1\",\"To\":\"c-2\"}}"));
        var p = profile(10, 10, 10);
        var relation = new AlfabetReadProfile.Report("Selected Applications", "Application",
                AlfabetReadProfile.Kind.RELATION, Map.of("sourceRef", "From", "targetRef", "To"));
        var result = read(p.withReports(List.of(relation)), Map.of("ALFABET_TOKEN", TOKEN));
        assertEquals(2, result.reports().getFirst().rows().size());
        assertEquals("c-2", result.reports().getFirst().rows().get(1).fields().get("targetRef"));
    }

    @Test void stopsAtTheAggregatePageBudgetWithoutReturningALeadingPartialSuccess() throws Exception {
        start((path, body) -> page(row("a-" + body.path("Offset").asInt(), "App")));
        assertThrows(IntegrationProblem.class, () -> read(profile(1, 1, 10), Map.of("ALFABET_TOKEN", TOKEN)));
        assertEquals(1, requests.size());
    }

    @Test void stopsAtTheAggregateRowBudget() throws Exception {
        start((path, body) -> page(row("a-1", "App"), row("a-2", "App")));
        assertThrows(IntegrationProblem.class, () -> read(profile(2, 10, 1), Map.of("ALFABET_TOKEN", TOKEN)));
        assertEquals(1, requests.size());
    }

    @Test void malformedSuccessfulResponseIsNotAnEmptyInventory() throws Exception {
        start((path, body) -> "{\"Error\":\"Access denied\"}");
        assertThrows(IntegrationProblem.class, () -> read(profile(2, 10, 10), Map.of("ALFABET_TOKEN", TOKEN)));
    }

    @Test void unknownClassIsNotSilentlyTreatedAsAnApplication() throws Exception {
        start((path, body) -> page(row("a-1", "App").replace("Application", "Person")));
        assertThrows(IntegrationProblem.class, () -> read(profile(2, 10, 10), Map.of("ALFABET_TOKEN", TOKEN)));
    }

    @Test void rejectsCredentialReflectionEvenAfterJsonEscapeDecoding() throws Exception {
        start((path, body) -> page(row("a-1", "prefix-" + TOKEN.replace("s", "\\u0073"))));
        var failure = assertThrows(IntegrationProblem.class, () -> read(profile(2, 10, 10), Map.of("ALFABET_TOKEN", TOKEN)));
        assertFalse(failure.toString().contains(TOKEN));
        assertNull(failure.getCause());
    }

    @Test void missingCredentialDoesNotContactTheServer() throws Exception {
        start((path, body) -> page());
        assertThrows(IntegrationProblem.class, () -> read(profile(2, 10, 10), Map.of()));
        assertTrue(requests.isEmpty());
    }

    @Test void immutableMappingsAndSafeApplicationBaseAreRequired() {
        var auth = new AlfabetReadProfile.Authentication(AlfabetReadProfile.Grant.BEARER, null, "ALFABET_TOKEN");
        for (String base : List.of("https://user:pass@example.test/", "https://example.test/a/../b/",
                "https://example.test/%2e%2e/", "https://example.test/?x=1", "https://example.test/#x")) {
            assertThrows(IllegalArgumentException.class, () -> new AlfabetReadProfile(
                    new ExternalScope("alfabet", "test-instance", null), "1", URI.create(base), "Reader", auth,
                    List.of(report()), AlfabetReadProfile.Limits.defaults(), false, false));
        }
    }

    @Test void doesNotExposeAnExceptionFromTheSecretResolver() throws Exception {
        start((path, body) -> page());
        var failure = assertThrows(IntegrationProblem.class, () -> new AlfabetReadClient(reference -> {
            throw new IllegalStateException("provider failure " + TOKEN);
        }).read(profile(2, 10, 10)));
        assertFalse(failure.toString().contains(TOKEN));
        assertNull(failure.getCause());
        assertTrue(requests.isEmpty());
    }

    @Test void rejectsPercentEncodedCredentialReflection() throws Exception {
        String encoded = TOKEN.chars().mapToObj(c -> String.format("%%%02x", c)).collect(java.util.stream.Collectors.joining());
        start((path, body) -> page(row("a-1", encoded)));
        var failure = assertThrows(IntegrationProblem.class, () -> read(profile(2, 10, 10), Map.of("ALFABET_TOKEN", TOKEN)));
        assertEquals("ALFABET_CREDENTIAL_REFLECTION", failure.code());
    }

    @Test void doesNotSilentlyDiscardNonEmptyNestedObjects() throws Exception {
        start((path, body) -> page(row("a-1", "App").replace("\"Values\":", "\"NestedObjects\":[{\"RefStr\":\"hidden\"}],\"Values\":")));
        var failure = assertThrows(IntegrationProblem.class, () -> read(profile(2, 10, 10), Map.of("ALFABET_TOKEN", TOKEN)));
        assertEquals("ALFABET_UNSUPPORTED_NESTED_OBJECTS", failure.code());
    }

    private AlfabetSnapshot read(AlfabetReadProfile profile, Map<String, String> environment) {
        return new AlfabetReadClient(environment::get).read(profile);
    }
    private AlfabetReadProfile profile(int pageSize, int pages, int rows) {
        return new AlfabetReadProfile(new ExternalScope("alfabet", "test-instance", null), "1",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/alfabet/"), "Reader",
                new AlfabetReadProfile.Authentication(AlfabetReadProfile.Grant.BEARER, null, "ALFABET_TOKEN"),
                List.of(report()), new AlfabetReadProfile.Limits(pageSize, pages, rows, 1024 * 1024, 4 * 1024 * 1024, 10), true, true);
    }
    private static AlfabetReadProfile.Report report() {
        return new AlfabetReadProfile.Report("Selected Applications", "Application", AlfabetReadProfile.Kind.OBJECT,
                Map.of("name", "Name", "lifecycleStatus", "Status"));
    }
    private static String row(String ref, String name) {
        return "{\"ClassName\":\"Application\",\"RefStr\":\"" + ref + "\",\"Values\":{\"Name\":\"" + name + "\"}}";
    }
    private static String page(String... rows) {
        return "{\"Name\":\"Selected Applications\",\"Count\":" + rows.length + ",\"Objects\":[" + String.join(",", rows) + "]}";
    }
    @FunctionalInterface private interface Reply { String body(String path, JsonNode body) throws Exception; }
    private void start(Reply reply) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            byte[] response;
            try {
                assertEquals("POST", exchange.getRequestMethod());
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (path.endsWith("/api/token")) {
                    assertEquals("application/x-www-form-urlencoded", exchange.getRequestHeaders().getFirst("Content-Type").split(";")[0]);
                    assertEquals("grant_type=client_credentials&client_id=reader&client_secret=synthetic-client-secret", body);
                    assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                    response = ("{\"token_type\":\"bearer\",\"access_token\":\"" + TOKEN + "\",\"expires_in\":1200}").getBytes(StandardCharsets.UTF_8);
                } else {
                    assertEquals("Bearer " + TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
                    response = reply.body(path, json.readTree(body)).getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable failure) {
                serverFailure.compareAndSet(null, failure);
                exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        });
        server.start();
    }
}
