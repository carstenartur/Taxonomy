package com.taxonomy.plugins.leanix;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;
import com.taxonomy.extension.api.inventory.InventorySourceExtension;
import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.parser.Parser;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.validation.Validator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic protocol contracts, not a real LeanIX workspace or proof of product compatibility. */
class LeanIxInventorySourceTest {
    private static final String API = "test-only-api-token-value";
    private static final String BEARER = "test-only-access-token-value";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir static Path temporary;
    private static SSLContext tls;
    private Server server;

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
    @AfterEach void close() { if (server != null) server.close(); }

    @Test void pluginDiscoveryIsOfflineAndUsesTheNeutralSpi() {
        var plugins = ServiceLoader.load(TaxonomyPlugin.class).stream()
                .map(ServiceLoader.Provider::get).filter(p -> p instanceof LeanIxPlugin).toList();
        assertEquals(1, plugins.size());
        assertInstanceOf(InventorySourceExtension.class, plugins.getFirst().extensions().getFirst());
        assertEquals("leanix", plugins.getFirst().extensions().getFirst().id());
        plugins.forEach(TaxonomyPlugin::close);
    }
    @Test void readsObjectsWithTokenReuseAndForwardPagination() throws Exception {
        start((query, variables) -> variables.path("after").isNull()
                ? objects(true, "cursor-1", app("a1", 3, "App")) : objects(false, null, component("t1")));
        Observation value = read(false);
        assertEquals(2, value.objects().size()); assertEquals(3, server.calls.size());
        assertEquals(Completeness.SELECTED_SCOPE_READ_NON_ATOMIC, value.completeness());
        assertEquals(ObjectKind.APPLICATION, value.objects().getFirst().kind());
        assertEquals(ObjectKind.OTHER, value.objects().get(1).kind());
        assertEquals("cursor-1", JSON.readTree(server.calls.get(2).body()).path("variables").path("after").asText());
        assertFalse(value.toString().contains(API)); assertFalse(value.toString().contains(BEARER));
        assertEquals("endOfLife", value.objects().getFirst().lifecycle().getFirst().phase().value());
        assertEquals("2029-12-31", value.objects().getFirst().lifecycle().getFirst().startDate().value());
        assertEquals(AssertionKind.DECLARED, value.objects().getFirst().lifecycle().getFirst().assertionKind());
    }
    @Test void keepsMissingNullEmptyAndUnsupportedFieldsDistinct() throws Exception {
        start((q, v) -> objects(false, null, "{\"id\":\"a1\",\"type\":\"Application\",\"rev\":0,\"name\":\"\",\"description\":null}"));
        var value = read(false).objects().getFirst();
        assertEquals(State.PRESENT, value.fields().get("name").state());
        assertEquals("", value.fields().get("name").value());
        assertEquals(State.NULL, value.fields().get("description").state());
        assertEquals(State.ABSENT, value.lifecycleState());
        assertEquals(State.NOT_SUPPORTED, value.subtype().state());
    }
    @Test void mapsOnlyExplicitKnownSubtypesAndPreservesTheOriginalValue() throws Exception {
        start((q, v) -> objects(false, null, component("t1").replace("\"rev\":2", "\"rev\":2,\"inventory_ITComponent_subtype\":\"managedService\"")));
        var projection = new TypeProjection("ITComponent", ObjectKind.TECHNOLOGY_PRODUCT, "category",
                Map.of("managedService", ObjectKind.SERVICE), Map.of());
        var value = new LeanIxInventorySource().read(request(List.of(projection), false, Set.of(), limits()), access(true));
        assertEquals(ObjectKind.SERVICE, value.objects().getFirst().kind());
        assertEquals("managedService", value.objects().getFirst().subtype().value());
        assertTrue(server.calls.get(1).body().contains("inventory_ITComponent_subtype: category"));
    }
    @Test void multiTypeProjectionsValidateAgainstASchemaAndDecodeTheirOwnScalarAliases() throws Exception {
        var types = new SchemaParser().parse("""
                interface FactSheet { id: ID!, name: String, description: String, type: String!, rev: Int! }
                type Application implements FactSheet {
                  id: ID!, name: String, description: String, type: String!, rev: Int!, lifecycle: Lifecycle
                  category: String, externalCode: String
                }
                type ITComponent implements FactSheet {
                  id: ID!, name: String, description: String, type: String!, rev: Int!, lifecycle: Lifecycle
                  category: ID, externalCode: ID
                }
                type Lifecycle { phases: [LifecyclePhase!]! }
                type LifecyclePhase { phase: String, startDate: String }
                type PageInfo { hasNextPage: Boolean!, endCursor: String }
                type FactSheetEdge { node: FactSheet! }
                type FactSheetConnection { pageInfo: PageInfo!, edges: [FactSheetEdge!]! }
                input FacetFilterInput { facetKey: String!, operator: String!, keys: [String!]! }
                input FilterInput { facetFilters: [FacetFilterInput!]! }
                type Query {
                  allFactSheets(first: Int!, after: String, filter: FilterInput!): FactSheetConnection!
                  factSheet(id: ID!): FactSheet
                }
                """);
        var values = List.of(
                Map.<String, Object>of("id", "a1", "type", "Application", "name", "App", "rev", 1,
                        "category", "web", "externalCode", "APP-1"),
                Map.<String, Object>of("id", "t1", "type", "ITComponent", "name", "Platform", "rev", 2,
                        "category", "managedService", "externalCode", "TECH-1"));
        var wiring = RuntimeWiring.newRuntimeWiring()
                .type("FactSheet", type -> type.typeResolver(env ->
                        env.getSchema().getObjectType(((Map<?, ?>) env.getObject()).get("type").toString())))
                .type("Query", type -> type
                        .dataFetcher("allFactSheets", env -> Map.of("pageInfo", Map.of("hasNextPage", false),
                                "edges", values.stream().map(value -> Map.of("node", value)).toList()))
                        .dataFetcher("factSheet", env -> values.stream()
                                .filter(value -> value.get("id").equals(env.getArgument("id"))).findFirst().orElse(null)))
                .build();
        var schema = new SchemaGenerator().makeExecutableSchema(types, wiring);
        var graphql = GraphQL.newGraphQL(schema).build();
        start((query, variables) -> JSON.writeValueAsString(graphql.execute(ExecutionInput.newExecutionInput()
                .query(query).variables(JSON.convertValue(variables, new TypeReference<Map<String, Object>>() { })).build()).toSpecification()));
        var projections = List.of(
                new TypeProjection("Application", ObjectKind.APPLICATION, "category", Map.of("web", ObjectKind.APPLICATION),
                        Map.of("code", "externalCode")),
                new TypeProjection("ITComponent", ObjectKind.OTHER, "category", Map.of("managedService", ObjectKind.SERVICE),
                        Map.of("code", "externalCode")));
        for (boolean single : List.of(false, true)) {
            var request = request(projections, false, single ? Set.of("a1", "t1") : Set.of(), limits());
            var errors = new Validator().validateDocument(schema, new Parser().parseDocument(LeanIxQueries.objects(request, single)), Locale.ROOT);
            assertTrue(errors.isEmpty(), errors::toString);
            var observed = new LeanIxInventorySource().read(request, access(true)).objects();
            assertEquals(List.of("web", "managedService"), observed.stream().map(value -> value.subtype().value()).toList());
            assertEquals(List.of(ObjectKind.APPLICATION, ObjectKind.SERVICE), observed.stream().map(ObservedObject::kind).toList());
            assertEquals(List.of("APP-1", "TECH-1"), observed.stream().map(value -> value.fields().get("code").value()).toList());
            assertTrue(observed.stream().allMatch(value -> "category".equals(value.subtype().sourceField())
                    && "externalCode".equals(value.fields().get("code").sourceField())));
        }
    }
    @Test void selectedIdsUseVariablesAndDoNotEnumerateTheWorkspace() throws Exception {
        start((q, v) -> {
            assertEquals("a1", v.path("id").asText()); assertFalse(q.contains("allFactSheets"));
            return "{\"data\":{\"factSheet\":" + app("a1", 1, "A") + "}}";
        });
        var value = new LeanIxInventorySource().read(request(projections(), false, Set.of("a1"), limits()), access(true));
        assertEquals(1, value.objects().size()); assertEquals(2, server.calls.size());
        assertEquals(Set.of("a1"), value.request().selectedIds());
    }
    @Test void paginatesRelationsSeparatelyAndKeepsUnresolvedTargets() throws Exception {
        start((q, v) -> {
            if (q.contains("allFactSheets")) return objects(false, null, app("a1", 3, "A"));
            return relations(3, v.path("after").isNull(), v.path("after").isNull() ? "rel-cursor" : null,
                    v.path("after").isNull() ? "r1" : "r2");
        });
        var result = read(true);
        assertEquals(2, result.relations().size()); assertEquals(1, result.objects().size());
        assertEquals("a1", result.relations().getFirst().source().id());
        assertEquals("outside-selection", result.relations().getFirst().target().id());
        assertEquals("USES", result.relations().getFirst().meaning());
        assertEquals("2029-12-31", result.relations().getFirst().fields().get("activeUntil").value());
        assertEquals(4, server.calls.size());
    }
    @Test void relationRevisionChangeInvalidatesTheRead() throws Exception {
        start((q, v) -> q.contains("allFactSheets") ? objects(false, null, app("a1", 3, "A")) : relations(4, false, null, "r1"));
        assertEquals("SOURCE_CHANGED", assertThrows(ReadFailure.class, () -> read(true)).code());
    }
    @Test void graphqlErrorsWithHttp200AreNotPartialSuccess() throws Exception {
        start((q, v) -> "{\"data\":{\"allFactSheets\":{\"edges\":[]}},\"errors\":[{\"message\":\"" + API + "\"}]} ");
        var failure = assertThrows(ReadFailure.class, () -> read(false));
        assertFalse(failure.toString().contains(API)); assertNull(failure.getCause());
    }
    @Test void doesNotReturnEarlierObjectsWhenALaterPageFails() throws Exception {
        start((q, v) -> v.path("after").isNull() ? objects(true, "next", app("a1", 1, "A")) : "{\"errors\":[{}]}");
        assertThrows(ReadFailure.class, () -> read(false));
    }
    @Test void rejectsRepeatedCursors() throws Exception {
        AtomicInteger n = new AtomicInteger();
        start((q, v) -> objects(true, "same", app("a" + n.incrementAndGet(), 1, "A")));
        assertEquals("CURSOR_REPEATED", assertThrows(ReadFailure.class, () -> read(false)).code());
    }
    @Test void rejectsDuplicateObjectIdentities() throws Exception {
        start((q, v) -> objects(false, null, app("a1", 1, "A"), app("a1", 1, "B")));
        assertEquals("DUPLICATE_ID", assertThrows(ReadFailure.class, () -> read(false)).code());
    }
    @Test void rejectsMissingPagingStateInsteadOfAssumingCompleteness() throws Exception {
        start((q, v) -> "{\"data\":{\"allFactSheets\":{\"edges\":[]}}}");
        assertThrows(ReadFailure.class, () -> read(false));
    }
    @Test void rejectsUnexpectedObjectTypes() throws Exception {
        start((q, v) -> objects(false, null, app("a1", 1, "A").replace("Application", "UserGroup")));
        assertEquals("UNSUPPORTED_SOURCE_TYPE", assertThrows(ReadFailure.class, () -> read(false)).code());
    }
    @Test void keepsUnknownLifecyclePhaseWithoutClaimingActiveAvailability() throws Exception {
        start((q, v) -> objects(false, null, app("a1", 1, "A").replace("endOfLife", "localPhase")));
        var result = read(false);
        assertEquals("localPhase", result.objects().getFirst().lifecycle().getFirst().phase().value());
        assertTrue(result.limitations().contains("NO_AVAILABILITY_OR_SUITABILITY_VERDICT"));
    }
    @Test void refusesQuerySyntaxInConfiguredScalarFieldsBeforeNetwork() throws Exception {
        start((q, v) -> objects(false, null));
        var p = new TypeProjection("Application", ObjectKind.APPLICATION, "category){mutation", Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> new LeanIxInventorySource().read(request(List.of(p), false, Set.of(), limits()), access(true)));
        assertTrue(server.calls.isEmpty());
    }
    @Test void missingCredentialDoesNotContactTheServer() throws Exception {
        start((q, v) -> objects(false, null));
        assertEquals("CREDENTIAL_UNAVAILABLE", assertThrows(ReadFailure.class,
                () -> new LeanIxInventorySource().read(request(projections(), false, Set.of(), limits()), () -> null)).code());
        assertTrue(server.calls.isEmpty());
    }
    @Test void credentialResolverExceptionsAreSanitized() throws Exception {
        start((q, v) -> objects(false, null));
        var e = assertThrows(ReadFailure.class, () -> new LeanIxInventorySource().read(request(projections(), false, Set.of(), limits()),
                () -> { throw new IllegalStateException(API); }));
        assertNull(e.getCause()); assertFalse(e.toString().contains(API)); assertTrue(server.calls.isEmpty());
    }
    @Test void privateNetworksRequireHostApproval() throws Exception {
        start((q, v) -> objects(false, null));
        assertThrows(ReadFailure.class, () -> new LeanIxInventorySource().read(request(projections(), false, Set.of(), limits()), access(false)));
        assertTrue(server.calls.isEmpty());
    }
    @Test void detectsReflectedCredentialsInSuccessfulSourceData() throws Exception {
        start((q, v) -> objects(false, null, app("a1", 1, BEARER)));
        assertEquals("CREDENTIAL_REFLECTION", assertThrows(ReadFailure.class, () -> read(false)).code());
    }
    @Test void aggregateRequestBudgetIncludesAuthentication() throws Exception {
        start((q, v) -> objects(true, "next", app("a1", 1, "A")));
        var l = new Limits(10, 2, 10, 10, 4096, 16384, 10);
        assertEquals("READ_LIMIT", assertThrows(ReadFailure.class,
                () -> new LeanIxInventorySource().read(request(projections(), false, Set.of(), l), access(true))).code());
        assertEquals(2, server.calls.size());
    }
    @Test void httpFailuresAndRedirectsAreNotRetried() throws Exception {
        start((q, v) -> objects(false, null)); server.dataStatus = 307; server.expectDataWriteAbort = true;
        assertEquals("REDIRECT_REJECTED", assertThrows(ReadFailure.class, () -> read(false)).code()); assertEquals(2, server.calls.size());
        assertTrue(server.calls.stream().allMatch(c -> Set.of("/services/mtm/v1/oauth2/token", "/services/pathfinder/v1/graphql").contains(c.path())));
    }
    @Test void refusesAnOversizedResponse() throws Exception {
        start((q, v) -> objects(false, null, app("a1", 1, "x".repeat(20000))));
        server.expectDataWriteAbort = true;
        assertEquals("READ_LIMIT", assertThrows(ReadFailure.class, () -> new LeanIxInventorySource().read(
                request(projections(), false, Set.of(), new Limits(10, 20, 100, 100, 1024, 2048, 10)), access(true))).code());
        assertEquals(2, server.calls.size());
    }
    @Test void oversizedChunkedResponseIsAbortedWithoutDraining() throws Exception {
        startPausedResponse();
        assertAbortedWithoutDraining("READ_LIMIT");
    }
    @Test void unauthorizedChunkedResponseIsAbortedWithoutDraining() throws Exception {
        startPausedResponse(); server.dataStatus = 403;
        assertAbortedWithoutDraining("UNAUTHORIZED");
    }
    @Test void unsupportedChunkedMediaTypeIsAbortedWithoutDraining() throws Exception {
        startPausedResponse(); server.dataMediaType = "text/plain";
        assertAbortedWithoutDraining("MEDIA_TYPE");
    }
    @Test void encodedChunkedResponseIsAbortedWithoutDraining() throws Exception {
        startPausedResponse(); server.dataEncoding = "gzip";
        assertAbortedWithoutDraining("MEDIA_TYPE");
    }
    @Test void oversizedContentLengthIsAbortedWithoutDraining() throws Exception {
        startPausedResponse(); server.chunkedData = false;
        assertAbortedWithoutDraining("READ_LIMIT");
    }
    @Test void invalidJsonIsNotAnEmptyInventory() throws Exception {
        start((q, v) -> "{\"data\":{},\"data\":{}} {}");
        assertEquals("INVALID_JSON", assertThrows(ReadFailure.class, () -> read(false)).code());
    }
    @Test void aNullSelectedFactSheetIsNotADeletion() throws Exception {
        start((q, v) -> "{\"data\":{\"factSheet\":null}}");
        assertThrows(ReadFailure.class, () -> new LeanIxInventorySource().read(request(projections(), false, Set.of("a1"), limits()), access(true)));
    }

