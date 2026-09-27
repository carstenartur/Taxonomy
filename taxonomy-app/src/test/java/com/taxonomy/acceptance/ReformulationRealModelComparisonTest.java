package com.taxonomy.acceptance;

import com.taxonomy.analysis.reformulation.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in only. Executes production synthesis/transport; never installs playback. */
@Tag("real-llm")
@SpringBootTest(properties = {"llm.mock=false", "embedding.enabled=false", "embedding.allow-download=false", "taxonomy.init.async=false"})
class ReformulationRealModelComparisonTest {
    @Autowired LlmProviderConfig providers;
    @Autowired TaxonomyService catalogue;
    @Autowired FrozenReformulationEngine walkUp;
    @Autowired CrossTaxonomyReconciler reconciliation;
    @Autowired NodeReformulationService nodes;
    @Autowired ObjectMapper json;
    private static final Set<String> FRONT = Set.of("BP", "BP-1000", "BP-1327", "BP-1060", "BP-1017",
            "IP", "IP-1000", "IP-1005", "IP-1102", "IP-1116");

    @Test void compareFiveAuthoredCasesWithTheSameSourceContextProviderAndModel() throws Exception {
        Path output = Path.of("target/reformulation-model-comparison"); Files.createDirectories(output);
        var provider = providers.getActiveProvider();
        var manifest = json.createObjectNode().put("recordedAt", Instant.now().toString())
                .put("humanQualityGate", "NOT_REVIEWED").put("pricingBasis", "NOT_PROVIDED; no currency cost inferred");
        if (provider == LlmProvider.LOCAL_ONNX || providers.isMockMode() || !providers.isProviderConfigured(provider)
                || providers.getProviderConfigurationError(provider) != null) {
            manifest.put("status", "NOT_RUN").put("reason", "No configured generative provider available");
            write(output.resolve("manifest.json"), manifest);
            Assumptions.assumeTrue(false, "NOT RUN: configure a real generative provider to evaluate model quality");
        }
        String model = provider == LlmProvider.GEMINI
                ? providers.getGeminiUrl().replaceFirst(".*models/([^:/?]+).*", "$1")
                : providers.getOpenAiCompatibleModel(provider);
        manifest.put("provider", provider.name()).put("model", model).put("status", "RUNNING");
        write(output.resolve("manifest.json"), manifest);
        JsonNode cases;
        try (var stream = getClass().getResourceAsStream("/scenarios/reformulation-model-comparison.json")) { cases = json.readTree(stream); }
        assertThat(cases.path("cases")).hasSize(5);
        var reports = manifest.putArray("cases");
        var failed = new ArrayList<String>();
        providers.setRequestProvider(provider);
        try {
            for (var testCase : cases.path("cases")) {
                var baseline = baseline(testCase);
                Path directory = output.resolve(testCase.path("id").asText()); Files.createDirectories(directory);
                write(directory.resolve("input.json"), json.valueToTree(baseline));
                var pair = reports.addObject().put("id", testCase.path("id").asText())
                        .put("sourceSha256", baseline.originalTextHash())
                        .put("contextSha256", StableIdentityHash.sha256(json.writeValueAsString(baseline.frozenContext())))
                        .put("provider", provider.name()).put("model", model).put("humanQualityGate", "NOT_REVIEWED");
                for (String variant : List.of("walk-up", "single-complete-prompt")) {
                    Supplier<Object> operation = variant.equals("walk-up")
                            ? () -> reconciliation.reconcile(baseline, walkUp.synthesize(baseline, List.of(), List.of()), List.of(), List.of())
                            : () -> nodes.synthesize(completeInput(baseline));
                    var result = measure(operation, testCase);
                    write(directory.resolve(variant + ".json"), result);
                    pair.set(variant, result);
                    if (!result.path("schemaOutcome").asText().equals("VALIDATED")) failed.add(testCase.path("id").asText() + ":" + variant);
                    write(output.resolve("manifest.json"), manifest);
                }
                write(directory.resolve("human-review.json"), reviewTemplate(testCase));
            }
        } finally {
            providers.clearRequestProvider();
            manifest.put("status", failed.isEmpty() ? "OUTPUTS_REQUIRE_HUMAN_REVIEW" : "EXECUTION_FAILURES");
            write(output.resolve("manifest.json"), manifest);
        }
        // This is a schema/execution assertion, deliberately not a language-quality verdict.
        assertThat(failed).as("Provider/schema failures; review recorded outputs and actual transport observations").isEmpty();
    }

