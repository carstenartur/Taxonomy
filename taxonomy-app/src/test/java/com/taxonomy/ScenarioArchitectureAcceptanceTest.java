package com.taxonomy;

import com.taxonomy.acceptance.ScenarioLlmConfiguration;
import com.taxonomy.acceptance.ScenarioLlmPlayback;
import com.taxonomy.acceptance.ScenarioExportQa;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.export.service.ExportFormatExtensionRegistry;
import com.taxonomy.export.spi.ExportContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, authentication, catalogue, jobs, persistence and exports; only LLM HTTP is replaced. */
@Tag("ci-app-shard-browser")
class ScenarioArchitectureAcceptanceTest {
    // Build the Spring fixture only in the child JVM, not in the long-lived reactor
    // test process. The resource guard still observes real, unmodified heap samples.
    @SpringBootTest(classes = TaxonomyApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "taxonomy.features.multi-repository-api.enabled=true",
            "embedding.enabled=false", "embedding.allow-download=false", "llm.mock=false",
            "llm.provider=CUSTOM_OPENAI", "custom.llm.url=" + ScenarioLlmConfiguration.URL,
            "custom.llm.model=scenario-fixture", "taxonomy.admin-password=Scenario-Acceptance-2026!",
            "taxonomy.security.require-password-change=false", "taxonomy.ai.copilot.verification-passes=2"
    })
    @Import({ScenarioLlmConfiguration.class, com.taxonomy.interop.publication.PublicationScenarioConfiguration.class})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    static class Scenario extends ScenarioArchitectureAcceptanceTest { }

    static final String PASSWORD = "Scenario-Acceptance-2026!";
    @LocalServerPort int port;
    @Autowired ScenarioLlmPlayback playback;
    @Autowired com.taxonomy.interop.publication.PublicationContractProvider publicationProvider;
    @Autowired ExportFormatExtensionRegistry exportFormats;
    @Autowired com.taxonomy.portfolio.report.SnapshotWordReportService snapshotWords;
    @Autowired com.taxonomy.preferences.PreferencesService preferences;
    @Autowired com.taxonomy.catalog.repository.TaxonomyNodeRepository catalogueRepository;
    @Autowired com.taxonomy.catalog.service.TaxonomyService catalogueService;
    @Autowired com.taxonomy.catalog.service.CatalogueOverlayService catalogueOverlay;
    @Autowired com.taxonomy.relations.repository.RelationProposalRepository proposalRepository;
    @Autowired com.taxonomy.export.DiagramProjectionService diagramProjection;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.taxonomy.architecture.service.ArchitectureReportService liveReports;
    final ObjectMapper json = new ObjectMapper();
    final CookieManager sessionCookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    final HttpClient http = HttpClient.newBuilder().cookieHandler(sessionCookies)
            .connectTimeout(Duration.ofSeconds(10)).build();
    final ScenarioHttpSession httpSession = new ScenarioHttpSession(http, sessionCookies, json, "admin", PASSWORD);
    final Path output = Path.of("target/scenario-acceptance");
    final Map<String, com.taxonomy.acceptance.ScenarioPromptEvidenceQuality.Cycle> observedAnalysisCycles = new LinkedHashMap<>();

    @Test void generatesAndReopensScenarioArchitectureThroughTheRealCopilot() throws Exception {
        ScenarioAcceptanceProcess.verify();
    }

    void verifyScenario() throws Exception {
        Files.createDirectories(output);
        JsonNode fixture = playback.fixture();
        JsonNode catalogue = get("/api/taxonomy");
        save("prompt-catalogue.json", catalogue);
        var names = new LinkedHashMap<String, String>();
        catalogueNames(catalogue, names);
        var overlayParents = new TreeSet<String>();
        names.keySet().stream().filter(catalogueOverlay::hasParentPatch).forEach(overlayParents::add);
        for (var binding : fixture.get("bindings").properties()) {
            assertThat(names).containsEntry(binding.getKey(), binding.getValue().asText());
        }
        try (var browser = Boolean.getBoolean("generateScreenshots")
                ? new ScenarioBrowserWalkthrough(port, output) : null) {
            Run run = browser == null ? startThroughHttp(fixture) : browser.start(fixture);
            long projectId = run.projectId();
            long requirementId = run.requirementId();
            JsonNode requirement = fixture.get("requirement");
            String requirementPath = "/api/projects/" + projectId + "/requirements/" + requirementId;
            String operationPath = "/api/projects/" + projectId + "/copilot-operations/" + run.operationId();
            JsonNode operation = ScenarioContinuationWalkthrough.finishIndependentBudgetWork(this, projectId, operationPath, browser);
            save("operation.json", operation);
            save("llm-calls.json", json.valueToTree(playback.calls()));
            save("playback-coverage.json", json.valueToTree(playback.relationCoverage()));
            // Keep the actual evidence even when a quality gate rejects a partial run.
            String snapshotId = operation.path("selectedSnapshotId").asText();
            assertThat(snapshotId).isNotBlank();
            String snapshotPath = "/api/projects/" + projectId + "/snapshots/" + snapshotId;
            JsonNode snapshot = get(snapshotPath);
            save("snapshot.json", snapshot);
            saveRun(fixture, projectId, requirementId, snapshotId);
            assertThat(playback.failures()).isEmpty();
            // The independent reference retains one genuine, non-core F5 input-identity
            // question. Its exact scope is checked below; it must not become false success.
            assertThat(operation.path("status").asText()).as(operation.toPrettyString()).isEqualTo("PARTIAL");
            assertThat(operation.path("completedPasses").asInt()).isEqualTo(2);
            playback.verifyCoverage(2);
            playback.verifyRelationCoverage(2);
            assertThat(get(snapshotPath)).isEqualTo(snapshot);
            assertThat(get(requirementPath).path("currentAnalysisSnapshotId").asText()).isEqualTo(snapshotId);
            JsonNode analysis = snapshot.path("analysis");
            assertThat(analysis.path("status").asText()).isEqualTo("PARTIAL");
            assertThat(analysis.at("/recovery/state").asText()).isEqualTo("COMPLETED_WITH_GAPS");
            assertThat(operation.path("provider").asText()).isEqualTo("CUSTOM_OPENAI");
            assertThat(analysis.path("provider").asText()).isEqualTo("Custom OpenAI-compatible");
            // The independent relation gate below checks the exact permitted F5
            // identity warnings; all other warnings remain acceptance failures.
            verifyIndependentPasses(projectId, operation, analysis);
            JsonNode continuationHistory = json.readTree(Files.readString(output.resolve("continuation-history.json")));
            assertThat(continuationHistory.isArray()).isTrue();
            assertThat(observedAnalysisCycles).as("Both initial snapshots and every explicit continuation were budget-checked")
                    .hasSize(2 + continuationHistory.size());
            save("prompt-evidence-quality.json", json.valueToTree(
                    com.taxonomy.acceptance.ScenarioPromptEvidenceQuality.verifyObservedCycles(catalogue, overlayParents,
                            requirement.path("text").asText(), playback.calls(), observedAnalysisCycles.values())));
            save("classification-quality.json", json.valueToTree(
                    com.taxonomy.acceptance.ScenarioScoringQuality.verify(fixture, catalogue, analysis)));
            String workbenchPath = "/api/projects/" + projectId + "/architecture-workbench/" + snapshotId;
            JsonNode projection = get(workbenchPath);
            save("architecture.json", projection);
            assertThat(projection.path("snapshotId").asText()).isEqualTo(snapshotId);
            assertThat(projection.path("requirementText")).isEqualTo(requirement.get("text"));
            JsonNode projectedElements = projection.path("elements");
            assertThat(projectedElements.isEmpty()).isFalse();
            assertThat(projectedElements.propertyNames()).doesNotContain("BP", "BR", "CI", "CO", "CP", "CR", "IP", "UA");
            for (var element : projectedElements.properties()) {
                assertThat(analysis.path("scores").has(element.getKey()))
                        .as("Projected element %s must come from retained assessed evidence", element.getKey())
                        .isTrue();
                assertThat(element.getValue().path("presenceReason").asText()).isNotBlank();
            }
            // This reference is maintained separately from stored provider replies. It also
            // rejects a superficially successful run with sources or searches left unassessed.
            com.taxonomy.acceptance.ScenarioRelationQuality.verify(output);
            assertThat(projection.path("relations").isEmpty()).isFalse();
            for (var relation : projection.path("relations").properties()) {
                var value = relation.getValue();
                assertThat(projection.path("elements").has(value.path("sourceCode").asText())).isTrue();
                assertThat(projection.path("elements").has(value.path("targetCode").asText())).isTrue();
                assertThat(value.path("sourceCode")).isNotEqualTo(value.path("targetCode"));
                assertThat(value.path("relationType").asText()).isNotBlank();
            }
            for (JsonNode relation : analysis.at("/architectureView/includedRelationships")) {
                assertThat(relation.path("origin").asText()).isEqualTo("LLM_SUPPORTED");
                assertThat(relation.path("requirementEvidence").isEmpty()).isFalse();
            }
            JsonNode evidence = analysis.path("relationSearchReport");
            assertThat(evidence.path("warnings").isEmpty()).isTrue();
            assertThat(evidence.path("progress").path("pendingSearches").asInt()).isZero();
            assertThat(evidence.path("result").path("edges")).anySatisfy(edge -> {
                assertThat(edge.path("evidence").path("necessity").asText()).isEqualTo("OPTIONAL");
                assertThat(edge.path("evidence").path("condition").asText()).contains("oversight");
            });
            var artifacts = new LinkedHashMap<String, byte[]>();
            var graphHashes = new HashSet<String>();
            for (String format : List.of("svg", "pdf", "archimate.xml", "archimate.zip", "vsdx", "visio.zip")) {
                var response = request("GET", workbenchPath + "." + format, null, 200);
                String file = "architecture." + format;
                artifacts.put(file, response.body());
                Files.write(output.resolve(file), response.body());
                if (!Set.of("svg", "pdf").contains(format)) {
                    assertThat(response.headers().firstValue("X-Taxonomy-Snapshot-Id")).contains(snapshotId);
                    graphHashes.add(response.headers().firstValue("X-Taxonomy-Canonical-Graph-Sha256").orElseThrow());
                    assertThat(response.headers().firstValue("X-Taxonomy-Artifact-Sha256"))
                            .contains(ScenarioExportQa.sha256(response.body()));
                }
            }
            assertThat(graphHashes).hasSize(1);
            var wordHashes = new HashSet<String>();
            for (String format : List.of("html", "docx", "json")) {
                var response = request("GET", snapshotPath + "/decision-report/" + format + "?language=en", null, 200);
                if (format.equals("docx")) {
                    assertThat(response.headers().firstValue("X-Taxonomy-Snapshot-Id")).contains(snapshotId);
                    wordHashes.add(response.headers().firstValue("X-Taxonomy-Graph-SHA256").orElseThrow());
                }
                String file = "decision." + format;
                artifacts.put(file, response.body());
                Files.write(output.resolve(file), response.body());
            }
            // Exercise every registered diagram adapter with the same real generated graph.
            // These SPI calls avoid the legacy endpoints' deliberate re-analysis of a requirement.
            var diagram = json.treeToValue(projection.get("diagram"), DiagramModel.class);
            assertThat(exportFormats.listDescriptors()).extracting(descriptor -> descriptor.id())
                    .containsExactlyInAnyOrder("archimate", "visio", "svg", "mermaid", "structurizr", "sparx");
            for (var descriptor : exportFormats.listDescriptors()) {
                byte[] bytes = exportFormats.getRequired(descriptor.id()).export(ExportContext.of(diagram)).bytes();
                String file = "adapter-" + descriptor.id() + "." + descriptor.fileExtension();
                artifacts.put(file, bytes);
                Files.write(output.resolve(file), bytes);
            }
            // Older report endpoints also consume the scores produced by this run.
            for (String format : List.of("markdown", "html", "json")) {
                var response = request("POST", "/api/report/" + format, Map.of("scores", analysis.get("scores"),
                        "businessText", requirement.get("text").asText(), "minScore", 50), 200);
                String file = "report." + format;
                artifacts.put(file, response.body());
                Files.write(output.resolve(file), response.body());
            }
            var wordResponse = request("GET", snapshotPath + "/architecture-report/docx?language=en", null, 200);
            assertThat(wordResponse.headers().firstValue("X-Taxonomy-Snapshot-Id")).contains(snapshotId);
            wordHashes.add(wordResponse.headers().firstValue("X-Taxonomy-Graph-SHA256").orElseThrow());
            assertThat(wordHashes).hasSize(1);
            artifacts.put("report.docx", wordResponse.body());
            Files.write(output.resolve("report.docx"), wordResponse.body());
            save("quality.json", json.valueToTree(ScenarioExportQa.verify(projection, artifacts, output,
                    Boolean.getBoolean("generateScreenshots"))));
            ScenarioIntegrationWalkthrough.verify(this, snapshot, projection, artifacts.get("architecture.archimate.xml"));
            // Export/reopen must not perform another analysis, even when a provider is configured.
            playback.verifyCoverage(2);
            playback.verifyRelationCoverage(2);
            ScenarioPublicationWalkthrough.prepare(this, publicationProvider);
            if (browser != null) browser.inspect(projectId, requirementId, snapshotId, projection, artifacts, this);
            playback.verifyCoverage(2);
            playback.verifyRelationCoverage(2);
            assertFrozenWordSourceSurvivesLiveStateMutations(projectId,snapshotId,projection);
            saveRun(fixture, projectId, requirementId, snapshotId);
        }
    }

    void verifyIndependentPasses(long projectId, JsonNode operation, JsonNode analysis) throws Exception {
        for (JsonNode job : operation.path("jobs")) {
            String passSnapshotId = job.path("items").get(0).path("snapshotId").asText();
            JsonNode passSnapshot = get("/api/projects/" + projectId + "/snapshots/" + passSnapshotId);
            com.taxonomy.acceptance.ScenarioArchitectureQuality.verifyPassCompletion(passSnapshot);
            JsonNode pass = passSnapshot.path("analysis");
            for (String field : List.of("scores", "reasons")) {
                assertThat(pass.path(field)).as("Stable %s across verification passes", field).isEqualTo(analysis.path(field));
            }
            assertThat(semanticArchitecture(pass.path("architectureView")))
                    .isEqualTo(semanticArchitecture(analysis.path("architectureView")));
        }
    }

    private void saveRun(JsonNode fixture, long projectId, long requirementId, String snapshotId) throws Exception {
        save("playback-coverage.json", json.valueToTree(playback.relationCoverage()));
        save("run.json", json.valueToTree(Map.of("scenario", fixture.path("id").asText(),
                "fixtureSha256", ScenarioLlmPlayback.sha256(fixture.toString()),
                "projectId", projectId, "requirementId", requirementId, "snapshotId", snapshotId,
                "llmCalls", playback.calls().size(), "mockBoundary", "remote LLM HTTP response only",
                "sourceRevision", System.getenv().getOrDefault("GITHUB_SHA", "local-working-tree"),
                "ciRun", System.getenv().getOrDefault("GITHUB_RUN_ID", "local"))));
    }

    private static JsonNode semanticArchitecture(JsonNode architecture) {
        JsonNode copy = architecture.deepCopy();
        for (String path : List.of("/relationSearchReport", "/relationSearchReport/result")) {
            JsonNode report = copy.at(path);
            assertThat(report.path("durationMillis").isIntegralNumber()).isTrue();
            assertThat(report.path("durationMillis").asLong()).isGreaterThanOrEqualTo(0);
            ((tools.jackson.databind.node.ObjectNode) report).remove("durationMillis");
        }
        return copy;
    }

    private void assertFrozenWordSourceSurvivesLiveStateMutations(long projectId,String snapshotId,JsonNode projection) throws Exception {
        var context=new com.taxonomy.workspace.service.WorkspaceContext("admin",projection.path("workspaceId").asText(null),projection.path("branchName").asText(),projection.path("exportProvenance").path("repositoryId").asText());
        var before=snapshotWords.load(projectId,snapshotId,"admin",context,Locale.ENGLISH);
        var node=catalogueRepository.findByCode("BP-1017").orElseThrow();
        var target=catalogueRepository.findByCode("CP-1041").orElseThrow();
        String oldName=node.getNameEn(),oldDescription=node.getDescriptionEn();
        Map<String,Object> oldPreferences=new LinkedHashMap<>();
        for(String key:List.of("diagram.policy","analysis.min-relevance-score","limits.max-architecture-nodes"))oldPreferences.put(key,preferences.get(key));
        var proposal=new com.taxonomy.relations.model.RelationProposal();
        proposal.setRepositoryId(context.repositoryId());proposal.setWorkspaceId(context.workspaceId());proposal.setOwnerUsername("admin");
        proposal.setSourceNode(node);proposal.setTargetNode(target);proposal.setRelationType(com.taxonomy.model.RelationType.DEPENDS_ON);
        proposal.setConfidence(.99);proposal.setRationale("Live proposal added after frozen analysis");
        int callsBefore=playback.calls().size();
        var originalPolicy = diagramProjection.getPolicy();
        org.mockito.Mockito.clearInvocations(liveReports);
        try {
            node.setNameEn("MUTATED LIVE CATALOGUE TITLE");node.setDescriptionEn("Mutated live description after saving snapshot");
            catalogueRepository.saveAndFlush(node);
            preferences.update(Map.of("diagram.policy","leafOnly","analysis.min-relevance-score",99,"limits.max-architecture-nodes",1),"word-regression");
            proposal=proposalRepository.saveAndFlush(proposal);
            assertThat(catalogueService.getNodeByCode("BP-1017").getNameEn()).isEqualTo("MUTATED LIVE CATALOGUE TITLE");
            assertThat(preferences.get("diagram.policy")).isEqualTo("leafOnly");
            var restricted = new com.taxonomy.export.ConfigurableDiagramSelectionPolicy(
                    new com.taxonomy.export.DiagramSelectionConfig(true, true, true, true, false, true, 0, 1, 1));
            diagramProjection.setPolicy(restricted);
            assertThat(diagramProjection.getPolicy().apply(before.architecture().diagram()).edges()).hasSizeLessThan(before.architecture().diagram().edges().size());
            assertThat(proposalRepository.findVisibleByRepositoryAndWorkspace(context.repositoryId(),context.workspaceId())).extracting(com.taxonomy.relations.model.RelationProposal::getId).contains(proposal.getId());

            // Both real services deserialize the persisted analysis/version and project the saved view again.
            var after=snapshotWords.load(projectId,snapshotId,"admin",context,Locale.ENGLISH);
            assertThat(after.architecture()).isEqualTo(before.architecture());
            assertThat(after.decision().architecture()).isEqualTo(before.architecture());
            assertThat(after.decision().requirement()).isEqualTo(before.decision().requirement());
            assertThat(after.decision().chapters()).isEqualTo(before.decision().chapters());
            assertThat(after.architecture().evidence().snapshotId()).isEqualTo(snapshotId);
            assertThat(after.architecture().evidence().requirementVersionId()).isEqualTo(before.architecture().evidence().requirementVersionId());
            assertThat(after.architecture().evidence().commit()).isEqualTo(before.architecture().evidence().commit());
            assertThat(after.architecture().elements()).isEqualTo(before.architecture().elements());
            assertThat(after.architecture().relations()).isEqualTo(before.architecture().relations());
            org.mockito.Mockito.verifyNoInteractions(liveReports);
            assertThat(playback.calls()).hasSize(callsBefore);
            save("frozen-word-mutation.json",json.valueToTree(Map.of("snapshotId",snapshotId,"graphSha256",after.architecture().evidence().graphSha256(),"catalogueChanged",true,"selectionPolicyChanged",true,"pendingProposalAdded",true,"frozenSourceUnchanged",true,"additionalLlmCalls",0)));
        } finally {
            node.setNameEn(oldName);node.setDescriptionEn(oldDescription);catalogueRepository.saveAndFlush(node);
            preferences.update(oldPreferences,"word-regression-restore");
            diagramProjection.setPolicy(originalPolicy);
            if(proposal.getId()!=null)proposalRepository.deleteById(proposal.getId());
        }
    }

    Run startThroughHttp(JsonNode fixture) throws Exception {
        String projectKey = "CIV-FLOOD-" + UUID.randomUUID().toString().substring(0, 8);
        long projectId = post("/api/projects", Map.of("projectKey", projectKey,
                "title", "Flood information", "description", "Source-backed deterministic acceptance scenario",
                "status", "ACTIVE"), 201).path("id").asLong();
        var requirement = fixture.get("requirement");
        long requirementId = post("/api/projects/" + projectId + "/requirements", Map.of(
                "requirementKey", requirement.get("key").asText(), "title", requirement.get("title").asText(),
                "text", requirement.get("text").asText(), "status", "DRAFT", "priority", 80,
                "criticality", "HIGH", "requirementType", "FUNCTIONAL", "reviewStatus", "PROPOSED"), 201)
                .path("id").asLong();
        String requirementPath = "/api/projects/" + projectId + "/requirements/" + requirementId;
        JsonNode operation = post(requirementPath + "/copilot", Map.of(
                "provider", "CUSTOM_OPENAI", "profile", "EXHAUSTIVE", "verificationPasses", 2,
                "maxArchitectureNodes", 50, "proposeSolutions", true, "proposeProducts", true), 202);
        return new Run(projectId, requirementId, operation.path("operationId").asText());
    }
    record Run(long projectId, long requirementId, String operationId) { }

    JsonNode get(String path) throws Exception {
        JsonNode value = json.readTree(request("GET", path, null, 200).body());
        if (path.matches("/api/projects/[0-9]+/snapshots/[^/?]+")) {
            var checked = com.taxonomy.acceptance.ScenarioPromptEvidenceQuality.verifyCycle(value);
            var previous = observedAnalysisCycles.putIfAbsent(checked.snapshotId(), checked);
            if (previous != null) assertThat(checked).as("Repeated snapshot reads preserve the checked immutable source").isEqualTo(previous);
        }
        return value;
    }
    JsonNode post(String path, Object body, int status) throws Exception {
        return json.readTree(request("POST", path, body, status).body());
    }
    HttpResponse<byte[]> request(String method, String path, Object body, int status) throws Exception {
        var builder = authenticatedRequest(method, path)
                .header("Content-Type", "application/json");
        var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as(() -> {
            String excerpt = new String(response.body(), StandardCharsets.UTF_8);
            return "%s %s: %s".formatted(method, path, excerpt.substring(0, Math.min(excerpt.length(), 400)));
        }).isEqualTo(status);
        return response;
    }
    HttpRequest.Builder authenticatedRequest(String method, String path) throws Exception {
        return httpSession.request(port, method, path);
    }
    void save(String file, JsonNode value) throws Exception {
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve(file).toFile(), value);
    }
    static void catalogueNames(JsonNode nodes, Map<String, String> names) {
        for (JsonNode node : nodes) {
            names.put(node.path("code").asText(), node.path("name").asText());
            catalogueNames(node.path("children"), names);
        }
    }
}