    @Test void permissionErrorsRemainExplicitAndAreNotRetried() throws Exception {
        start((q, v) -> objects(false, null)); server.dataStatus = 403; server.expectDataWriteAbort = true;
        assertEquals("UNAUTHORIZED", assertThrows(ReadFailure.class, () -> read(false)).code());
        assertEquals(2, server.calls.size());
    }
    @Test void rateLimitsRemainExplicitAndAreNotRetried() throws Exception {
        start((q, v) -> objects(false, null)); server.dataStatus = 429; server.expectDataWriteAbort = true;
        assertEquals("RATE_LIMITED", assertThrows(ReadFailure.class, () -> read(false)).code());
        assertEquals(2, server.calls.size());
    }
    @Test void exhaustedTokenLifetimeStopsBeforeGraphql() throws Exception {
        start((q, v) -> objects(false, null)); server.tokenExpires = 1;
        assertEquals("TOKEN_EXPIRED", assertThrows(ReadFailure.class, () -> read(false)).code());
        assertEquals(1, server.calls.size());
    }
    @Test void metadataAddressesRemainForbiddenEvenWithPrivateNetworkOptIn() throws Exception {
        assertFalse(LeanIxHttp.allowed(InetAddress.getByName("169.254.169.254"), true));
        assertFalse(LeanIxHttp.allowed(InetAddress.getByName("fd00:ec2::254"), true));
        assertFalse(LeanIxHttp.allowed(InetAddress.getByName("100.100.100.200"), true));
        assertFalse(LeanIxHttp.allowed(InetAddress.getByName("0.0.0.0"), true));
        assertFalse(LeanIxHttp.allowed(InetAddress.getByName("224.0.0.1"), true));
        assertFalse(LeanIxHttp.allowed(InetAddress.getByName("::1"), false));
        assertTrue(LeanIxHttp.allowed(InetAddress.getByName("::1"), true));
    }
    @Test void rejectedOriginCannotRequestOrExposeAnyCredential() {
        for (String value : List.of("http://example.test/", "https://u:p@example.test/",
                "https://example.test/?token=x", "https://example.test/a/", "https://example.test/#x"))
            assertThrows(IllegalArgumentException.class, () -> LeanIxHttp.validateOrigin(URI.create(value)));
    }
    @Test void objectLimitReturnsNoSuccessfulPrefix() throws Exception {
        start((q, v) -> objects(false, null, app("a1", 1, "A"), app("a2", 1, "B")));
        var limits = new Limits(10, 20, 1, 10, 65536, 262144, 10);
        assertEquals("READ_LIMIT", assertThrows(ReadFailure.class,
                () -> new LeanIxInventorySource().read(request(projections(), false, Set.of(), limits), access(true))).code());
    }
    @Test void aBoundCredentialIsWipedAfterUse() throws Exception {
        start((q, v) -> objects(false, null));
        char[] owned = API.toCharArray();
        new LeanIxInventorySource().read(request(projections(), false, Set.of(), limits()), new ReadAccess() {
            public char[] credential() { return owned; }
            public SSLContext tls() { return tls; }
            public boolean allowPrivateNetworks() { return true; }
        });
        assertArrayEquals(new char[API.length()], owned);
    }

