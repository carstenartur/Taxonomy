package com.taxonomy.plugins.alfabet;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;
import com.taxonomy.extension.api.inventory.InventorySourceExtension;
import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic report protocol over real HTTPS; not acceptance against a customer installation. */
class AlfabetInventorySourceTest {
    private static final String TOKEN = "synthetic-source-bearer-credential";
    private static final String SECRET = "synthetic-source-client-secret";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir static Path temporary;
    private static SSLContext tls;
    private HttpsServer server;
    private ExecutorService threads;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();

    @BeforeAll static void certificate() throws Exception {
        Path store = temporary.resolve("local-test.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "test-fixture-only", "-keypass", "test-fixture-only", "-noprompt")
                .redirectErrorStream(true).redirectOutput(temporary.resolve("keytool.log").toFile()).start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, p.exitValue());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) { ks.load(in, "test-fixture-only".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(ks, "test-fixture-only".toCharArray());
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(ks);
        tls = SSLContext.getInstance("TLS"); tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    }

    @AfterEach void close() throws Exception {
        if (server != null) server.stop(0);
        if (threads != null) { threads.shutdownNow(); assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS)); }
        if (serverFailure.get() != null) throw new AssertionError(serverFailure.get());
    }

    @Test void readsPagedReportsAndRetainsTheApprovedRequest() throws Exception {
        start(body -> {
            assertEquals("Reader", body.path("CurrentProfile").asText());
            assertEquals("DataSet", body.path("ReportResult").asText());
            assertEquals("Applications", body.path("Report").asText());
            assertEquals(2, body.path("Limit").asInt());
            return body.path("Offset").asInt() == 0 ? page("Applications", row("a1", "First"), row("a2", "Second"))
                    : page("Applications", row("a3", "Third"));
        });
        Request request = request(false, options(), limits());
        Observation result = source().read(request, access(true));
        assertSame(request, result.request());
        assertEquals(List.of("a1", "a2", "a3"), result.objects().stream().map(o -> o.key().id()).toList());
        assertEquals(new ExternalKey("instance", "refstr", "a1"), result.objects().getFirst().key());
        assertEquals(2, result.requests()); assertEquals(2, calls.size()); assertTrue(result.bytes() > 0);
        assertEquals(Completeness.SELECTED_SCOPE_READ_NON_ATOMIC, result.completeness());
        assertTrue(result.limitations().containsAll(Set.of("NO_DELETION_AUTHORITY", "NO_ATOMIC_SNAPSHOT")));
    }

    @Test void missingNullEmptyAndUnknownSubtypeArePreserved() throws Exception {
        start(body -> page("Applications", "{\"ClassName\":\"Application\",\"RefStr\":\"a1\",\"Values\":{\"Name\":\"\",\"Description\":null,\"Kind\":\"unknown\"}}"));
        Request r = request(false, options(), limits());
        var projection = new TypeProjection("Application", ObjectKind.APPLICATION, "Kind", Map.of("service", ObjectKind.SERVICE),
                Map.of("name", "Name", "description", "Description", "status", "Status"));
        var result = source().read(copy(r, List.of(projection), Set.of(), r.origin()), access(true)).objects().getFirst();
        assertEquals(Field.text("", "Name"), result.fields().get("name"));
        assertEquals(Field.missing(State.NULL, "Description"), result.fields().get("description"));
        assertEquals(Field.missing(State.ABSENT, "Status"), result.fields().get("status"));
        assertEquals(ObjectKind.OTHER, result.kind()); assertEquals("Application", result.sourceType());
        assertNull(result.revision()); assertEquals(State.NOT_SUPPORTED, result.lifecycleState());
    }

    @Test void relationsSharingABaseRefHaveSeparateLocalIdsAndNoExternalRelationKey() throws Exception {
        start(body -> body.path("Report").asText().equals("Applications") ? page("Applications", row("a1", "App"))
                : body.path("Offset").asInt() == 0 ? page("Usage", relation("c1"), relation("c2")) : page("Usage"));
        var result = source().read(request(true, relationOptions(), limits()), access(true));
        assertEquals(2, result.relations().size());
        var a = result.relations().get(0); var b = result.relations().get(1);
        assertNull(a.key()); assertNull(b.key()); assertNotEquals(a.observationId(), b.observationId());
        assertEquals(a.source(), b.source()); assertEquals("a1", a.source().id());
        assertEquals("c1", a.target().id()); assertEquals("c2", b.target().id());
        assertEquals("USES", a.meaning()); assertEquals("2028-01-01", a.fields().get("activeFrom").value());
        assertEquals(Field.text("a1", "RefStr"), a.fields().get("baseRef"));
        assertTrue(result.limitations().contains("RELATION_ENDPOINTS_OUTSIDE_OBSERVATION"));
        assertEquals(3, result.requests());
    }

    @Test void unrequestedRelationReportsAreNotFetched() throws Exception {
        start(body -> { assertEquals("Applications", body.path("Report").asText()); return page("Applications"); });
        assertTrue(source().read(request(false, relationOptions(), limits()), access(true)).relations().isEmpty());
        assertEquals(1, calls.size());
    }

    @Test void invalidSettingsAndUnsupportedSelectionFailBeforeCredentialLookup() {
        ReadAccess forbidden = () -> { throw new AssertionError("No credential lookup for an invalid request"); };
        var invalid = new ArrayList<Map<String,String>>();
        invalid.add(Map.of());
        var unknown = new HashMap<>(options()); unknown.put("endpoint", "api/v2/update"); invalid.add(unknown);
        var invalidGrant = new HashMap<>(options()); invalidGrant.put("authentication", "AUTOMATIC"); invalid.add(invalidGrant);
        var incomplete = new HashMap<>(relationOptions()); incomplete.remove("relation.usage.targetRef"); invalid.add(incomplete);
        for (var options : invalid) assertThrows(IllegalArgumentException.class, () -> source().read(request(false, options, limits()), forbidden));
        Request r = request(false, options(), limits());
        assertThrows(IllegalArgumentException.class, () -> source().read(copy(r, r.projections(), Set.of("a1"), r.origin()), forbidden));
        assertThrows(IllegalArgumentException.class, () -> source().read(request(true, options(), limits()), forbidden));
        assertThrows(IllegalArgumentException.class, () -> source().read(copy(r, r.projections(), Set.of(), URI.create("http://example.invalid/")), forbidden));
    }

    @Test void sourceProfileIdentityIsCheckedBeforeCredentialLookup() {
        Request r = request(false, options(), limits());
        for (String[] identity : List.of(new String[]{"leanix", "alfabet-read", "1"},
                new String[]{"alfabet", "leanix-read", "1"}, new String[]{"alfabet", "alfabet-read", "2"})) {
            var c = r.context();
            var context = new IntegrationContext(c.connectionId(), c.authority(), new ExternalScope(identity[0], "instance", null),
                    c.internalState(), c.actor(), identity[1], identity[2]);
            var wrong = new Request(context, r.instanceId(), r.configurationRevision(), r.origin(), r.projections(), Set.of(), false, r.limits(), r.sourceOptions());
            assertThrows(IllegalArgumentException.class, () -> source().read(wrong, () -> { throw new AssertionError("credential lookup"); }));
        }
    }

    @Test void boundCredentialsAreWipedAndNeverIncludedInObservations() throws Exception {
        start(body -> page("Applications"));
        char[] secret = SECRET.toCharArray(), principal = "reader".toCharArray();
        var options = new HashMap<>(options()); options.put("authentication", "CLIENT_CREDENTIALS");
        var result = source().read(request(false, options, limits()), new ReadAccess() {
            public char[] credential() { return secret; }
            public char[] principal() { return principal; }
            public SSLContext tls() { return tls; }
            public boolean allowPrivateNetworks() { return true; }
        });
        assertArrayEquals(new char[secret.length], secret); assertArrayEquals(new char[principal.length], principal);
        assertEquals(2, result.requests());
        assertFalse(result.toString().contains(SECRET)); assertFalse(result.toString().contains(TOKEN));
    }

    @Test void passwordGrantUsesOnlyTheBoundPrincipalAndSecret() throws Exception {
        start(body -> page("Applications"));
        var options = new HashMap<>(options()); options.put("authentication", "PASSWORD");
        assertEquals(2, source().read(request(false, options, limits()), access(true, SECRET)).requests());
        assertTrue(calls.getFirst().contains("grant_type=password&username=reader&password=" + SECRET));
    }

    @Test void authenticationCountsAgainstTheAggregateRequestBudget() throws Exception {
        start(body -> page("Applications", row("a" + body.path("Offset").asInt(), "App")));
        var options = new HashMap<>(options()); options.put("authentication", "CLIENT_CREDENTIALS");
        var budget = new Limits(1, 2, 10, 10, 4096, 16384, 10);
        assertEquals("ALFABET_READ_LIMIT", assertThrows(ReadFailure.class,
                () -> source().read(request(false, options, budget), access(true))).code());
        assertEquals(2, calls.size());
    }

    @Test void objectAndRelationBudgetsAreIndependentAndFailWithoutAPartialResult() throws Exception {
        start(body -> body.path("Report").asText().equals("Applications") ? page("Applications", row("a1", "App"))
                : page("Usage", relation("c1"), relation("c2")));
        assertEquals("ALFABET_READ_LIMIT", assertThrows(ReadFailure.class, () -> source().read(
                request(true, relationOptions(), new Limits(2, 10, 10, 1, 4096, 16384, 10)), access(true))).code());
    }

    @Test void duplicateObjectIdentityAcrossDifferentReportsIsRejected() throws Exception {
        start(body -> body.path("Report").asText().equals("Applications") ? page("Applications", row("shared", "App"))
                : page("Components", row("shared", "Platform").replace("Application", "Component")));
        var options = new HashMap<>(options()); options.put("object.Component.report", "Components");
        Request r = request(false, options, limits());
        var projections = new ArrayList<>(r.projections());
        projections.add(new TypeProjection("Component", ObjectKind.OTHER, null, Map.of(), Map.of("name", "Name")));
        assertEquals("ALFABET_DUPLICATE_OBJECT", assertThrows(ReadFailure.class,
                () -> source().read(copy(r, projections, Set.of(), r.origin()), access(true))).code());
    }

    @Test void privateLiteralAddressRequiresTheHostGrant() throws Exception {
        start(body -> page("Applications"));
        assertThrows(ReadFailure.class, () -> source().read(request(false, options(), limits()), access(false)));
        assertTrue(calls.isEmpty());
    }

    @Test void malformedRelationEndpointsFailWithASanitizedSourceError() throws Exception {
        var endpoint = new AtomicReference<>("bad\nreference");
        start(body -> body.path("Report").asText().equals("Applications") ? page("Applications")
                : page("Usage", JSON.writeValueAsString(Map.of("ClassName", "Application", "RefStr", "a1",
                        "Values", Map.of("From", "a1", "To", endpoint.get())))));
        for (String invalid : List.of("bad\nreference", "x".repeat(2049))) {
            endpoint.set(invalid);
            var failure = assertThrows(ReadFailure.class, () -> source().read(request(true, relationOptions(), limits()), access(true)));
            assertEquals("ALFABET_RELATION_ENDPOINT", failure.code()); assertNull(failure.getCause());
        }
    }

    @Test void objectBudgetAppliesAcrossReports() throws Exception {
        start(body -> body.path("Report").asText().equals("Applications") ? page("Applications", row("a1", "App"))
                : page("Components", row("c1", "Platform").replace("Application", "Component")));
        var options = new HashMap<>(options()); options.put("object.Component.report", "Components");
        Request r = request(false, options, new Limits(2, 10, 1, 10, 4096, 16384, 10));
        var projections = new ArrayList<>(r.projections());
        projections.add(new TypeProjection("Component", ObjectKind.OTHER, null, Map.of(), Map.of("name", "Name")));
        assertEquals("ALFABET_READ_LIMIT", assertThrows(ReadFailure.class,
                () -> source().read(copy(r, projections, Set.of(), r.origin()), access(true))).code());
        assertEquals(2, calls.size());
    }

    @Test void untrustedCertificateIsRejected() throws Exception {
        start(body -> page("Applications"));
        assertThrows(ReadFailure.class, () -> source().read(request(false, options(), limits()), new ReadAccess() {
            public char[] credential() { return TOKEN.toCharArray(); }
            public boolean allowPrivateNetworks() { return true; }
        }));
        assertTrue(calls.isEmpty());
    }

    private static InventorySourceExtension source() {
        return ServiceLoader.load(TaxonomyPlugin.class).stream().map(ServiceLoader.Provider::get)
                .flatMap(p -> p.extensions().stream()).filter(e -> e.id().equals("alfabet"))
                .map(InventorySourceExtension.class::cast).findFirst().orElseThrow();
    }
    private ReadAccess access(boolean privateNetwork) {
        return access(privateNetwork, TOKEN);
    }
    private ReadAccess access(boolean privateNetwork, String credential) {
        return new ReadAccess() {
            public char[] credential() { return credential.toCharArray(); }
            public char[] principal() { return "reader".toCharArray(); }
            public SSLContext tls() { return tls; }
            public boolean allowPrivateNetworks() { return privateNetwork; }
        };
    }
    private static Map<String,String> options() { return Map.of("currentProfile", "Reader", "authentication", "BEARER", "object.Application.report", "Applications"); }
    private static Map<String,String> relationOptions() {
        var options = new HashMap<>(options());
        options.putAll(Map.of("relation.usage.report", "Usage", "relation.usage.className", "Application",
                "relation.usage.sourceRef", "From", "relation.usage.targetRef", "To", "relation.usage.activeFrom", "Since"));
        return options;
    }
    private static Limits limits() { return new Limits(2, 20, 30, 30, 4096, 16384, 10); }
    private Request request(boolean relations, Map<String,String> options, Limits limits) {
        var context = new IntegrationContext(UUID.randomUUID(), AuthorityMode.LINK_ONLY, new ExternalScope("alfabet", "instance", null),
                new InternalState("repository", "scope", "main", null, 0, null, null), "actor", "alfabet-read", "1");
        URI base = URI.create("https://127.0.0.1:" + (server == null ? 443 : server.getAddress().getPort()) + "/alfabet/");
        return new Request(context, "instance", "config-1", base,
                List.of(new TypeProjection("Application", ObjectKind.APPLICATION, null, Map.of(), Map.of("name", "Name"))),
                Set.of(), relations, limits, options);
    }
    private static Request copy(Request r, List<TypeProjection> projections, Set<String> ids, URI origin) {
        return new Request(r.context(), r.instanceId(), r.configurationRevision(), origin, projections, ids, r.readUsageRelations(), r.limits(), r.sourceOptions());
    }
    private static String row(String id, String name) { return "{\"ClassName\":\"Application\",\"RefStr\":\"" + id + "\",\"Values\":{\"Name\":\"" + name + "\"}}"; }
    private static String relation(String target) { return "{\"ClassName\":\"Application\",\"RefStr\":\"a1\",\"Values\":{\"From\":\"a1\",\"To\":\"" + target + "\",\"Since\":\"2028-01-01\"}}"; }
    private static String page(String name, String... rows) { return "{\"Name\":\"" + name + "\",\"Objects\":[" + String.join(",", rows) + "]}"; }
    @FunctionalInterface private interface Reply { String response(JsonNode body) throws Exception; }
    private void start(Reply reply) throws Exception {
        threads = Executors.newCachedThreadPool();
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls)); server.setExecutor(threads);
        server.createContext("/", exchange -> {
            try {
                assertEquals("POST", exchange.getRequestMethod());
                String path = exchange.getRequestURI().getPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                calls.add(path + " " + body);
                String response;
                if (path.equals("/alfabet/api/token")) {
                    assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                    assertTrue(body.startsWith("grant_type=client_credentials&client_id=reader&client_secret=")
                            || body.startsWith("grant_type=password&username=reader&password="));
                    response = "{\"token_type\":\"bearer\",\"access_token\":\"" + TOKEN + "\",\"expires_in\":1200}";
                } else {
                    assertEquals("/alfabet/api/v2/objects", path);
                    assertEquals("Bearer " + TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
                    response = reply.response(JSON.readTree(body));
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable failure) { serverFailure.compareAndSet(null, failure); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        server.start();
    }
}
