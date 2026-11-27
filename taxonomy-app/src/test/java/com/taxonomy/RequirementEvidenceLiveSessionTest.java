package com.taxonomy;

import com.taxonomy.testsupport.BrowserSession;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.PrintWriter;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in live guided session. The operator reviews real questions through recorded decision files. */
@Tag("real-llm")
@EnabledIfSystemProperty(named = "taxonomy.requirementEvidence.evaluateLive", matches = "true")
@ActiveProfiles("hsqldb-file")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.context.annotation.Import(RequirementEvidenceLiveSessionTest.HttpDiagnostics.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "taxonomy.features.multi-repository-api.enabled=true", "taxonomy.init.async=false",
        "taxonomy.admin-password=" + RequirementEvidenceLiveSessionTest.PASSWORD,
        "taxonomy.security.require-password-change=false", "taxonomy.analysis.transport.mode=local",
        "embedding.enabled=false", "embedding.allow-download=false", "taxonomy.catalogue.overlay.enabled=true",
        "taxonomy.catalogue.resource=classpath:data/bilingual/C3_Taxonomy_Catalogue_25AUG2025.xlsx",
        "llm.provider=CUSTOM_OPENAI", "llm.mock=false", "llm.replay=false", "llm.record=true",
        "custom.llm.structured-output-enabled=true", "taxonomy.llm.rpm=120", "taxonomy.llm.timeout-seconds=300"})
class RequirementEvidenceLiveSessionTest {
    static final String PASSWORD = "Requirement-Evidence-Only-2026!";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path RUN = Path.of(System.getenv().getOrDefault("TAXONOMY_EVIDENCE_RUN_DIR",
            "target/requirement-evidence-live")).toAbsolutePath();
    private static final boolean RESUME_FIRST_STAGE = "true".equals(System.getenv("TAXONOMY_EVIDENCE_RESUME_FIRST_STAGE"));
    private static final boolean RECOVER_FINAL = "true".equals(System.getenv("TAXONOMY_EVIDENCE_RECOVER_FINAL"));
    private static final boolean PRECISION_RECHECK = "true".equals(System.getenv("TAXONOMY_EVIDENCE_PRECISION_RECHECK"));
    private static final boolean DEFER_BROWSER = "true".equals(System.getenv("TAXONOMY_EVIDENCE_DEFER_BROWSER"));
    private static final String BROWSER_ONLY_LABEL = System.getenv("TAXONOMY_EVIDENCE_BROWSER_ONLY_LABEL");
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
    private final ScenarioHttpSession session = new ScenarioHttpSession(http, cookies, JSON, "admin", RequirementEvidenceLiveSessionTest.PASSWORD);
    private final Set<String> seenCompletions = new HashSet<>();
    @LocalServerPort int port;
    @Value("${custom.llm.model}") String model;