    @Test void fixtureAcceptsOnlyExplicitlyExpectedBodyWriteDisconnects() {
        var attempted = new AtomicInteger();
        assertDoesNotThrow(() -> Server.writeBody(() -> {
            attempted.incrementAndGet();
            throw new IOException("Synthetic peer reset while sending a rejected response");
        }, true));
        assertEquals(1, attempted.get(), "A rejected response must not be retried");
    }
    @Test void fixtureRetainsUnexpectedBodyWriteFailures() {
        var failure = new IOException("Unexpected response write failure");
        assertSame(failure, assertThrows(IOException.class,
                () -> Server.writeBody(() -> { throw failure; }, false)));
    }
    @Test void fixtureNeverSuppressesAssertionsEvenWhenBodyAbortIsExpected() {
        var failure = new AssertionError("Request contract mismatch");
        assertSame(failure, assertThrows(AssertionError.class,
                () -> Server.writeBody(() -> { throw failure; }, true)));
    }

    private static List<TypeProjection> projections() {
        return List.of(new TypeProjection("Application", ObjectKind.APPLICATION, null, Map.of(), Map.of()),
                new TypeProjection("ITComponent", ObjectKind.OTHER, null, Map.of(), Map.of()));
    }
    private static Limits limits() { return new Limits(10, 20, 100, 100, 65536, 262144, 10); }
    private Request request(List<TypeProjection> p, boolean relations, Set<String> ids, Limits l) {
        var state = new InternalState("repo", "workspace", "main", null, 0, null, null);
        var context = new IntegrationContext(UUID.randomUUID(), AuthorityMode.LINK_ONLY,
                new ExternalScope("leanix", "operator-declared-workspace", null), state, "test-user", "leanix-read", "1");
        return new Request(context, "test-instance", "projection-1", server.origin(), p, ids, relations, l);
    }
    private ReadAccess access(boolean networks) {
        return new ReadAccess() {
            public char[] credential() { return API.toCharArray(); }
            public SSLContext tls() { return tls; }
            public boolean allowPrivateNetworks() { return networks; }
        };
    }
    private Observation read(boolean relations) { return new LeanIxInventorySource().read(request(projections(), relations, Set.of(), limits()), access(true)); }
    private void start(Reply reply) throws Exception { server = new Server(reply); }
    private void startPausedResponse() throws Exception {
        start((q, v) -> "x".repeat(2 * 1024 * 1024));
        server.pauseData = true;
    }
    private void assertAbortedWithoutDraining(String code) throws Exception {
        try (var reader = Executors.newSingleThreadExecutor()) {
            var result = reader.submit(() -> assertThrows(ReadFailure.class, () -> new LeanIxInventorySource().read(
                    request(projections(), false, Set.of(), new Limits(10, 20, 100, 100, 1024, 2048, 30)), access(true))));
            try {
                assertTrue(server.dataPrefixSent.await(20, TimeUnit.SECONDS), "Server must begin its response before rejection is timed");
                // The rest of the body is unavailable until the client has returned its rejection.
                var rejected = assertDoesNotThrow(() -> result.get(2, TimeUnit.SECONDS),
                        "Rejection must return before the server releases the remaining response body");
                assertEquals(code, rejected.code());
                assertTrue(server.dataBytesWritten.get() <= 2048);
                assertEquals(2, server.calls.size());
            } finally { server.releaseData.countDown(); }
        }
    }
    private static String app(String id, int revision, String name) {
        return "{\"id\":\"" + id + "\",\"type\":\"Application\",\"rev\":" + revision + ",\"name\":\"" + name
                + "\",\"description\":\"Test\",\"lifecycle\":{\"phases\":[{\"phase\":\"endOfLife\",\"startDate\":\"2029-12-31\"}]}}";
    }
    private static String component(String id) { return "{\"id\":\"" + id + "\",\"type\":\"ITComponent\",\"rev\":2,\"name\":\"Platform\",\"description\":null,\"lifecycle\":null}"; }
    private static String objects(boolean next, String cursor, String... values) {
        return "{\"data\":{\"allFactSheets\":" + connection(next, cursor, values) + "}}";
    }
    private static String connection(boolean next, String cursor, String... values) {
        return "{\"pageInfo\":{\"hasNextPage\":" + next + ",\"endCursor\":" + (cursor == null ? "null" : "\"" + cursor + "\"")
                + "},\"edges\":[" + Arrays.stream(values).map(v -> "{\"node\":" + v + "}").collect(java.util.stream.Collectors.joining(",")) + "]}";
    }
    private static String relations(int rev, boolean next, String cursor, String id) {
        return "{\"data\":{\"factSheet\":{\"id\":\"a1\",\"rev\":" + rev + ",\"relApplicationToITComponent\":"
                + connection(next, cursor, "{\"id\":\"" + id + "\",\"activeFrom\":\"2028-01-01\",\"activeUntil\":\"2029-12-31\",\"factSheet\":{\"id\":\"outside-selection\",\"type\":\"ITComponent\"}}") + "}}}";
    }
    @FunctionalInterface interface Reply { String response(String query, JsonNode variables) throws Exception; }
    record Call(String path, String body) { }
    static class Server implements AutoCloseable {
        final HttpsServer http;
        final ExecutorService threads = Executors.newCachedThreadPool();
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch dataPrefixSent = new CountDownLatch(1);
        final CountDownLatch releaseData = new CountDownLatch(1);
        final AtomicInteger dataBytesWritten = new AtomicInteger();
        volatile int dataStatus = 200;
        volatile int tokenExpires = 3599;
        // Opt in only when the test asserts a specific header-rejection code.
        // Authentication and request/handler assertions always remain strict.
        volatile boolean expectDataWriteAbort;
        volatile boolean pauseData;
        volatile boolean chunkedData = true;
        volatile String dataMediaType = "application/json";
        volatile String dataEncoding;
        Server(Reply reply) throws Exception {
            http = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setHttpsConfigurator(new HttpsConfigurator(tls)); http.setExecutor(threads);
            http.createContext("/", ex -> {
                try {
                    assertEquals("POST", ex.getRequestMethod());
                    String path = ex.getRequestURI().getPath();
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    calls.add(new Call(path, body));
                    String response; int status = 200;
                    if (path.equals("/services/mtm/v1/oauth2/token")) {
                        assertEquals("Basic " + Base64.getEncoder().encodeToString(("apitoken:" + API).getBytes(StandardCharsets.UTF_8)), ex.getRequestHeaders().getFirst("Authorization"));
                        assertEquals("grant_type=client_credentials", body);
                        response = "{\"access_token\":\"" + BEARER + "\",\"token_type\":\"bearer\",\"expires_in\":" + tokenExpires + "}";
                    } else {
                        assertEquals("/services/pathfinder/v1/graphql", path);
                        assertEquals("Bearer " + BEARER, ex.getRequestHeaders().getFirst("Authorization"));
                        JsonNode input = JSON.readTree(body); String q = input.path("query").asText();
                        assertTrue(q.stripLeading().startsWith("query ")); assertFalse(q.contains("mutation"));
                        response = reply.response(q, input.path("variables")); status = dataStatus;
                    }
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    boolean data = !path.equals("/services/mtm/v1/oauth2/token");
                    ex.getResponseHeaders().set("Content-Type", data ? dataMediaType : "application/json");
                    if (data && dataEncoding != null) ex.getResponseHeaders().set("Content-Encoding", dataEncoding);
                    if (status == 307) ex.getResponseHeaders().set("Location", "/forbidden");
                    if (data && pauseData) {
                        ex.sendResponseHeaders(status, chunkedData ? 0 : bytes.length);
                        try {
                            ex.getResponseBody().write(bytes, 0, 2048); ex.getResponseBody().flush();
                            dataBytesWritten.addAndGet(2048); dataPrefixSent.countDown();
                            assertTrue(releaseData.await(10, TimeUnit.SECONDS));
                            ex.getResponseBody().write(bytes, 2048, bytes.length - 2048);
                            dataBytesWritten.addAndGet(bytes.length - 2048);
                        } catch (IOException expectedCancellation) { /* Rejected responses must abort the socket. */ }
                        finally { dataPrefixSent.countDown(); }
                    } else {
                        ex.sendResponseHeaders(status, bytes.length);
                        writeBody(() -> ex.getResponseBody().write(bytes), data && expectDataWriteAbort);
                    }
                } catch (Throwable e) { failure.compareAndSet(null, e); ex.sendResponseHeaders(500, -1); }
                finally { ex.close(); }
            }); http.start();
        }
        @FunctionalInterface interface BodyWrite { void run() throws IOException; }
        static void writeBody(BodyWrite body, boolean expectAbort) throws IOException {
            try { body.run(); }
            catch (IOException writeFailure) {
                // Cancelling a rejected response may reset the socket before the
                // server has sent the body. Do not hide other fixture failures.
                if (!expectAbort) throw writeFailure;
            }
        }
        URI origin() { return URI.create("https://127.0.0.1:" + http.getAddress().getPort() + "/"); }
        public void close() {
            http.stop(0); releaseData.countDown(); threads.shutdown();
            try {
                if (!threads.awaitTermination(5, TimeUnit.SECONDS)) {
                    threads.shutdownNow();
                    throw new AssertionError("HTTPS fixture handlers did not terminate");
                }
            } catch (InterruptedException interrupted) {
                threads.shutdownNow(); Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            // Inspect failures only after every handler has had a chance to report them.
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }
}
