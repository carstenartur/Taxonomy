package com.taxonomy;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in paired requirement evaluation through the regular HTTP, storage and provider boundaries. */
@Tag("real-llm")
@EnabledIfSystemProperty(named = "taxonomy.architecture.evaluateLive", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "taxonomy.admin-password=" + ScenarioArchitectureAcceptanceTest.PASSWORD,
        "taxonomy.security.require-password-change=false", "taxonomy.analysis.transport.mode=local",
        "embedding.enabled=false", "embedding.allow-download=false", "taxonomy.catalogue.overlay.enabled=false",
        "llm.provider=CUSTOM_OPENAI", "llm.mock=false", "llm.replay=false", "llm.record=true",
        "custom.llm.structured-output-enabled=true", "taxonomy.llm.rpm=120", "taxonomy.llm.timeout-seconds=180"})
class RequirementArchitectureVllmLiveTest {
    private static final Set<String> LEAVES = Set.of("IP-1265", "IP-1472");
    private static final String CATALOGUE = "/data/bilingual/C3_Taxonomy_Catalogue_25AUG2025.xlsx";
    private static final String DIRECTORY = "target/vllm-architecture";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CATALOGUE_REQUIREMENT = "The municipal library needs an IT service catalogue as a document listing each information technology service available to library users, its description, and the responsible team. Physical building entry lists are outside the scope of this request.";
    private static final String ACCESS_REQUIREMENT = "The municipal library needs a physical access list identifying the staff who may enter the locked archive room. The deliverable is the list of authorised people. The library is not requesting a catalogue of information technology services.";

    private final Set<String> observedCompletionIds = new HashSet<>();