    /** Private test-run diagnostics; production's privacy-preserving exception handler remains unchanged. */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class HttpDiagnostics {
        @org.springframework.context.annotation.Bean
        org.springframework.web.servlet.config.annotation.WebMvcConfigurer captureLiveHttpFailures() {
            return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
                @Override public void extendHandlerExceptionResolvers(List<org.springframework.web.servlet.HandlerExceptionResolver> resolvers) {
                    resolvers.addFirst((request, response, handler, failure) -> {
                        var trace = new java.io.StringWriter();
                        failure.printStackTrace(new PrintWriter(trace));
                        try { Files.writeString(RUN.resolve("http-failure-" + System.nanoTime() + ".txt"), trace.toString()); }
                        catch (java.io.IOException unavailable) { failure.addSuppressed(unavailable); }
                        return null;
                    });
                }
            };
        }
    }

    /** Uses the Maven-resolved test classpath in a separate bounded JVM when a reactor cannot coexist with Spring. */
    public static void main(String[] arguments) {
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(RequirementEvidenceLiveSessionTest.class)).build());
        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out, true));
        summary.printFailuresTo(new PrintWriter(System.out, true));
        if (summary.getTestsSucceededCount() != 1 || summary.getTotalFailureCount() != 0)
            throw new AssertionError("The opt-in live session did not complete successfully");
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) throws Exception {
        requiredEnvironment("TAXONOMY_EVIDENCE_RUN_DIR");
        if (RESUME_FIRST_STAGE && RECOVER_FINAL) throw new IllegalStateException("Choose only one recovery phase");
        if (PRECISION_RECHECK && !RECOVER_FINAL) throw new IllegalStateException("Precision comparison requires the retained three-stage session");
        if (Files.exists(RUN) && !RESUME_FIRST_STAGE && !RECOVER_FINAL)
            throw new IllegalStateException("Use a new unique directory for every live session: " + RUN);
        if (RESUME_FIRST_STAGE) {
            for (String file : List.of("session.json", "1-product-job.json", "1-product-snapshot.json"))
                if (!Files.isRegularFile(RUN.resolve(file))) throw new IllegalStateException("Missing retained session evidence: " + file);
            if (Files.exists(RUN.resolve("1-product-adoption.json")))
                throw new IllegalStateException("This recovery entry point only resumes before the first adoption");
        }
        if (RECOVER_FINAL) {
            for (String file : List.of("session.json", "1-product-adoption.json", "2-storage-service-adoption.json",
                    "1-product-snapshot.json", "2-storage-service-snapshot.json", "3-operations-process-snapshot.json"))
                if (!Files.isRegularFile(RUN.resolve(file))) throw new IllegalStateException("Missing final recovery evidence: " + file);
        }
        Files.createDirectories(RUN);
        properties.add("custom.llm.url", () -> requiredEnvironment("CUSTOM_LLM_URL"));
        properties.add("custom.llm.model", () -> requiredEnvironment("CUSTOM_LLM_MODEL"));
        properties.add("llm.recordings.dir", () -> RUN.resolve("provider-responses").toString());
        properties.add("TAXONOMY_HSQLDB_FILE_PATH", () -> RUN.resolve("storage/database").toString());
        // Preserve the real file-backed store with smaller/compressed LOB allocation for this bounded runner.
        properties.add("spring.datasource.url", () -> "jdbc:hsqldb:file:" + RUN.resolve("storage/database")
                + ";hsqldb.default_table_type=cached;hsqldb.cache_size=2048;hsqldb.cache_rows=5000"
                + ";hsqldb.lob_compressed=true;hsqldb.lob_file_scale=1;hsqldb.write_delay_millis=0;shutdown=true");
        properties.add("TAXONOMY_SEARCH_DIRECTORY_ROOT", () -> RUN.resolve("storage/index").toString());
    }

    @Test
    void guideProductThroughClarificationAdoptionAndProcessAnalysis() throws Exception {
        JsonNode reference;
        try (var input = getClass().getResourceAsStream("/scenarios/altscherbitz-backup-reference-v1.json")) {
            reference = JSON.readTree(input);
        }
        if (BROWSER_ONLY_LABEL != null && !BROWSER_ONLY_LABEL.isBlank()) {
            verifyBrowserOnly(reference);
            return;
        }
        try (var catalogue = getClass().getResourceAsStream("/data/bilingual/C3_Taxonomy_Catalogue_25AUG2025.xlsx")) {
            assertThat(catalogue).as("Original catalogue resource is present").isNotNull();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(catalogue.readAllBytes())))
                    .as("Catalogue is the independently frozen original workbook")
                    .isEqualTo(reference.required("catalogueSha256").asText());
        }
        try (var overlay = getClass().getResourceAsStream("/data/nato-taxonomy.json")) {
            assertThat(overlay).as("The original catalogue's versioned hierarchy repair is present").isNotNull();
            byte[] bytes = overlay.readAllBytes();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo(reference.at("/catalogueOverlay/sha256").asText());
            JSON.readTree(bytes).required("nodePatches").forEach(patch ->
                    assertThat(patch.required("code").asText()).as("Repair does not change the selected BP/CR/UA nodes")
                            .startsWith("IP-"));
        }
        var stages = reference.required("cases");
        JsonNode retainedSession = RESUME_FIRST_STAGE || RECOVER_FINAL ? JSON.readTree(RUN.resolve("session.json").toFile()) : null;
        if (retainedSession != null) {
            assertThat(retainedSession.required("sourceReference")).isEqualTo(reference);
            assertThat(retainedSession.required("model").asText()).isEqualTo(model);
        }
        long projectId = retainedSession != null ? retainedSession.required("projectId").asLong() : post("/api/projects", Map.of("projectKey", "BACKUP-" + UUID.randomUUID().toString().substring(0, 8),
                "title", "Altscherbitz – Anforderungsbeiträge", "status", "ACTIVE",
                "description", "Quellenbasierte Beispielsitzung, Verfahren 230039; keine Vergabeentscheidung"), null, 201).required("id").asLong();
        String projectPath = "/api/projects/" + projectId;
        long requirementId = retainedSession != null ? retainedSession.required("requirementId").asLong() : post(projectPath + "/requirements", Map.of("requirementKey", "BACKUP-001",
                "title", "Backup-Infrastruktur schrittweise präzisieren", "text", stages.get(0).required("requirement").asText(),
                "status", "DRAFT", "requirementType", "TECHNICAL", "reviewStatus", "PROPOSED",
                "source", Map.of("originalText", stages.get(0).required("requirement").asText(),
                        "sectionReference", reference.at("/source/url").asText())), null, 201).required("id").asLong();
        String requirementPath = projectPath + "/requirements/" + requirementId;
        if (retainedSession == null) save("session.json", Map.of("projectId", projectId, "requirementId", requirementId, "model", model,
                "catalogue", "Original complete catalogue with pinned Information Products hierarchy repairs; selected BP/CR/UA roots, TAXONOMIES_ONLY",
                "sourceReference", reference, "applicationPort", port));
        if (RECOVER_FINAL) {
            recoverFinalPresentation(reference, stages, projectId, requirementId, requirementPath, projectPath);
            return;
        }
        var evaluations = new ArrayList<Map<String, Object>>();
        var savedSnapshots = new LinkedHashMap<String, Path>();
        var browserFailures = new ArrayList<String>();
        for (int index = 0; index < stages.size(); index++) {
            JsonNode scenario = stages.get(index);
            String label = (index + 1) + "-" + scenario.required("id").asText();
            JsonNode requirement = get(requirementPath);
            long version = requirement.required("currentVersionId").asLong();
            boolean resume = RESUME_FIRST_STAGE && index == 0;
            if (resume) assertThat(requirement.at("/currentVersion/text").asText()).isEqualTo(scenario.required("requirement").asText());
            JsonNode job = resume ? JSON.readTree(RUN.resolve(label + "-job.json").toFile()) : post(requirementPath + "/analyses", Map.of("provider", "CUSTOM_OPENAI",
                    "analysisLanguage", "de", "maxArchitectureNodes", 30, "requirementVersionId", version,
                    "idempotencyKey", UUID.randomUUID().toString(),
                    "model", Map.of("scope", Map.of("taxonomyRoots", List.of("BP", "CR", "UA"), "mode", "TAXONOMIES_ONLY"))), null, 202);
            String jobPath = projectPath + "/analysis-jobs/" + job.required("id").asText();
            JsonNode terminal = waitForSnapshot(jobPath);
            save(label + "-job.json", terminal);
            assertThat(terminal.at("/items/0/requirementVersionId").asLong()).isEqualTo(version);
            String snapshotId = terminal.path("items").get(0).required("snapshotId").asText();
            String snapshotPath = projectPath + "/snapshots/" + snapshotId;
            JsonNode snapshot = get(snapshotPath);
            assertThat(snapshot.at("/summary/requirementVersionId").asLong()).isEqualTo(version);
            if (resume) {
                assertThat(snapshot).as("Recovery uses the unchanged server-retained snapshot")
                        .isEqualTo(JSON.readTree(RUN.resolve(label + "-snapshot.json").toFile()));
                save("resumption.json", Map.of("occurredAt", java.time.Instant.now().toString(),
                        "snapshotId", snapshotId, "newModelCallsForThisStep", 0,
                        "method", "Resume the real persisted session before its first adoption; no model response replay"));
            }
            save(label + "-snapshot.json", snapshot);
            savedSnapshots.put(snapshotPath, RUN.resolve(label + "-snapshot.json"));
            JsonNode analysis = snapshot.required("analysis");
            assertThat(analysis.path("nodeAssessments").isEmpty()).as("Fresh typed evidence").isFalse();
            assertThat(snapshot.at("/summary/provider").asText()).isEqualTo("CUSTOM_OPENAI");
            assertThat(analysis.at("/modelBinding/executionCatalogueRevision").asText()).isNotBlank();
            assertThat(analysis.at("/modelBinding/scope/mode").asText()).isEqualTo("TAXONOMIES_ONLY");
            assertFreshCompletions(requirement.at("/currentVersion/text").asText());
            ObjectNode actualScenario = (ObjectNode) scenario.deepCopy();
            // Adoption may append retained unresolved decisions; source integrity uses the actual analysed version.
            actualScenario.put("requirement", requirement.at("/currentVersion/text").asText());
            var evaluation = RequirementEvidenceReferenceQa.evaluate(reference, actualScenario, analysis);
            evaluations.add(evaluation);
            save(label + "-qa.json", evaluation);
            saveSourceFidelity(label, reference, actualScenario, analysis);
            assertThat(get(requirementPath).path("reviewStatus").asText()).isEqualTo("PROPOSED");
            assertThat(get(requirementPath).path("requirementType").asText()).as("Analysis does not overwrite the editable requirement type")
                    .isEqualTo("TECHNICAL");
            try {
                captureSnapshot(projectId, requirementId, snapshotId, label, index);
            } catch (Exception | AssertionError failure) {
                // Preserve the substantive session and all three versions even if one visual check fails.
                // The final test still fails; this is not a successful browser result.
                browserFailures.add(label + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                save(label + "-browser-failure.json", Map.of("error", browserFailures.getLast()));
            }
            for (String format : List.of("json", "html", "docx")) {
                byte[] bytes = request("GET", snapshotPath + "/decision-report/" + format + "?language=de", null, null, 200);
                Files.write(RUN.resolve(label + "-decision-report." + format), bytes);
                verifyExport(format, bytes, analysis, requirement.at("/currentVersion/text").asText());
            }
            if (index + 1 < stages.size()) {
                JsonNode proposal = operatorProposal(post(requirementPath + "/reformulations", Map.of("sourceVersionId", version,
                        "snapshotId", snapshotId, "language", "de", "includeAnalysisQuestions", true), null, 200));
                save(label + "-review.json", Map.of("proposal", proposal, "nextSourceDraft", stages.get(index + 1).path("requirement").asText(),
                        "sourceUrl", reference.at("/source/url").asText(),
                        "decisionFile", label + "-decision.json", "instruction", "Review real questions; provide answers or explicitly defer. Confirm the next draft separately."));
                JsonNode decision = waitForDecision(RUN.resolve(label + "-decision.json"));
                assertThat(decision.required("proposalId").asText()).isEqualTo(proposal.required("id").asText());
                assertThat(decision.required("revision").asLong()).isEqualTo(proposal.at("/currentRevision/number").asLong());
                var questionIds = new HashSet<String>();
                proposal.at("/currentRevision/questions").forEach(question -> questionIds.add(question.required("id").asText()));
                var answeredIds = new HashSet<String>();
                decision.required("answers").forEach(answer -> assertThat(answeredIds.add(answer.required("questionId").asText()))
                        .as("Each real question receives one reviewed disposition").isTrue());
                assertThat(answeredIds).containsExactlyInAnyOrderElementsOf(questionIds);
                String proposalPath = requirementPath + "/reformulations/" + proposal.required("id").asText();
                for (JsonNode answer : decision.path("answers")) {
                    proposal = operatorProposal(post(proposalPath + "/answers", answer, proposal.at("/currentRevision/number").asLong(), 201));
                }
                String nextText = decision.required("nextText").asText();
                assertThat(nextText).as("Reviewed draft uses the independently frozen next source stage")
                        .isEqualTo(stages.get(index + 1).required("requirement").asText());
                proposal = operatorProposal(post(proposalPath + "/revisions", Map.of("text", nextText, "rationale", decision.required("rationale").asText()),
                        proposal.at("/currentRevision/number").asLong(), 201));
                save(label + "-reviewed-proposal.json", proposal);
                long revision = proposal.at("/currentRevision/number").asLong();
                JsonNode preview = post(proposalPath + "/adoption-previews", Map.of(), revision, 201);
                save(label + "-adoption-preview.json", preview);
                assertThat(get(requirementPath).required("currentVersionId").asLong()).as("Draft and preview preserve active source").isEqualTo(version);
                assertThat(preview.at("/content/blockingReasons").isEmpty()).as("Adoption preview has no structural blockers").isTrue();
                JsonNode confirmation = waitForDecision(RUN.resolve(label + "-adoption-confirmation.json"));
                assertThat(confirmation.required("previewId").asText()).isEqualTo(preview.at("/content/id").asText());
                assertThat(confirmation.required("previewHash").asText()).isEqualTo(preview.required("hash").asText());
                assertThat(confirmation.required("confirmed").asBoolean()).as("Confirmation follows inspection of the exact preview").isTrue();
                JsonNode adopted = post(proposalPath + "/adoptions", Map.of("commandId", UUID.randomUUID().toString(),
                        "previewId", preview.at("/content/id").asText(), "previewHash", preview.required("hash").asText(),
                        "confirmed", true, "acknowledgeWarnings", confirmation.required("acknowledgeWarnings").asBoolean(),
                        "rationale", confirmation.required("rationale").asText()), revision, 200);
                save(label + "-adoption.json", adopted);
                assertThat(adopted.required("analysisNeedsRefresh").asBoolean()).isTrue();
                assertThat(get(requirementPath).required("currentVersionId").asLong()).isNotEqualTo(version);
            }
        }
        for (var saved : savedSnapshots.entrySet()) assertThat(get(saved.getKey())).as("Historical snapshot unchanged")
                .isEqualTo(JSON.readTree(saved.getValue().toFile()));
        save("evaluations.json", evaluations);
        save("versions.json", get(requirementPath + "/versions"));
        save("completion-count.json", Map.of("freshCompletions", seenCompletions.size(), "model", model));
        save("browser-verification.json", Map.of("failures", browserFailures));
        assertThat(evaluations).allSatisfy(qa -> assertThat(qa.get("failures")).as(qa.toString()).isEqualTo(List.of()));
        assertThat(browserFailures).as("Real browser verification of every saved version").isEmpty();
    }

    /** Finishes presentation checks after a recorded failure; never queues analysis or changes a requirement. */
    private void recoverFinalPresentation(JsonNode reference, JsonNode stages, long projectId, long requirementId,
                                          String requirementPath, String projectPath) throws Exception {
        String finalSnapshot = verifyRetainedResults(reference, stages, requirementPath, projectPath);
        if (!DEFER_BROWSER) captureSnapshot(projectId, requirementId, finalSnapshot, "3-operations-process-recovered", 2);
        save(DEFER_BROWSER ? "final-export-recovery.json" : "final-presentation-recovery.json", Map.of("occurredAt", java.time.Instant.now().toString(),
                "newModelCalls", 0, "unchangedSnapshots", 3, "verifiedFinalExports", true, "verifiedFinalBrowser", !DEFER_BROWSER,
                "method", "Verify retained third-stage presentation after portfolio ancestry allocation fix; earlier failure retained"));
        if (PRECISION_RECHECK) recheckPrecision(reference, stages, projectId, requirementId, requirementPath, projectPath);
    }

    /** Keep large parsed snapshots out of the stack frame that coexists with Chrome. */
    private String verifyRetainedResults(JsonNode reference, JsonNode stages, String requirementPath, String projectPath) throws Exception {
        JsonNode current = get(requirementPath);
        assertThat(current.at("/currentVersion/text").asText()).isEqualTo(stages.get(2).required("requirement").asText());
        assertThat(current.path("reviewStatus").asText()).isEqualTo("PROPOSED");
        assertThat(current.path("requirementType").asText()).isEqualTo("TECHNICAL");
        byte[] lifecycle = request("GET", "/api/architecture/lifecycle", null, null, 200);
        save("lifecycle-recovery.json", Map.of("httpStatus", 200, "responseBytes", lifecycle.length,
                "method", "Real read of the previously failing lifecycle boundary, before opening Chrome"));
        var evaluations = new ArrayList<JsonNode>();
        String finalSnapshot = null;
        for (int index = 0; index < stages.size(); index++) {
            JsonNode scenario = stages.get(index);
            String label = (index + 1) + "-" + scenario.required("id").asText();
            Path saved = RUN.resolve(label + "-snapshot.json");
            JsonNode retained = JSON.readTree(saved.toFile());
            String id = retained.at("/summary/id").asText();
            assertThat(id).as("Saved snapshot identity").isNotBlank();
            String snapshotPath = projectPath + "/snapshots/" + id;
            assertThat(get(snapshotPath)).as("Recovery preserves each original server snapshot").isEqualTo(retained);
            assertFreshCompletions(scenario.required("requirement").asText());
            JsonNode analysis = retained.required("analysis");
            saveSourceFidelity(label, reference, scenario, analysis);
            evaluations.add(JSON.readTree(RUN.resolve(label + "-qa.json").toFile()));
            if (index == 2) {
                assertThat(retained.at("/summary/requirementVersionId").asLong()).isEqualTo(current.required("currentVersionId").asLong());
                finalSnapshot = id;
                for (String format : List.of("json", "html", "docx")) {
                    byte[] bytes = request("GET", snapshotPath + "/decision-report/" + format + "?language=de", null, null, 200);
                    Files.write(RUN.resolve(label + "-recovered-decision-report." + format), bytes);
                    verifyExport(format, bytes, analysis, scenario.required("requirement").asText());
                }
            }
        }
        save("evaluations.json", evaluations);
        save("versions.json", get(requirementPath + "/versions"));
        save("completion-count.json", Map.of("freshCompletionsInOriginalAnalyses", seenCompletions.size(),
                "newModelCallsDuringPresentationRecovery", 0, "model", model));
        return java.util.Objects.requireNonNull(finalSnapshot);
    }

    /** New live analyses of the unchanged historical versions, after the documented generic prompt correction. */
    private void recheckPrecision(JsonNode reference, JsonNode stages, long projectId, long requirementId,
                                  String requirementPath, String projectPath) throws Exception {
        long activeVersion = get(requirementPath).required("currentVersionId").asLong();
        var comparisons = new ArrayList<Map<String, Object>>();
        for (int index : List.of(1, 2)) {
            JsonNode scenario = stages.get(index);
            String originalLabel = (index + 1) + "-" + scenario.required("id").asText();
            String label = (index + 3) + "-" + scenario.required("id").asText() + "-precision";
            JsonNode original = JSON.readTree(RUN.resolve(originalLabel + "-snapshot.json").toFile());
            long version = original.at("/summary/requirementVersionId").asLong();
            int before = seenCompletions.size();
            JsonNode job = post(requirementPath + "/analyses", Map.of("provider", "CUSTOM_OPENAI",
                    "analysisLanguage", "de", "maxArchitectureNodes", 30, "requirementVersionId", version,
                    "idempotencyKey", UUID.randomUUID().toString(),
                    "model", Map.of("scope", Map.of("taxonomyRoots", List.of("BP", "CR", "UA"), "mode", "TAXONOMIES_ONLY"))), null, 202);
            JsonNode terminal = waitForSnapshot(projectPath + "/analysis-jobs/" + job.required("id").asText());
            save(label + "-job.json", terminal);
            String id = terminal.at("/items/0/snapshotId").asText();
            assertThat(id).isNotBlank().isNotEqualTo(original.at("/summary/id").asText());
            String snapshotPath = projectPath + "/snapshots/" + id;
            JsonNode snapshot = get(snapshotPath);
            assertThat(snapshot.at("/summary/requirementVersionId").asLong()).isEqualTo(version);
            assertThat(snapshot.at("/summary/promptFingerprint").asText()).isNotEqualTo(original.at("/summary/promptFingerprint").asText());
            save(label + "-snapshot.json", snapshot);
            JsonNode analysis = snapshot.required("analysis");
            assertFreshCompletions(scenario.required("requirement").asText(), "requirement-node-assessment-v3");
            save(label + "-qa.json", RequirementEvidenceReferenceQa.evaluate(reference, scenario, analysis));
            saveSourceFidelity(label, reference, scenario, analysis);
            for (String format : List.of("json", "html", "docx")) {
                byte[] bytes = request("GET", snapshotPath + "/decision-report/" + format + "?language=de", null, null, 200);
                Files.write(RUN.resolve(label + "-decision-report." + format), bytes);
                verifyExport(format, bytes, analysis, scenario.required("requirement").asText());
            }
            comparisons.add(Map.of("case", scenario.required("id").asText(), "requirementVersionId", version,
                    "beforeSnapshotId", original.at("/summary/id").asText(), "afterSnapshotId", id,
                    "freshModelCalls", seenCompletions.size() - before, "policy", "requirement-node-assessment-v3"));
            save("precision-comparisons.json", comparisons);
            original = null;
            snapshot = null;
            analysis = null;
            terminal = null;
            job = null;
            if (!DEFER_BROWSER) captureSnapshot(projectId, requirementId, id, label, index);
        }
        for (int index = 0; index < stages.size(); index++) {
            String label = (index + 1) + "-" + stages.get(index).required("id").asText();
            JsonNode original = JSON.readTree(RUN.resolve(label + "-snapshot.json").toFile());
            assertThat(get(projectPath + "/snapshots/" + original.at("/summary/id").asText())).isEqualTo(original);
        }
        assertThat(get(requirementPath).required("currentVersionId").asLong()).isEqualTo(activeVersion);
        save("post-fix-verification.json", Map.of("occurredAt", java.time.Instant.now().toString(),
                "comparisons", comparisons, "originalSnapshotsUnchanged", 3,
                "requirementVersionUnchanged", activeVersion, "allRecordedFreshCompletions", seenCompletions.size(),
                "browserChecksDeferred", DEFER_BROWSER));
    }

    /** A separate ordinary app invocation keeps Chrome away from report-generation buffers in small executors. */
    private void verifyBrowserOnly(JsonNode reference) throws Exception {
        var allowed = Map.of("3-operations-process", 2, "4-storage-service-precision", 1, "5-operations-process-precision", 2);
        Integer index = allowed.get(BROWSER_ONLY_LABEL);
        if (!RECOVER_FINAL || index == null || DEFER_BROWSER || PRECISION_RECHECK)
            throw new IllegalArgumentException("Browser-only verification needs an explicit retained stage and no inference flags");
        JsonNode recordedSession = JSON.readTree(RUN.resolve("session.json").toFile());
        assertThat(recordedSession.required("sourceReference")).isEqualTo(reference);
        assertThat(recordedSession.required("model").asText()).isEqualTo(model);
        JsonNode job = JSON.readTree(RUN.resolve(BROWSER_ONLY_LABEL + "-job.json").toFile());
        String snapshot = job.at("/items/0/snapshotId").asText();
        assertThat(snapshot).isNotBlank();
        captureSnapshot(recordedSession.required("projectId").asLong(), recordedSession.required("requirementId").asLong(),
                snapshot, BROWSER_ONLY_LABEL + "-verified", index);
        save(BROWSER_ONLY_LABEL + "-browser-verification.json", Map.of("snapshotId", snapshot, "passed", true,
                "occurredAt", java.time.Instant.now().toString(), "newModelCalls", 0,
                "method", "Native Chrome against the real persisted application in a fresh browser-only invocation"));
    }

    private void saveSourceFidelity(String label, JsonNode reference, JsonNode scenario, JsonNode analysis) throws Exception {
        Path review = RUN.resolve(label + "-semantic-review.json");
        save(label + "-semantic-review-template.json", RequirementEvidenceSourceFidelityQa.reviewTemplate(reference, scenario, analysis));
        save(label + "-source-fidelity.json", RequirementEvidenceSourceFidelityQa.evaluate(reference, scenario, analysis,
                Files.isRegularFile(review) ? JSON.readTree(review.toFile()) : null));
    }

    private static JsonNode operatorProposal(JsonNode proposal) {
        // The API retains the full immutable baseline. Operator decisions need its identity and current questions;
        // avoid keeping three copies of the complete catalogue in every test-side response and review file.
        var baseline = (ObjectNode) proposal.required("baseline");
        baseline.remove("snapshotPayload");
        baseline.remove("frozenContext");
        baseline.put("payloadOmittedInOperatorView", true);
        return proposal;
    }

    private void captureSnapshot(long projectId, long requirementId, String snapshotId, String label, int index) throws Exception {
        // This opt-in runner cohosts Spring and Chrome. Reclaim completed JSON decode buffers before Chrome starts.
        System.gc();
        try (var browser = BrowserSession.open(port, RUN.resolve("downloads"))) {
            var driver = browser.driver();
            driver.manage().window().setSize(new Dimension(1440, 1050));
            var wait = new WebDriverWait(driver, Duration.ofSeconds(45));
            // Authenticate the real deep link so login does not start an unrelated heavyweight landing page.
            driver.get(browser.origin() + "/projects/" + projectId + "/requirements/" + requirementId
                    + "?lang=de&snapshot=" + snapshotId);
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.name("username"))).sendKeys("admin");
            driver.findElement(By.name("password")).sendKeys(RequirementEvidenceLiveSessionTest.PASSWORD);
            driver.findElement(By.cssSelector("form")).submit();
            wait.until(d -> !d.getCurrentUrl().contains("/login"));
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-requirement-evidence]")));
            var evidence = driver.findElement(By.cssSelector("[data-requirement-evidence]"));
            wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("[data-requirement-evidence] details[data-evidence-node]")));
            driver.executeScript("arguments[0].scrollIntoView({block:'start',behavior:'instant'});", evidence);
            Files.write(RUN.resolve(label + "-overview.png"), driver.getScreenshotAs(OutputType.BYTES));
            var list = evidence.findElements(By.cssSelector("details.requirement-evidence-list"));
            if (!list.isEmpty() && list.getFirst().getDomAttribute("open") == null) {
                var summary = list.getFirst().findElement(By.cssSelector("summary"));
                driver.executeScript("arguments[0].scrollIntoView({block:'center',behavior:'instant'});", summary);
                summary.click();
            }
            String preferred = index == 2 ? "BP-1503" : "CR-1091";
            var nodes = evidence.findElements(By.cssSelector("details[data-evidence-node='" + preferred + "']"));
            if (nodes.isEmpty() && index == 2) nodes = evidence.findElements(By.cssSelector("details[data-evidence-node='BP-2009']"));
            if (nodes.isEmpty()) nodes = evidence.findElements(By.cssSelector("details[data-evidence-node]"));
            if (!nodes.isEmpty()) {
                evidence = nodes.getFirst();
                if (evidence.getDomAttribute("open") == null) {
                    var summary = evidence.findElement(By.cssSelector("summary"));
                    driver.executeScript("arguments[0].scrollIntoView({block:'center',behavior:'instant'});", summary);
                    summary.click();
                }
                driver.executeScript("arguments[0].scrollIntoView({block:'start',behavior:'instant'});", evidence);
            }
            Files.write(RUN.resolve(label + "-desktop.png"), driver.getScreenshotAs(OutputType.BYTES));
            driver.manage().window().setSize(new Dimension(430, 932));
            driver.executeScript("arguments[0].scrollIntoView({block:'start',behavior:'instant'});", evidence);
            Files.write(RUN.resolve(label + "-narrow.png"), driver.getScreenshotAs(OutputType.BYTES));
            assertThat(((Number) driver.executeScript("return document.documentElement.scrollWidth - window.innerWidth")).intValue())
                    .as("No horizontal page overflow").isLessThanOrEqualTo(2);
            driver.manage().window().setSize(new Dimension(1440, 1050));
        }
    }

    private static void verifyExport(String format, byte[] bytes, JsonNode analysis, String requirement) throws Exception {
        if (format.equals("json")) {
            JsonNode report = JSON.readTree(bytes);
            assertThat(report.required("requirement").asText()).isEqualTo(requirement);
            assertThat(report.required("nodeAssessments")).isEqualTo(analysis.required("nodeAssessments"));
            return;
        }
        String text;
        if (format.equals("docx")) {
            try (var document = new XWPFDocument(new ByteArrayInputStream(bytes)); var extractor = new XWPFWordExtractor(document)) {
                text = extractor.getText();
            }
        } else {
            text = org.springframework.web.util.HtmlUtils.htmlUnescape(new String(bytes, StandardCharsets.UTF_8).replaceAll("<[^>]+>", " "));
        }
        String normalized = text.replaceAll("\\s+", " ");
        assertThat(normalized).contains("Anforderungsbeiträge und Beleglage");
        for (var claims : analysis.required("nodeAssessments")) {
            for (var claim : claims) {
                for (String field : List.of("requirementQuote", "condition", "rationale", "question")) {
                    String value = claim.path(field).asText();
                    if (!value.isBlank()) assertThat(normalized).as(format + " preserves " + field)
                            .contains(value.replaceAll("\\s+", " "));
                }
            }
        }
    }

    private JsonNode waitForSnapshot(String jobPath) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode job = get(jobPath);
            JsonNode item = job.path("items").get(0);
            if (item != null && !item.path("snapshotId").asText().isBlank()) return job;
            if (item != null) assertThat(item.path("status").asText()).as(job.toPrettyString()).isNotIn("FAILED", "CANCELLED");
            Thread.sleep(750);
        }
        throw new AssertionError("Live analysis did not retain a snapshot before deadline: " + jobPath);
    }

    private JsonNode waitForDecision(Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path)) return JSON.readTree(path.toFile());
            Thread.sleep(500);
        }
        throw new AssertionError("Review the actual questions and atomically publish " + path);
    }

    private void assertFreshCompletions(String requirement) throws Exception {
        assertFreshCompletions(requirement, null);
    }

    private void assertFreshCompletions(String requirement, String policy) throws Exception {
        var fresh = new HashSet<String>();
        var deliveredReceipts = new LinkedHashMap<String, JsonNode>();
        String spool = System.getenv("TAXONOMY_RELAY_SPOOL");
        if (spool != null && !spool.isBlank()) {
            try (var receipts = Files.list(Path.of(spool).resolve("receipts"))) {
                for (Path file : receipts.filter(path -> path.toString().endsWith(".json")).toList()) {
                    JsonNode receipt = JSON.readTree(file.toFile());
                    if (receipt.path("outcome").asText().equals("delivered"))
                        deliveredReceipts.put(receipt.required("completionId").asText(), receipt);
                }
            }
        }
        try (var files = Files.list(RUN.resolve("provider-responses"))) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("sha256-[0-9a-f]{64}\\.json")).toList()) {
                JsonNode entry = JSON.readTree(file.toFile());
                if (!entry.path("promptText").asText().contains(requirement)) continue;
                if (policy != null && !entry.path("promptText").asText().contains(policy)) continue;
                assertThat(entry.required("provider").asText()).isEqualTo("CUSTOM_OPENAI");
                JsonNode envelope = JSON.readTree(entry.required("responseBody").asText());
                assertThat(envelope.required("model").asText()).isEqualTo(model);
                assertThat(envelope.required("id").asText()).isNotBlank();
                fresh.add(envelope.required("id").asText());
                if (spool != null && !spool.isBlank()) {
                    JsonNode receipt = deliveredReceipts.get(envelope.required("id").asText());
                    assertThat(receipt).as("Recorded completion has its actual successful relay receipt").isNotNull();
                    assertThat(receipt.required("provenance").asText()).isEqualTo("assistant-relay");
                    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                            entry.required("responseBody").asText().getBytes(StandardCharsets.UTF_8))))
                            .isEqualTo(receipt.required("deliveredSha256").asText());
                }
            }
        }
        requireFreshCompletionIds(fresh, seenCompletions);
        seenCompletions.addAll(fresh);
    }

    static void requireFreshCompletionIds(Set<String> fresh, Set<String> previouslyObserved) {
        assertThat(fresh).as("Actual recorded provider responses for this exact version").isNotEmpty();
        assertThat(fresh.stream().anyMatch(previouslyObserved::contains))
                .as("A completion from an earlier requirement version must not be reused").isFalse();
    }

    private JsonNode get(String path) throws Exception { return JSON.readTree(request("GET", path, null, null, 200)); }
    private JsonNode post(String path, Object body, Long revision, int status) throws Exception {
        return JSON.readTree(request("POST", path, body, revision, status));
    }
    private byte[] request(String method, String path, Object body, Long revision, int expected) throws Exception {
        var builder = session.request(port, method, path).timeout(Duration.ofMinutes(3));
        if (revision != null) builder.header("If-Match", "\"" + revision + "\"");
        builder.header("Content-Type", "application/json");
        var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as(() -> method + " " + path + ": " + new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(expected);
        if (path.contains("/snapshots/") && path.contains("/decision-report/")) {
            String expectedSnapshot = path.substring(path.indexOf("/snapshots/") + 11, path.indexOf("/decision-report/"));
            assertThat(response.headers().firstValue("X-Taxonomy-Snapshot-Id")).contains(expectedSnapshot);
        }
        return response.body();
    }
    private void save(String name, Object value) throws Exception { JSON.writerWithDefaultPrettyPrinter().writeValue(RUN.resolve(name).toFile(), value); }
    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + key + " for the opt-in live session");
        return value;
    }
}
