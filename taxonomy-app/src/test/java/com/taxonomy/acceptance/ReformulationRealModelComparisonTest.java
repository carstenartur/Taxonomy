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
        JsonNode cases;
        try (var stream = getClass().getResourceAsStream("/scenarios/reformulation-model-comparison.json")) { cases = json.readTree(stream); }
        var run = ReformulationComparisonEvidence.create(Path.of("target/reformulation-model-comparison"), json,
                git("rev-parse", "HEAD"), cases);
        var provider = providers.getActiveProvider();
        if (provider == LlmProvider.LOCAL_ONNX || providers.isMockMode() || !providers.isProviderConfigured(provider)
                || providers.getProviderConfigurationError(provider) != null) {
            run.notRun("No configured generative provider available");
            Assumptions.assumeTrue(false, "NOT RUN: configure a real generative provider to evaluate model quality; evidence: " + run.directory());
        }
        String model = provider == LlmProvider.GEMINI
                ? providers.getGeminiUrl().replaceFirst(".*models/([^:/?]+).*", "$1")
                : providers.getOpenAiCompatibleModel(provider);
        var failed = new ArrayList<String>();
        String status;
        try {
            run.start(provider.name(), model);
            // A commit hash must identify the source actually evaluated, rather than an
            // uncommitted implementation with the same HEAD. Generated target evidence is ignored.
            assertThat(git("status", "--porcelain")).as("Live evaluation requires a committed source tree").isEmpty();
            providers.setRequestProvider(provider);
            for (var testCase : cases.path("cases")) {
                String id = testCase.path("id").asText();
                run.beginCase(id); // Persist before catalogue/baseline work, which can throw.
                var baseline = baseline(testCase);
                run.prepareCase(id, json.valueToTree(baseline));
                for (String variant : List.of("walk-up", "single-complete-prompt")) {
                    Supplier<Object> operation = variant.equals("walk-up")
                            ? () -> reconciliation.reconcile(baseline, walkUp.synthesize(baseline, List.of(), List.of()), List.of(), List.of())
                            : () -> nodes.synthesize(completeInput(baseline));
                    var result = measure(operation, testCase);
                    run.recordVariant(id, variant, result);
                    if (!result.path("schemaOutcome").asText().equals("VALIDATED")) failed.add(id + ":" + variant);
                }
                run.completeCase(id);
            }
            status = run.finish();
        } catch (Exception | AssertionError failure) {
            try { run.abort(failure); } catch (Exception recordingFailure) { failure.addSuppressed(recordingFailure); }
            throw failure;
        } finally { providers.clearRequestProvider(); }
        assertThat(status).as("Complete real-HTTP output evidence: " + run.directory()).isEqualTo("OUTPUTS_REQUIRE_HUMAN_REVIEW");
        // This is a schema/execution assertion, deliberately not a language-quality verdict.
        assertThat(failed).as("Provider/schema failures; review recorded outputs and actual transport observations: " + run.directory()).isEmpty();
    }

    private static String git(String... arguments) throws Exception {
        var command = new ArrayList<String>(); command.add("git"); command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String value = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) throw new IllegalStateException("Cannot establish evaluation source provenance");
        return value;
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
        var scores = new TreeMap<String, Double>(); FRONT.forEach(id -> scores.put(id, 100d));
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
}