    @LocalServerPort int port;
    @Value("${custom.llm.model}") String model;
    @Value("${llm.recordings.dir}") Path recordings;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) throws Exception {
        String url = requiredEnvironment("CUSTOM_LLM_URL");
        String model = requiredEnvironment("CUSTOM_LLM_MODEL");
        Path run = Files.createTempDirectory(Files.createDirectories(Path.of(DIRECTORY)), "run-").toAbsolutePath();
        Path file = run.resolve("catalogue.xlsx");
        writeCatalogue(file);
        properties.add("custom.llm.url", () -> url);
        properties.add("custom.llm.model", () -> model);
        properties.add("llm.recordings.dir", () -> run.resolve("provider-responses").toString());
        properties.add("taxonomy.catalogue.resource", () -> file.toUri().toString());
    }

    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + key + " for the live vLLM evaluation");
        return value;
    }

    private static void writeCatalogue(Path file) throws Exception {
        try (var input = RequirementArchitectureVllmLiveTest.class.getResourceAsStream(CATALOGUE);
             var source = new XSSFWorkbook(input); var subset = new XSSFWorkbook()) {
            var sheet = subset.createSheet("Information Products");
            var formatter = new DataFormatter(Locale.ROOT);
            for (var row : source.getSheet(sheet.getSheetName())) {
                if (row.getRowNum() != 0 && !LEAVES.contains(formatter.formatCellValue(row.getCell(0)))) continue;
                var copy = sheet.createRow(sheet.getPhysicalNumberOfRows());
                for (var cell : row) copy.createCell(cell.getColumnIndex()).setCellValue(formatter.formatCellValue(cell));
            }
            assertThat(sheet.getPhysicalNumberOfRows()).as("Official header and both catalogue entries").isEqualTo(3);
            subset.createSheet("Relations");
            try (var output = Files.newOutputStream(file)) { subset.write(output); }
        }
    }

    @Test
    void compareTheScoresAndResultingArchitectureForTwoLibraryRequirements() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        var http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
        var session = new ScenarioHttpSession(http, cookies, JSON, "admin", ScenarioArchitectureAcceptanceTest.PASSWORD);
        var run = recordings.getParent();
        var catalogue = analyze(http, session, run, "service-catalogue", CATALOGUE_REQUIREMENT);
        var access = analyze(http, session, run, "building-access", ACCESS_REQUIREMENT);
        var comparison = new LinkedHashMap<String, Object>();
        comparison.put("model", model);
        comparison.put("sameCatalogue", catalogue.at("/modelBinding/catalogueRevision").equals(access.at("/modelBinding/catalogueRevision")));
        comparison.put("scoresChanged", !catalogue.path("rawScores").equals(access.path("rawScores")));
        comparison.put("includedNodesChanged", !includedNodes(catalogue).equals(includedNodes(access)));
        comparison.put("includedRelationshipsChanged", !catalogue.at("/architectureView/includedRelationships")
                .equals(access.at("/architectureView/includedRelationships")));
        JSON.writerWithDefaultPrettyPrinter().writeValue(run.resolve("comparison.json").toFile(), comparison);
        assertThat(access.at("/modelBinding/catalogueRevision")).isEqualTo(catalogue.at("/modelBinding/catalogueRevision"));
        assertThat(access.at("/modelBinding/commit")).isEqualTo(catalogue.at("/modelBinding/commit"));
        assertThat(access.required("analysisOperationId")).isNotEqualTo(catalogue.required("analysisOperationId"));
    }

    private JsonNode analyze(HttpClient http, ScenarioHttpSession session, Path run, String label, String text) throws Exception {
        var payload = Map.of("businessText", text, "analysisScope", Map.of("mode", "FULL", "taxonomyRoots", List.of("IP")),
                "analysisLanguage", "en", "analysisReasonLength", "short", "includeArchitectureView", true,
                "provider", "CUSTOM_OPENAI", "maxArchitectureNodes", 30);
        JSON.writerWithDefaultPrettyPrinter().writeValue(run.resolve(label + "-request.json").toFile(), payload);
        var request = session.request(port, "POST", "/api/analyze").timeout(Duration.ofMinutes(15))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Files.writeString(run.resolve(label + "-result.json"), response.body());
        assertThat(response.statusCode()).as("POST /api/analyze").isEqualTo(200);
        JsonNode result = JSON.readTree(response.body());
        // An unreachable provider can legitimately return HTTP 200/PARTIAL with no assessments.
        // Require fresh transport evidence and complete score coverage independently of architecture quality.
        assertFreshCompletions(text);
        assertThat(result.required("analysisProviderId").asString()).isEqualTo("CUSTOM_OPENAI");
        assertThat(result.required("analysisOperationId").asString()).isNotBlank();
        assertThat(result.at("/modelBinding/catalogueRevision").asString()).isNotBlank();
        assertThat(result.at("/modelBinding/commit").asString()).isNotBlank();
        assertThat(result.at("/analysisCoverage/assessedNodes").asInt()).isEqualTo(3);
        assertThat(result.at("/analysisCoverage/unknownNodes").asInt()).isZero();
        var scores = result.required("rawScores");
        assertThat(scores.propertyNames()).containsExactlyInAnyOrder("IP", "IP-1265", "IP-1472");
        for (var value : scores) {
            assertThat(value.isNumber()).isTrue();
            assertThat(value.doubleValue()).isBetween(0d, 100d);
        }
        var graph = result.required("architectureView");
        assertThat(graph.required("originalRequirementText").asString()).isEqualTo(text);
        assertThat(graph.required("totalElements").asInt()).isEqualTo(graph.required("includedElements").size());
        assertThat(graph.required("totalRelationships").asInt()).isEqualTo(graph.required("includedRelationships").size());
        var included = includedNodes(result);
        for (var code : included) assertThat(scores.has(code)).as("Included catalogue node %s", code).isTrue();
        for (var edge : graph.get("includedRelationships")) {
            assertThat(included).contains(edge.required("sourceCode").asString(), edge.required("targetCode").asString());
            assertThat(edge.required("relationType").asString()).isNotBlank();
        }
        return result;
    }

    private void assertFreshCompletions(String requirement) throws Exception {
        assertThat(recordings).as("Fresh production gateway recordings in the unique run directory").isDirectory();
        Set<String> completionIds = new HashSet<>();
        boolean rootAnswer = false, childAnswer = false;
        try (var files = Files.list(recordings)) {
            for (var file : files.filter(p -> p.getFileName().toString().matches("sha256-[0-9a-f]{64}\\.json")).toList()) {
                var entry = JSON.readTree(file.toFile());
                if (!entry.required("promptText").asString().contains(requirement)) continue;
                assertThat(entry.required("provider").asString()).isEqualTo("CUSTOM_OPENAI");
                var raw = JSON.readTree(entry.required("responseBody").asString());
                assertThat(raw.required("model").asString()).isEqualTo(model);
                var decision = JSON.readTree(raw.at("/choices/0/message/content").asString());
                rootAnswer |= decision.has("IP");
                childAnswer |= Set.copyOf(decision.propertyNames()).equals(LEAVES);
                assertThat(raw.required("id").asString()).isNotBlank();
                completionIds.add(raw.get("id").asString());
            }
        }
        assertThat(rootAnswer).as("Live root decision for this requirement").isTrue();
        assertThat(childAnswer).as("Live decision containing both offered child codes").isTrue();
        assertThat(completionIds).as("Completion IDs returned for this requirement").isNotEmpty();
        assertThat(Collections.disjoint(completionIds, observedCompletionIds)).as("Fresh IDs for each requirement").isTrue();
        observedCompletionIds.addAll(completionIds);
    }

    private static Set<String> includedNodes(JsonNode result) {
        var codes = new TreeSet<String>();
        for (var node : result.at("/architectureView/includedElements")) codes.add(node.required("nodeCode").asString());
        return codes;
    }
}
