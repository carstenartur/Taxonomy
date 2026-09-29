package com.taxonomy.acceptance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taxonomy.dto.TaxonomyDataFingerprint;
import com.taxonomy.dto.TaxonomyNodeDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class OnnxReferenceEvaluationTest {
    @TempDir Path temporary;

    private static final String CATALOGUE = """
            [{"code":"CP","nameEn":"Capabilities","descriptionEn":"Root description",
              "name":"Capabilities","description":"Root description",
              "taxonomyRoot":"CP","level":0,"analysisRole":"CATEGORY","children":[
                {"code":"CP-1","nameEn":"Payroll","descriptionEn":"Payroll processing",
                 "taxonomyRoot":"CP","level":1,"analysisRole":"PRODUCT","children":[]}
              ]}]
            """;

    @Test void reportUsesCanonicalCatalogueIdentityIncludingInheritedParentAndVersion() throws Exception {
        var catalogue = new ObjectMapper().readTree(CATALOGUE);
        assertEquals("4ef66c20abe633ad6f2abce22cb6a138444b32f5815d79158ca6ba2041092d13",
                OnnxReferenceEvaluation.catalogueFingerprint(catalogue));
    }

    @Test void scoreSemanticsChangesInvalidateTheEvaluationCatalogue() throws Exception {
        var mapper = new ObjectMapper();
        String original = OnnxReferenceEvaluation.catalogueFingerprint(mapper.readTree(CATALOGUE));
        assertNotEquals(original, OnnxReferenceEvaluation.catalogueFingerprint(
                mapper.readTree(CATALOGUE.replace("\"PRODUCT\"", "\"CATEGORY\""))));
        assertNotEquals(original, OnnxReferenceEvaluation.catalogueFingerprint(
                mapper.readTree(CATALOGUE.replace("\"level\":1", "\"level\":2"))));
    }

    @Test void missingModelCannotPublishOldSuccessOrDropUnexecutedCases() throws Exception {
        Path outputDirectory = temporary.resolve("reports");
        Files.createDirectories(outputDirectory);
        Files.writeString(outputDirectory.resolve("report.json"), "STALE SUCCESS");
        Path jar = temporary.resolve("metadata-only-test.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("BOOT-INF/classes/git.properties"));
            output.write(("git.commit.id=" + "a".repeat(40) + "\ngit.dirty=false\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        // Fails before any HTTP call: no ONNX model, provider or fabricated responses.
        assertThrows(java.io.IOException.class, () -> OnnxReferenceEvaluation.verify(
                URI.create("http://127.0.0.1:1"), "AUTHORIZATION_MUST_NOT_APPEAR", temporary, jar, outputDirectory));
        String text = Files.readString(outputDirectory.resolve("report.json"));
        var report = new ObjectMapper().readTree(text);
        assertEquals("ERROR", report.path("status").stringValue());
        assertEquals("LOCAL_ONNX_EVALUATION_ATTEMPT", report.path("evidenceKind").stringValue());
        assertEquals(12, report.path("cases").size());
        for (var row : report.path("cases")) {
            assertEquals("NOT_RUN", row.path("status").stringValue());
            assertTrue(row.path("measurement").isNull());
        }
        assertFalse(text.contains("AUTHORIZATION_MUST_NOT_APPEAR"));
        assertFalse(text.contains("STALE SUCCESS"));
    }
    @Test void reportsTheCanonicalVersionedCatalogueFingerprint() throws Exception {
        try (var fixture = new RetrievalFixture(temporary.resolve("canonical"))) {
            fixture.verify();
            var report = fixture.report();
            assertEquals(TaxonomyDataFingerprint.sha256(fixture.nodes),
                    report.path("catalogueSha256").stringValue());
            assertEquals(2, report.path("schemaVersion").asInt());
            assertEquals("TaxonomyDataFingerprint.sha256",
                    report.path("catalogueFingerprintAlgorithm").stringValue());
            assertTrue(report.path("catalogueProjectionSha256").stringValue().matches("[0-9a-f]{64}"));
            assertNotEquals(report.path("catalogueSha256"), report.path("catalogueProjectionSha256"));
            assertEquals(12, report.path("attemptedSearchCalls").asInt());
        }
    }

    @Test void retainsPartialNodeReadinessInTheCompletedReport() throws Exception {
        try (var fixture = new RetrievalFixture(temporary.resolve("partial"))) {
            // One PARTIAL response, then READY: a regressed evaluator fails this assertion
            // rather than hanging for its ten-minute production readiness deadline.
            fixture.firstState = "PARTIAL";
            fixture.verify();
            assertEquals("PARTIAL", fixture.report().path("indexState").stringValue());
            assertEquals(12, fixture.report().path("attemptedSearchCalls").asInt());
        }
    }

    @Test void levelChangesCannotLeaveTheEvaluationSuccessful() throws Exception {
        assertCatalogueMutationRejected("level", roots -> leaf(roots).put("level", 7));
    }

    @Test void analysisRoleChangesCannotLeaveTheEvaluationSuccessful() throws Exception {
        assertCatalogueMutationRejected("role", roots -> leaf(roots).put("analysisRole", "PRODUCT"));
    }

    @Test void movingANodeWithoutExplicitParentCodeChangesCanonicalEvidence() throws Exception {
        assertCatalogueMutationRejected("parent", roots -> {
            var first = (ArrayNode) roots.at("/0/children");
            var second = (ArrayNode) roots.at("/1/children");
            second.add(first.remove(0));
        });
    }

    @Test void supplementaryProjectionStillDetectsTranslationChanges() throws Exception {
        assertCatalogueMutationRejected("translation", roots -> leaf(roots).put("nameDe", "Anderer Name"));
    }

    @Test void reorderingTheSameCatalogueDoesNotCreateAFalseChange() throws Exception {
        try (var fixture = new RetrievalFixture(temporary.resolve("ordering"))) {
            fixture.secondCatalogue = roots -> {
                var children = (ArrayNode) roots.at("/0/children");
                children.add(children.remove(0));
                roots.add(roots.remove(0));
            };
            fixture.verify();
            assertEquals("MEASURED_REFERENCES_FOUND", fixture.report().path("status").stringValue());
        }
    }

    @Test void malformedCanonicalFieldsCannotBeCoercedIntoUnchangedEvidence() throws Exception {
        assertCatalogueMutationRejected("invalid-level", roots -> leaf(roots).put("level", "1"));
        assertCatalogueMutationRejected("invalid-role", roots -> leaf(roots).put("analysisRole", 42));
    }

    @Test void duplicateCatalogueCodesAreStillRejected() throws Exception {
        assertCatalogueMutationRejected("duplicate", roots -> {
            var children = (ArrayNode) roots.at("/0/children");
            children.add(children.get(0).deepCopy());
        });
    }

    private void assertCatalogueMutationRejected(String name, Consumer<ArrayNode> mutation) throws Exception {
        try (var fixture = new RetrievalFixture(temporary.resolve(name))) {
            fixture.secondCatalogue = mutation;
            assertThrows(IOException.class, fixture::verify);
            var report = fixture.report();
            assertEquals("ERROR", report.path("status").stringValue());
            assertEquals(12, report.path("attemptedSearchCalls").asInt());
            assertFalse(Files.readString(fixture.output.resolve("report.json")).contains("PRIVATE_TEST_AUTH"));
        }
    }

    private static ObjectNode leaf(ArrayNode roots) {
        return (ObjectNode) roots.at("/0/children/0");
    }

    /** Synthetic HTTP fixtures exercise the evaluator contract, never model quality. */
    private static final class RetrievalFixture implements AutoCloseable {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final HttpServer server;
        private final Path model;
        private final Path jar;
        private final Path output;
        private final List<OnnxReferenceCases.Case> cases = OnnxReferenceCases.load();
        private final List<TaxonomyNodeDto> nodes;
        private final AtomicInteger catalogueRequests = new AtomicInteger();
        private final AtomicInteger statusRequests = new AtomicInteger();
        private String firstState = "READY";
        private Consumer<ArrayNode> secondCatalogue = roots -> { };

        private RetrievalFixture(Path directory) throws IOException {
            Files.createDirectories(directory);
            model = Files.createDirectory(directory.resolve("model"));
            // Metadata-only placeholders. No model or provider is loaded by these tests.
            for (String name : List.of("model.onnx", "tokenizer.json", "tokenizer_config.json",
                    "special_tokens_map.json", "config.json")) {
                Files.writeString(model.resolve(name), "evaluator contract fixture only");
            }
            jar = directory.resolve("metadata-only.jar");
            try (var archive = new JarOutputStream(Files.newOutputStream(jar))) {
                archive.putNextEntry(new JarEntry("BOOT-INF/classes/git.properties"));
                archive.write(("git.commit.id=" + "b".repeat(40) + "\ngit.dirty=true\n")
                        .getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
            var primary = node("UA", "Applications", 0);
            var alternate = node("UA-OTHER", "Other applications", 0);
            var codes = new HashSet<String>();
            for (var test : cases) {
                String code = test.required().iterator().next();
                if (codes.add(code)) primary.getChildren().add(node(code, test.expectedTitle(), 1));
            }
            nodes = List.of(primary, alternate);
            output = directory.resolve("report");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::respond);
            server.start();
        }

        private void verify() throws Exception {
            OnnxReferenceEvaluation.verify(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "PRIVATE_TEST_AUTH", model, jar, output);
        }

        private JsonNode report() throws IOException {
            return JSON.readTree(Files.readString(output.resolve("report.json")));
        }

        private void respond(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                JsonNode response;
                if ("/api/taxonomy".equals(path)) {
                    ArrayNode roots = JSON.valueToTree(nodes);
                    if (catalogueRequests.incrementAndGet() > 1) secondCatalogue.accept(roots);
                    response = roots;
                } else if ("/api/embedding/status".equals(path)) {
                    response = JSON.createObjectNode().put("enabled", true).put("available", true)
                            .put("modelAvailable", true).put("semanticReady", true)
                            .put("indexState", statusRequests.incrementAndGet() == 1 ? firstState : "READY")
                            .put("indexedNodesAtReadiness", 5);
                } else if ("/api/search".equals(path) || "/api/search/semantic".equals(path)) {
                    String raw = exchange.getRequestURI().getRawQuery();
                    String query = Arrays.stream(raw.split("&")).filter(part -> part.startsWith("q="))
                            .map(part -> URLDecoder.decode(part.substring(2), StandardCharsets.UTF_8))
                            .findFirst().orElseThrow();
                    var test = cases.stream().filter(candidate -> candidate.query().equals(query))
                            .findFirst().orElseThrow();
                    response = JSON.createArrayNode().add(JSON.createObjectNode()
                            .put("code", test.required().iterator().next()));
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = JSON.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        private static TaxonomyNodeDto node(String code, String name, int level) {
            var result = new TaxonomyNodeDto();
            result.setCode(code);
            result.setNameEn(name);
            result.setTaxonomyRoot("UA");
            result.setLevel(level);
            return result;
        }

        @Override public void close() { server.stop(0); }
    }
}
