package com.taxonomy;

import com.taxonomy.acceptance.CivilianLlmConfiguration;
import com.taxonomy.acceptance.ScenarioLlmPlayback;
import com.taxonomy.acceptance.CivilianExportQa;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.export.service.ExportFormatExtensionRegistry;
import com.taxonomy.export.spi.ExportContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
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
class CivilianArchitectureAcceptanceTest {
    // Build the Spring fixture only in the child JVM, not in the long-lived reactor
    // test process. The resource guard still observes real, unmodified heap samples.
    @SpringBootTest(classes = TaxonomyApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "taxonomy.features.multi-repository-api.enabled=true",
            "embedding.enabled=false", "embedding.allow-download=false", "llm.mock=false",
            "llm.provider=CUSTOM_OPENAI", "custom.llm.url=" + CivilianLlmConfiguration.URL,
            "custom.llm.model=civilian-fixture", "taxonomy.admin-password=Civilian-Acceptance-2026!",
            "taxonomy.security.require-password-change=false", "taxonomy.ai.copilot.verification-passes=2"
    })
    @Import({CivilianLlmConfiguration.class, com.taxonomy.interop.publication.PublicationCivilianConfiguration.class})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    static class Scenario extends CivilianArchitectureAcceptanceTest { }

    static final String PASSWORD = "Civilian-Acceptance-2026!";
    @LocalServerPort int port;
    @Autowired ScenarioLlmPlayback playback;
    @Autowired com.taxonomy.interop.publication.PublicationContractProvider publicationProvider;
    @Autowired ExportFormatExtensionRegistry exportFormats;
    @Autowired com.taxonomy.portfolio.report.SnapshotWordReportService snapshotWords;
    @Autowired com.taxonomy.preferences.PreferencesService preferences;
    @Autowired com.taxonomy.catalog.repository.TaxonomyNodeRepository catalogueRepository;
    @Autowired com.taxonomy.catalog.service.TaxonomyService catalogueService;
    @Autowired com.taxonomy.relations.repository.RelationProposalRepository proposalRepository;
    @Autowired com.taxonomy.export.DiagramProjectionService diagramProjection;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.taxonomy.architecture.service.ArchitectureReportService liveReports;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    final Path output = Path.of("target/civilian-acceptance");

    @Test void generatesAndReopensCivilianArchitectureThroughTheRealCopilot() throws Exception {
        CivilianAcceptanceProcess.verify();
    }

    void verifyScenario() throws Exception {
        Files.createDirectories(output);
        JsonNode fixture = playback.fixture();
        JsonNode catalogue = get("/api/taxonomy");
        var names = new LinkedHashMap<String, String>();
        catalogueNames(catalogue, names);
        for (var binding : fixture.get("bindings").properties()) {
            assertThat(names).containsEntry(binding.getKey(), binding.getValue().asText());
        }
        try (var browser = Boolean.getBoolean("generateScreenshots")
                ? new CivilianBrowserWalkthrough(port, output) : null) {
            Run run = browser == null ? startThroughHttp(fixture) : browser.start(fixture);
            long projectId = run.projectId();
            long requirementId = run.requirementId();
            JsonNode requirement = fixture.get("requirement");
            String requirementPath = "/api/projects/" + projectId + "/requirements/" + requirementId;
            JsonNode operation;
            String operationPath = "/api/projects/" + projectId + "/copilot-operations/" + run.operationId();
            long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            do {
                // Real status clients race with the background finalizer. Every reader
                // must succeed; retrying a 500 here would hide a persistence defect.
                var readers = java.util.stream.IntStream.range(0, 3).mapToObj(index ->
                        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            try { return get(operationPath); }
                            catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                        })).toList();
                operation = null;
                for (var reader : readers) operation = reader.join();
                if (Set.of("SUCCESS", "PARTIAL", "FAILED", "CANCELLED").contains(operation.path("status").asText())) break;
                Thread.sleep(200);
            } while (System.nanoTime() < deadline);
            save("operation.json", operation);
            save("llm-calls.json", json.valueToTree(playback.calls()));
            assertThat(playback.failures()).isEmpty();
            assertThat(operation.path("status").asText()).as(operation.toPrettyString()).isEqualTo("SUCCESS");
            assertThat(operation.path("completedPasses").asInt()).isEqualTo(2);
            playback.verifyCoverage(2);
            String snapshotId = operation.path("selectedSnapshotId").asText();
            assertThat(snapshotId).isNotBlank();
            String snapshotPath = "/api/projects/" + projectId + "/snapshots/" + snapshotId;
            JsonNode snapshot = get(snapshotPath);
            save("snapshot.json", snapshot);
            assertThat(get(snapshotPath)).isEqualTo(snapshot);
            assertThat(get(requirementPath).path("currentAnalysisSnapshotId").asText()).isEqualTo(snapshotId);
            JsonNode analysis = snapshot.path("analysis");
            assertThat(analysis.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(operation.path("provider").asText()).isEqualTo("CUSTOM_OPENAI");
            assertThat(analysis.path("provider").asText()).isEqualTo("Custom OpenAI-compatible");
            assertThat(analysis.path("warnings").isEmpty()).isTrue();
            for (JsonNode job : operation.path("jobs")) {
                String passSnapshotId = job.path("items").get(0).path("snapshotId").asText();
                JsonNode pass = get("/api/projects/" + projectId + "/snapshots/" + passSnapshotId).path("analysis");
                for (String field : List.of("scores", "reasons", "architectureView")) {
                    assertThat(pass.path(field)).as("Stable %s across verification passes", field).isEqualTo(analysis.path(field));
                }
            }
            for (JsonNode leaf : fixture.get("expectedLeaves")) {
                assertThat(analysis.path("scores").path(leaf.asText()).asInt()).isEqualTo(100);
                assertThat(analysis.path("reasons").path(leaf.asText()).asText()).matches("[FA]\\d: .+");
            }
            String workbenchPath = "/api/projects/" + projectId + "/architecture-workbench/" + snapshotId;
            JsonNode projection = get(workbenchPath);
            save("architecture.json", projection);
            assertThat(projection.path("snapshotId").asText()).isEqualTo(snapshotId);
            assertThat(projection.path("requirementText")).isEqualTo(requirement.get("text"));
            assertThat(projection.path("elements").size()).isBetween(8, 50);
            assertThat(projection.path("relations").size()).isPositive();
            for (var relation : projection.path("relations").properties()) {
                var value = relation.getValue();
                assertThat(projection.path("elements").has(value.path("sourceCode").asText())).isTrue();
                assertThat(projection.path("elements").has(value.path("targetCode").asText())).isTrue();
                assertThat(value.path("sourceCode")).isNotEqualTo(value.path("targetCode"));
                assertThat(value.path("relationType").asText()).isNotBlank();
            }
            for (JsonNode leaf : fixture.get("expectedLeaves")) {
                assertThat(projection.path("elements").has(leaf.asText())).as("Architecture includes %s", leaf).isTrue();
            }
            Set<String> concreteEndpoints = new HashSet<>();
            fixture.get("expectedLeaves").forEach(leaf -> concreteEndpoints.add(leaf.asText()));
            for (JsonNode relation : analysis.at("/architectureView/includedRelationships")) {
                if (relation.path("origin").asText().equals("IMPACT_DERIVED")) {
                    assertThat(concreteEndpoints).contains(relation.path("sourceCode").asText(), relation.path("targetCode").asText());
                }
            }
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
                            .contains(CivilianExportQa.sha256(response.body()));
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
            save("quality.json", json.valueToTree(CivilianExportQa.verify(projection, artifacts, output,
                    Boolean.getBoolean("generateScreenshots"))));
            CivilianIntegrationWalkthrough.verify(this, artifacts.get("architecture.archimate.xml"));
            // Export/reopen must not perform another analysis, even when a provider is configured.
            playback.verifyCoverage(2);
            CivilianPublicationWalkthrough.prepare(this, publicationProvider);
            if (browser != null) browser.inspect(projectId, requirementId, snapshotId, projection, artifacts, this);
            playback.verifyCoverage(2);
            assertFrozenWordSourceSurvivesLiveStateMutations(projectId,snapshotId,projection);
            save("run.json", json.valueToTree(Map.of("scenario", fixture.path("id").asText(),
                    "fixtureSha256", ScenarioLlmPlayback.sha256(fixture.toString()),
                    "projectId", projectId, "requirementId", requirementId, "snapshotId", snapshotId,
                    "llmCalls", playback.calls().size(), "mockBoundary", "remote LLM HTTP response only",
                    "sourceRevision", System.getenv().getOrDefault("GITHUB_SHA", "local-working-tree"),
                    "ciRun", System.getenv().getOrDefault("GITHUB_RUN_ID", "local"))));
        }
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
        org.mockito.Mockito.clearInvocations(liveReports);
        try {
            node.setNameEn("MUTATED LIVE CATALOGUE TITLE");node.setDescriptionEn("Mutated live description after saving snapshot");
            catalogueRepository.saveAndFlush(node);
            preferences.update(Map.of("diagram.policy","leafOnly","analysis.min-relevance-score",99,"limits.max-architecture-nodes",1),"word-regression");
            proposal=proposalRepository.saveAndFlush(proposal);
            assertThat(catalogueService.getNodeByCode("BP-1017").getNameEn()).isEqualTo("MUTATED LIVE CATALOGUE TITLE");
            assertThat(preferences.get("diagram.policy")).isEqualTo("leafOnly");
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
            if(proposal.getId()!=null)proposalRepository.deleteById(proposal.getId());
        }
    }

    Run startThroughHttp(JsonNode fixture) throws Exception {
        String projectKey = "CIV-FLOOD-" + UUID.randomUUID().toString().substring(0, 8);
        long projectId = post("/api/projects", Map.of("projectKey", projectKey,
                "title", "Civilian flood information", "description", "Source-backed deterministic acceptance scenario",
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

    JsonNode get(String path) throws Exception { return json.readTree(request("GET", path, null, 200).body()); }
    JsonNode post(String path, Object body, int status) throws Exception {
        return json.readTree(request("POST", path, body, status).body());
    }
    HttpResponse<byte[]> request(String method, String path, Object body, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        ("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json");
        var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
        String excerpt = new String(response.body(), StandardCharsets.UTF_8);
        assertThat(response.statusCode()).as("%s %s: %s", method, path,
                excerpt.substring(0, Math.min(excerpt.length(), 400)))
                .isEqualTo(status);
        return response;
    }
    void save(String file, JsonNode value) throws Exception {
        Files.writeString(output.resolve(file), value.toPrettyString());
    }
    static void catalogueNames(JsonNode nodes, Map<String, String> names) {
        for (JsonNode node : nodes) {
            names.put(node.path("code").asText(), node.path("name").asText());
            catalogueNames(node.path("children"), names);
        }
    }
}
