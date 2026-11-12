package com.taxonomy.composition.demo;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.analysis.demo.DemoScenario;
import com.taxonomy.analysis.demo.DemoScenarioCatalog;
import com.taxonomy.analysis.demo.evaluation.DemoReferenceEvaluator;
import com.taxonomy.analysis.service.LlmGatewayRegistry;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.composition.reformulation.ReformulationExecutionService;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.reformulation.ReformulationAdoptionDtos;
import com.taxonomy.portfolio.reformulation.ReformulationAdoptionService;
import com.taxonomy.portfolio.reformulation.ReformulationDtos;
import com.taxonomy.portfolio.reformulation.ReformulationService;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioReportService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Complete product demo (issue #1184) through the real product paths: R0, analysis A0,
 * bottom-up reformulation with questions, visible demo answers and a rejected addition,
 * explicit adoption, R1, analysis A1 and the independent reference evaluation. Only the
 * external AI reply is replaced by the scenario replay; any outbound provider HTTP fails.
 */
@SpringBootTest(properties = {"llm.mock=false", "llm.provider=CUSTOM_OPENAI", "custom.llm.model=demo-isolation",
        "custom.llm.api.key=demo-test-credential"})
@AutoConfigureMockMvc
@WithMockUser(username = "architect", roles = "ARCHITECT")
class ProductDemoEndToEndTest {
    private static final String USER = "architect";

    @Autowired ProductDemoService demos;
    @Autowired ProjectPortfolioService projects;
    @Autowired ProjectRequirementAnalysisService analysis;
    @Autowired PortfolioAnalysisPersistenceService analyses;
    @Autowired ReformulationService reformulations;
    @Autowired ReformulationExecutionService execution;
    @Autowired ReformulationAdoptionService adoptions;
    @Autowired PortfolioReportService reports;
    @Autowired WorkspaceManager workspaces;
    @Autowired RestTemplate transport;
    @Autowired LlmGatewayRegistry gateways;
    @Autowired WorkspaceResolver resolver;
    @Autowired DemoScenarioCatalog catalog;
    @Autowired ProductDemoProviderBinding binding;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;

    private DemoScenario scenario;

    private ClientHttpRequestFactory originalFactory;
    private static HttpServer loopback;
    private static final AtomicInteger WIRE_REQUESTS = new AtomicInteger();

    @DynamicPropertySource
    static void configureProvider(DynamicPropertyRegistry properties) throws IOException {
        loopback = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        loopback.createContext("/", exchange -> {
            try (exchange) {
                WIRE_REQUESTS.incrementAndGet();
                if (!"POST".equals(exchange.getRequestMethod())
                        || !"/v1/chat/completions".equals(exchange.getRequestURI().getPath())
                        || !"Bearer demo-test-credential".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                byte[] reply = """
                        {"choices":[{"message":{"content":"local transport control"}}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, reply.length);
                exchange.getResponseBody().write(reply);
            }
        });
        loopback.start();
        properties.add("custom.llm.url", () -> "http://127.0.0.1:" + loopback.getAddress().getPort() + "/v1/chat/completions");
    }

    @AfterEach
    void restoreTransport() {
        if (originalFactory != null) {
            transport.setRequestFactory(originalFactory);
        }
    }

    @AfterAll
    static void stopProvider() {
        if (loopback != null) loopback.stop(0);
    }
    @org.junit.jupiter.api.BeforeEach
    void scenario() {
        scenario = catalog.defaultScenario();
    }

    @Test
    void completeDemoRunsWithoutExternalAiAndPassesTheIndependentReference() throws Exception {
        int beforeControl = WIRE_REQUESTS.get();
        String reply = gateways.getGateway(LlmProvider.CUSTOM_OPENAI)
                .sendHttpRequest("Verify the controlled provider connection", "demo-test-credential");
        assertThat(reply).contains("local transport control");
        int afterControl = WIRE_REQUESTS.get();
        assertThat(afterControl).isEqualTo(beforeControl + 1);
        originalFactory = transport.getRequestFactory();
        var server = MockRestServiceServer.bindTo(transport).build();
        server.expect(ExpectedCount.never(), MockRestRequestMatchers.anything());
        WorkspaceContext context = workspace();

        // An unrelated draft stays untouched; demo and normal projects are isolated.
        var normal = projects.createProject(new CreateProjectRequest("NORMAL", "Normal", null, ProjectStatus.ACTIVE,
                null, null, null, null), USER, context);
        var draft = projects.createRequirement(normal.id(), new CreateRequirementRequest("D-1", "Draft",
                "Mein unvollständiger Entwurf", RequirementStatus.DRAFT, null, null, null, null, null, "draft", null),
                USER, context);

        var run = demos.start(null, USER, context);
        assertThat(run.scenarioId()).isEqualTo(scenario.id());
        assertThat(run.banner().get("de")).startsWith("DEMODUS");
        assertThat(run.activeProvider()).isEqualTo("DEMO.TIME-TRACKING-V1.R0");
        assertThat(run.projectId()).isNotEqualTo(normal.id());
        assertThat(projects.getRequirement(run.projectId(), run.requirementId(), USER, context).currentVersion().text())
                .isEqualTo(scenario.r0Text());
        assertThat(projects.getRequirement(normal.id(), draft.id(), USER, context).currentVersion().text())
                .isEqualTo("Mein unvollständiger Entwurf");

        // Admission: no demo provider for normal projects, no live provider for demo projects.
        assertThatThrownBy(() -> analysis.analyzeRequirement(normal.id(), draft.id(), "DEMO.TIME-TRACKING-V1.R0", 25,
                UUID.randomUUID().toString(), USER, context)).isInstanceOf(PortfolioException.class)
                .extracting(e -> ((PortfolioException) e).getCode()).isEqualTo("PROVIDER_REQUIRES_PROJECT_BINDING");
        assertThatThrownBy(() -> analysis.analyzeRequirement(run.projectId(), run.requirementId(), "GEMINI", 25,
                UUID.randomUUID().toString(), USER, context)).isInstanceOf(PortfolioException.class)
                .extracting(e -> ((PortfolioException) e).getCode()).isEqualTo(ProductDemoProblems.PROVIDER_REJECTED);
        assertThat(demos.list(run.projectId(), USER, context)).extracting(ProductDemoService.DemoRunView::id).containsExactly(run.id());
        assertThat(demos.list(normal.id(), USER, context)).isEmpty();

        // A0 on R0.
        heap("before-A0");
        var a0 = analysis.analyzeRequirement(run.projectId(), run.requirementId(), null, 25,
                UUID.randomUUID().toString(), USER, context);
        heap("after-A0");
        assertThat(a0.provider()).isEqualTo("DEMO.TIME-TRACKING-V1.R0");
        assertThat(a0.status()).as(() -> "A0 job " + a0 + " warnings " + analyses.getSnapshot(run.projectId(),
                a0.items().getFirst().snapshotId(), USER, context).analysis().getWarnings()).isEqualTo(AnalysisStatus.PARTIAL);
        String a0Snapshot = a0.items().getFirst().snapshotId();
        var a0Evidence = analyses.getSnapshot(run.projectId(), a0Snapshot, USER, context).analysis();
        assertThat(a0Evidence.getRawScores()).doesNotContainKey("CO").containsKey("UA-1605");
        assertThat(a0Evidence.getAnalysisCoverage().nodes().get("CO")).satisfies(coverage -> {
            assertThat(coverage.state()).isEqualTo(com.taxonomy.dto.AnalysisCoverage.State.UNKNOWN);
            assertThat(coverage.score()).isNull();
            assertThat(coverage.reason()).startsWith("CLARIFICATION:");
        });

        // Bottom-up reformulation with five questions and one surveillance addition.
        var requirement = projects.getRequirement(run.projectId(), run.requirementId(), USER, context);
        var proposal = reformulations.create(run.projectId(), run.requirementId(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(), a0Snapshot, "de"), USER, context);
        runReformulation(run, proposal, context, "COMPLETED");
        proposal = reformulations.get(run.projectId(), run.requirementId(), proposal.id(), USER, context);
        var first = proposal.currentRevision();
        assertThat(first.questions()).hasSize(5);
        String gps = scenario.demoReviews().getFirst().wording();
        var gpsStatement = first.statements().stream().filter(s -> s.wording().equals(gps)).findFirst().orElseThrow();

        // Visible human review: reject the addition and answer with the scenario's demo decisions.
        proposal = reformulations.statement(run.projectId(), run.requirementId(), proposal.id(),
                proposal.currentRevision().number(), gpsStatement.id(),
                new ReformulationDtos.StatementRequest("REJECT", null, scenario.demoReviews().getFirst().rationale()),
                USER, context);
        for (var decision : scenario.demoDecisions()) {
            var authored = scenario.question(decision.question());
            var question = proposal.currentRevision().questions().stream()
                    .filter(q -> q.key().subject().equals(authored.subject())).findFirst().orElseThrow();
            proposal = reformulations.answer(run.projectId(), run.requirementId(), proposal.id(),
                    proposal.currentRevision().number(), new ReformulationDtos.AnswerRequest(question.id(), "ANSWER",
                            decision.values(), "", decision.rationale()), USER, context);
        }
        assertThat(projects.getRequirement(run.projectId(), run.requirementId(), USER, context).currentVersion()
                .versionNumber()).as("answers never adopt").isEqualTo(1);

        // The regeneration never overwrites the human review: it is offered as a protected candidate
        // that the user takes over explicitly.
        var protectedRun = runReformulation(run, proposal, context, "PARTIAL");
        assertThat(protectedRun.failureCode()).isEqualTo("MANUAL_DRAFT_PROTECTED");
        String reformulated = protectedRun.candidate().text();
        assertThat(reformulated).contains(scenario.r1RequiredPhrases().toArray(String[]::new)).doesNotContain(gps)
                .contains(scenario.r0Text());
        assertThat(protectedRun.candidate().statements()).filteredOn(s -> s.wording().equals(gps))
                .allSatisfy(s -> assertThat(s.reviewState()).isEqualTo("REJECTED"));
        proposal = reformulations.saveDraft(run.projectId(), run.requirementId(), proposal.id(),
                proposal.currentRevision().number(), new ReformulationDtos.SaveDraftRequest(reformulated,
                        "Produktdemo: geprüften Kandidaten übernehmen"), USER, context);

        // Explicit adoption: preview plus confirmation; a duplicate confirmation creates no second version.
        var preview = adoptions.preview(run.projectId(), run.requirementId(), proposal.id(),
                proposal.currentRevision().number(), USER, context);
        var confirm = new ReformulationAdoptionDtos.ConfirmRequest(UUID.randomUUID().toString(), preview.content().id(),
                preview.hash(), true, true, "Produktdemo: geprüfte Neufassung übernehmen");
        var adopted = adoptions.adopt(run.projectId(), run.requirementId(), proposal.id(),
                proposal.currentRevision().number(), confirm, USER, context);
        var again = adoptions.adopt(run.projectId(), run.requirementId(), proposal.id(),
                proposal.currentRevision().number(), confirm, USER, context);
        assertThat(adopted.targetVersionNumber()).isEqualTo(2);
        assertThat(again.targetVersionId()).isEqualTo(adopted.targetVersionId());
        assertThat(projects.listRequirementVersions(run.projectId(), run.requirementId(), USER, context)).hasSize(2);
        assertThat(demos.get(run.id(), USER, context).activeProvider()).isEqualTo("DEMO.TIME-TRACKING-V1.R1");

        // A1 on R1 and the independent reference evaluation.
        heap("before-A1");
        var a1 = analysis.analyzeRequirement(run.projectId(), run.requirementId(), null, 25,
                UUID.randomUUID().toString(), USER, context);
        heap("after-A1");
        String a1Snapshot = a1.items().getFirst().snapshotId();
        assertThat(a1.provider()).isEqualTo("DEMO.TIME-TRACKING-V1.R1");
        assertThat(a1.status()).as(() -> "A1 job " + a1 + " warnings " + analyses.getSnapshot(run.projectId(),
                a1Snapshot, USER, context).analysis().getWarnings()).isEqualTo(AnalysisStatus.SUCCESS);
        var comparison = demos.compare(run.id(), USER, context);
        assertThat(comparison.a0SnapshotId()).isEqualTo(a0Snapshot);
        assertThat(comparison.a1SnapshotId()).isEqualTo(a1Snapshot);
        assertThat(comparison.report().overall()).as("reference checks %s", comparison.report().checks())
                .isEqualTo(DemoReferenceEvaluator.Overall.PASS);

        // Second generation on the adopted R1: no repeated questions, the rejected addition stays out.
        var r1Requirement = projects.getRequirement(run.projectId(), run.requirementId(), USER, context);
        String adoptedText = r1Requirement.currentVersion().text();
        assertThat(adoptedText).isEqualTo(preview.content().finalText());
        var next = reformulations.create(run.projectId(), run.requirementId(), new ReformulationDtos.CreateRequest(
                r1Requirement.currentVersionId(), a1Snapshot, "de"), USER, context);
        assertThat(next.baseline().frozenContext()).containsKeys("adoptedLineage", "inheritedDecisionContext");
        var secondGeneration = runReformulation(run, next, context, "COMPLETED");
        assertThat(secondGeneration.provider()).isEqualTo("DEMO.TIME-TRACKING-V1.R1");
        var nextRevision = reformulations.get(run.projectId(), run.requirementId(), next.id(), USER, context).currentRevision();
        assertThat(nextRevision.text()).doesNotContain(gps);
        assertThat(nextRevision.questions()).isEmpty();

        // Download the actual saved revision and receipt endpoints after both analyses exist.
        var jobsBeforeExport = analyses.listJobs(run.projectId(), USER, context);
        var r0RunsBeforeExport = reformulations.runs(run.projectId(), run.requirementId(), proposal.id(), USER, context);
        var r1RunsBeforeExport = reformulations.runs(run.projectId(), run.requirementId(), next.id(), USER, context);
        String base = "/api/projects/" + run.projectId() + "/requirements/" + run.requirementId() + "/reformulations/";
        var original = downloadReports(base + proposal.id() + "/revisions/" + first.number(),
                proposal.id() + "-revision-" + first.number());
        assertDemoSource(original, "R0", requirement.currentVersionId(), a0Snapshot, scenario.r0Text());
        assertThat(original.json().at("/proposal/id").asString()).isEqualTo(proposal.id());
        assertThat(original.json().at("/proposal/revision").asLong()).isEqualTo(first.number());
        assertThat(original.json().at("/revision/questions")).hasSize(5);
        assertThat(original.json().at("/revision/answers")).isEmpty();
        assertThat(original.word()).contains("DEMO · Neuformulierungsangebot — gespeicherte Revision",
                scenario.r0Text(), first.questions().getFirst().id() + " — " + first.questions().getFirst().wording());

        var receipt = downloadReports(base + proposal.id() + "/adoptions/" + confirm.commandId(),
                proposal.id() + "-adoption-" + confirm.commandId());
        assertDemoSource(receipt, "R0", requirement.currentVersionId(), a0Snapshot, scenario.r0Text());
        assertThat(receipt.json().at("/adoption/commandId").asString()).isEqualTo(confirm.commandId());
        assertThat(receipt.json().at("/adoption/targetVersionId").asLong()).isEqualTo(adopted.targetVersionId());
        assertThat(receipt.json().at("/preview/hash").asString()).isEqualTo(preview.hash());
        assertThat(receipt.json().path("reviewedText").asString()).isEqualTo(adoptedText);
        assertThat(receipt.json().at("/revision/answers")).hasSize(scenario.demoDecisions().size());
        assertThat(receipt.word()).contains("DEMO · Neuformulierung — Übernahmebeleg",
                "Zielversion: " + adopted.targetVersionId(), "Vorschau-SHA-256: " + preview.hash());
        assertThat(receipt.word()).anySatisfy(paragraph ->
                assertThat(paragraph).contains(scenario.demoDecisions().getFirst().rationale()));

        var second = downloadReports(base + next.id() + "/revisions/" + nextRevision.number(),
                next.id() + "-revision-" + nextRevision.number());
        assertDemoSource(second, "R1", adopted.targetVersionId(), a1Snapshot, adoptedText);
        assertThat(second.json().at("/proposal/id").asString()).isEqualTo(next.id());
        assertThat(second.json().at("/proposal/revision").asLong()).isEqualTo(nextRevision.number());
        assertThat(second.json().at("/revision/questions")).isEmpty();
        assertThat(second.word()).contains("DEMO · Neuformulierungsangebot — gespeicherte Revision", adoptedText);
        assertThat(reformulations.runs(run.projectId(), run.requirementId(), proposal.id(), USER, context))
                .isEqualTo(r0RunsBeforeExport);
        assertThat(reformulations.runs(run.projectId(), run.requirementId(), next.id(), USER, context))
                .isEqualTo(r1RunsBeforeExport);

        assertFrozenDecisionReports(run.projectId(), a0Snapshot, 1, "DEMO.TIME-TRACKING-V1.R0");
        assertFrozenDecisionReports(run.projectId(), a1Snapshot, 2, "DEMO.TIME-TRACKING-V1.R1");
        assertThat(analyses.listJobs(run.projectId(), USER, context)).isEqualTo(jobsBeforeExport);

        // Exports of the demo project stay labelled as demo output.
        var export = reports.render(run.projectId(), run.requirementId(), PortfolioReportService.Format.MARKDOWN,
                null, USER, context);
        assertThat(export.filename()).startsWith("DEMO-");
        assertThat(new String(export.bytes(), java.nio.charset.StandardCharsets.UTF_8)).contains("DEMO · " + scenario.title().get(scenario.requirement().language()));

        // Closing the demo stops further replay admission.
        demos.close(run.id(), USER, context);
        assertThatThrownBy(() -> analysis.analyzeRequirement(run.projectId(), run.requirementId(), null, 25,
                UUID.randomUUID().toString(), USER, context)).isInstanceOf(PortfolioException.class)
                .extracting(e -> ((PortfolioException) e).getCode()).isEqualTo(ProductDemoProblems.RUN_CLOSED);
        server.verify();
        assertThat(WIRE_REQUESTS.get()).as("demo work and downloads must not reach the configured provider by any HTTP client")
                .isEqualTo(afterControl);
    }

    private void assertFrozenDecisionReports(Long projectId, String snapshotId, int version,
                                             String provider) throws Exception {
        String path = "/api/projects/" + projectId + "/snapshots/" + snapshotId + "/decision-report/";
        var formats = json.readTree(mvc.perform(get(path + "formats")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(formats).anySatisfy(format -> assertThat(format.path("id").asString()).isEqualTo("docx"));
        String fingerprint = null;
        for (JsonNode format : formats) {
            String id = format.path("id").asString();
            var response = mvc.perform(get(path + id).param("language", "de").param("profile", "COMPACT")
                            .param("taxonomyRoots", "CP").param("sections", "SUMMARY", "EVIDENCE"))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getHeader("X-Taxonomy-Snapshot-Id")).isEqualTo(snapshotId);
            String digest = response.getHeader("X-Taxonomy-Analysis-SHA256");
            assertThat(digest).matches("[0-9a-f]{64}");
            if (fingerprint == null) fingerprint = digest;
            assertThat(digest).isEqualTo(fingerprint);
            assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"DEMO-taxonomy-decision-rationale-report-v"
                    + version + "." + format.path("fileExtension").asString() + "\"");
            byte[] payload = response.getContentAsByteArray();
            assertThat(payload).isNotEmpty();
            if (id.equals("json")) {
                var parsed = json.readTree(payload);
                assertThat(parsed.at("/metadata/analysisSnapshotId").asString()).isEqualTo(snapshotId);
                assertThat(parsed.at("/metadata/analysisProvider").asString()).isEqualTo(provider);
                assertThat(parsed.path("languageTag").asString()).isEqualTo("de");
            } else if (id.equals("html")) {
                assertThat(new String(payload, StandardCharsets.UTF_8)).contains(provider, snapshotId);
            } else if (id.equals("docx")) {
                try (var document = new XWPFDocument(new ByteArrayInputStream(payload));
                     var text = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(document)) {
                    assertThat(text.getText()).contains(provider, snapshotId);
                }
            }
        }
    }

    private record DemoReports(JsonNode json, List<String> word, String markdown, String html) { }

    private MockHttpServletResponse attachment(String path, String identity, String format, String mediaType) throws Exception {
        var response = mvc.perform(get(path + "/export").param("format", format))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"DEMO-reformulation-" + identity + "." + format + "\"");
        assertThat(response.getContentType()).startsWith(mediaType);
        assertThat(response.getHeader("X-Content-SHA256")).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(response.getContentAsByteArray())));
        return response;
    }

    private DemoReports downloadReports(String path, String identity) throws Exception {
        var jsonResponse = attachment(path, identity, "json", "application/json");
        var wordResponse = attachment(path, identity, "docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        var markdown = attachment(path, identity, "md", "text/markdown").getContentAsString(StandardCharsets.UTF_8);
        var html = attachment(path, identity, "html", "text/html").getContentAsString(StandardCharsets.UTF_8);
        try (var word = new XWPFDocument(new ByteArrayInputStream(wordResponse.getContentAsByteArray()))) {
            return new DemoReports(json.readTree(jsonResponse.getContentAsByteArray()),
                    word.getParagraphs().stream().map(XWPFParagraph::getText).toList(), markdown, html);
        }
    }

    private void assertDemoSource(DemoReports downloads, String iteration, long versionId,
            String snapshotId, String originalText) throws Exception {
        var source = downloads.json().path("source");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(originalText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(source.path("versionId").asLong()).isEqualTo(versionId);
        assertThat(source.path("analysisSnapshotId").asString()).isEqualTo(snapshotId);
        assertThat(source.path("originalText").asString()).isEqualTo(originalText);
        assertThat(source.path("originalTextHash").asString()).isEqualTo(hash);
        assertThat(source.path("language").asString()).isEqualTo("de");
        String provider = "DEMO.TIME-TRACKING-V1." + iteration;
        assertThat(source.path("analysisProvider").asString()).isEqualTo(provider);
        assertThat(downloads.markdown()).contains("DEMO", originalText, hash, snapshotId, provider);
        assertThat(downloads.html()).contains("<html lang=\"de\">", "<h1>DEMO", hash, snapshotId, provider);
        assertThat(downloads.word()).contains("Originalanforderung", "Quellversion: " + versionId,
                "Quell-SHA-256: " + hash, "Analyse-Snapshot: " + snapshotId);
        assertThat(downloads.word()).anySatisfy(paragraph -> assertThat(paragraph).contains(provider));
    }

    /** Closing waits for an admission that holds the run lock, and no replay is admitted after the close commits. */
    @Test
    void closeSerializesWithProviderAdmission() throws Exception {
        WorkspaceContext context = workspace();
        var run = demos.start(null, USER, context);
        var versionId = projects.getRequirement(run.projectId(), run.requirementId(), USER, context).currentVersionId();
        var target = java.util.List.of(new com.taxonomy.portfolio.service.ProjectProviderBinding.Target(run.requirementId(), versionId));
        var admitted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2, task -> {
            var thread = new Thread(task, "demo-lock");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var admission = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .execute(status -> {
                        var provider = binding.admittedProvider(run.projectId(), target, null, USER, context);
                        admitted.countDown();
                        try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return provider.orElseThrow();
                    }));
            assertThat(admitted.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var close = executor.submit(() -> demos.close(run.id(), USER, context));
            Thread.sleep(300);
            assertThat(close.isDone()).as("close must wait for the admission holding the run lock").isFalse();
            release.countDown();
            assertThat(admission.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("DEMO.TIME-TRACKING-V1.R0");
            assertThat(close.get(30, java.util.concurrent.TimeUnit.SECONDS).closedAt()).isNotNull();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(status -> binding.admittedProvider(run.projectId(), target, null, USER, context)))
                .isInstanceOf(PortfolioException.class)
                .extracting(e -> ((PortfolioException) e).getCode()).isEqualTo(ProductDemoProblems.RUN_CLOSED);
    }

    /** REST errors carry a stable machine-readable code instead of message parsing. */
    @Test
    void restProblemsCarryStableCodes() throws Exception {
        workspace();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/product-demo/runs/unknown"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value(ProductDemoProblems.RUN_NOT_FOUND));
    }

    /** The created resource keeps the deployment's base path. */
    @Test
    void startLocationKeepsTheApplicationBasePath() throws Exception {
        workspace();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/taxonomy/api/product-demo/runs")
                        .contextPath("/taxonomy").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location",
                        org.hamcrest.Matchers.matchesPattern("http://localhost/taxonomy/api/product-demo/runs/[0-9a-f-]{36}")));
    }

    private ReformulationDtos.Run runReformulation(ProductDemoService.DemoRunView run, ReformulationDtos.Proposal proposal,
                                                   WorkspaceContext context, String expectedStatus) {
        var started = execution.start(run.projectId(), run.requirementId(), proposal.id(),
                proposal.currentRevision().number(), USER, context);
        assertThat(started.provider()).startsWith("DEMO.TIME-TRACKING-V1.R");
        await().atMost(Duration.ofSeconds(60)).until(() -> reformulations.runs(run.projectId(), run.requirementId(),
                proposal.id(), USER, context).stream().filter(r -> r.id().equals(started.id()))
                .anyMatch(r -> List.of("COMPLETED", "PARTIAL", "FAILED", "CANCELLED").contains(r.status())));
        var finished = reformulations.runs(run.projectId(), run.requirementId(), proposal.id(), USER, context).stream()
                .filter(r -> r.id().equals(started.id())).findFirst().orElseThrow();
        assertThat(finished.status()).as("run %s", finished).isEqualTo(expectedStatus);
        return finished;
    }

    /** Diagnostic only: observe the same heap source as the production guard; never GC or alter policy. */
    private static void heap(String station) {
        var sample = com.taxonomy.analysis.service.AnalysisMemoryGuard.heapSample();
        System.out.printf("PRODUCT_DEMO_HEAP station=%s pid=%d usedBytes=%d maximumBytes=%d headroomBytes=%d%n",
                station, ProcessHandle.current().pid(), sample.usedBytes(), sample.maximumBytes(),
                sample.maximumBytes() - sample.usedBytes());
    }

    private WorkspaceContext workspace() {
        var workspace = workspaces.createWorkspace(USER, "Demo " + UUID.randomUUID(), "Product demo");
        workspaces.provisionWorkspaceRepository(USER, workspace.getWorkspaceId());
        workspaces.switchWorkspace(USER, workspace.getWorkspaceId());
        return resolver.resolveCurrentContext();
    }
}