    private ObjectNode measure(Supplier<Object> operation, JsonNode testCase) {
        var observations = new java.util.concurrent.CopyOnWriteArrayList<LlmTransportMeter.Observation>();
        var result = json.createObjectNode().put("humanQualityGate", "NOT_REVIEWED");
        long started = System.nanoTime();
        try (var meter = LlmTransportMeter.open(observations::add)) {
            Object value = operation.get();
            result.set("output", json.valueToTree(value));
            result.put("schemaOutcome", "VALIDATED");
            String wording = value instanceof ReformulationDocument doc
                    ? String.join("\n", doc.sections().stream().map(Section::summary).toList()) + "\n"
                        + String.join("\n", doc.statements().stream().filter(s -> !s.provenance().isSource()).map(Statement::wording).toList())
                    : ((NodeSynthesisResult) value).summary() + "\n"
                        + String.join("\n", ((NodeSynthesisResult) value).statementProposals().stream().map(Statement::wording).toList());
            var literal = result.putObject("literalPresenceDiagnosticOnly");
            testCase.path("literalConstraints").forEach(c -> literal.put(c.asText(), wording.toLowerCase(Locale.ROOT).contains(c.asText().toLowerCase(Locale.ROOT))));
        } catch (RuntimeException failure) {
            result.put("schemaOutcome", "FAILED").put("failureType", failure.getClass().getSimpleName());
        } finally {
            result.put("durationMillis", (System.nanoTime() - started) / 1_000_000);
            result.set("transportAttempts", json.valueToTree(observations));
            result.put("actualHttpAttempts", observations.stream().filter(o -> o.source() == LlmTransportMeter.Source.HTTP).count());
            result.put("actualTransportRetries", observations.stream().filter(o -> o.retryIndex() > 0).count());
            if (observations.isEmpty() || observations.stream().anyMatch(o -> o.source() != LlmTransportMeter.Source.HTTP))
                result.put("schemaOutcome", "NOT_REAL_HTTP_EVIDENCE");
        }
        return result;
    }
    private ReformulationBaseline baseline(JsonNode testCase) {
        String source = testCase.path("source").asText();
        var tree = prune(json.valueToTree(catalogue.getFullTree()));
        var found = new HashSet<String>(); collect(tree, found); assertThat(found).isEqualTo(FRONT);
        var scores = new TreeMap<String, Integer>(); FRONT.forEach(id -> scores.put(id, 100));
        var frozen = new TreeMap<String, String>();
        frozen.put("catalogue", tree.toString()); frozen.put("elementMappings", "[]"); frozen.put("relationMappings", "[]");
        frozen.put("reformulationPrompt", ReformulationPromptBuilder.interactiveTemplate());
        frozen.put("reformulationPromptVersion", ReformulationPromptBuilder.INTERACTIVE_PROMPT_VERSION);
        frozen.put("reformulationSchemaVersion", ReformulationPromptBuilder.INTERACTIVE_SCHEMA_VERSION);
        frozen.putAll(ReconcilePromptBuilder.freeze(Map.of()));
        return new ReformulationBaseline(new ReformulationBaseline.Scope("authored-evaluation", "authored-evaluation", "main", 1, 1),
                1, source, StableIdentityHash.sha256(source), "evaluation-" + testCase.path("id").asText(),
                json.writeValueAsString(Map.of("scores", scores)), frozen, "en", "controlled-model-comparison-v1");
    }
    private NodeSynthesisInput completeInput(ReformulationBaseline baseline) {
        var spans = List.of(new Statement.SourceSpan(0, baseline.originalText().length(), baseline.originalText()));
        var original = new Statement("evaluation-original", baseline.originalText(), spans, Statement.Provenance.ORIGINAL,
                List.of(), List.of(), null, Statement.EditingOrigin.SOURCE, "UNREVIEWED");
        String context = json.writeValueAsString(Map.of("catalogue", json.readTree(baseline.frozenContext().get("catalogue")),
                "scores", json.readTree(baseline.snapshotPayload()).path("scores"), "mappings", List.of(), "boundaryEdges", Map.of()));
        return new NodeSynthesisInput(baseline, "@document", null, context, spans, List.of(original), List.of(), Map.of(),
                List.of(), List.of(), "Preserve all original anchors and child IDs verbatim; additions are unreviewed.");
    }
    private ArrayNode prune(JsonNode tree) {
        var kept = json.createArrayNode();
        for (var node : tree) if (FRONT.contains(node.path("code").asText())) {
            var copy = (ObjectNode) node.deepCopy(); copy.set("children", prune(node.path("children"))); kept.add(copy);
        }
        return kept;
    }
    private void collect(JsonNode tree, Set<String> ids) { tree.forEach(n -> { ids.add(n.path("code").asText()); collect(n.path("children"), ids); }); }
    private ObjectNode reviewTemplate(JsonNode testCase) {
        var review = json.createObjectNode().put("case", testCase.path("id").asText()).put("status", "NOT_REVIEWED");
        for (String criterion : List.of("sourceConditionLoss", "unmarkedAdditions", "concreteQuestionUsefulness", "duplicatesAndConflicts", "readability")) {
            var entry = review.putObject(criterion); entry.putNull("walkUpScore0to4"); entry.putNull("singlePromptScore0to4");
            entry.put("evidence", "");
        }
        review.put("reviewer", "").put("reviewedAt", ""); return review;
    }
    private void write(Path file, JsonNode value) throws Exception { Files.writeString(file, value.toPrettyString()); }
}
