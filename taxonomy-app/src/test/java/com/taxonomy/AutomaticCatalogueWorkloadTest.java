package com.taxonomy;

import com.taxonomy.acceptance.ScenarioJsonFingerprint;
import com.taxonomy.acceptance.ScenarioLlmConfiguration;
import com.taxonomy.acceptance.ScenarioLlmPlayback;
import com.taxonomy.acceptance.ScenarioScoringQuality;
import com.taxonomy.acceptance.ScenarioRelationQuality;
import com.taxonomy.analysis.relations.CatalogueWorkloadShadow;
import com.taxonomy.analysis.service.AnalysisLanguageContext;
import com.taxonomy.analysis.usecase.AdmittedCatalogue;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.FrozenCatalogueContext;
import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.dto.RelationSearchModel.Phase;
import com.taxonomy.analysis.relations.RelationSearchBudgetSettings;
import com.taxonomy.analysis.service.AnalysisMemoryGuard;
import com.taxonomy.analysis.usecase.AnalysisAdmissionModelPort;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.support.ProcessTestDiagnostics;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** One authenticated automatic analysis through the real local portfolio worker. */
class AutomaticCatalogueWorkloadTest {
    private static final String MODE = "catalogue.workload.mode";
    private static final String OUTPUT = "catalogue.workload.output";
    private static final String STORAGE = "catalogue.workload.storage";
    private static final String PHASE = "X-Catalogue-Workload-Phase";
    private static final String COMPLETE = "AUTOMATIC_CATALOGUE_WORKLOAD_DONE";
    private static final String WORKBOOK = "com.taxonomy.catalog.service.TaxonomyService.readWorkbookNodes";
    private static final Set<String> ALL_ROOTS = Set.of("BP", "BR", "CI", "CO", "CP", "CR", "IP", "UA");
    private static final Set<String> TERMINAL = Set.of("SUCCESS", "PARTIAL", "FAILED", "ERROR", "CANCELLED");
    private static final ObjectMapper JSON = new ObjectMapper();

    private enum Mode {
        SMOKE("smoke", 1), SEED("seed", 128), DEFAULT_FULL("default-full", 128), BOUNDED_FULL("bounded-full", 16);
        final String value;
        final int sources;
        Mode(String value, int sources) { this.value = value; this.sources = sources; }
        static Mode chosen() {
            String requested = System.getProperty(MODE, "smoke");
            return Arrays.stream(values()).filter(mode -> mode.value.equals(requested)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown catalogue workload mode " + requested));
        }
        boolean full() { return this == DEFAULT_FULL || this == BOUNDED_FULL; }
        AnalysisScope scope() { return this == SMOKE ? new AnalysisScope(Set.of("BR"), AnalysisMode.FULL) : AnalysisScope.full(); }
        Duration processLimit() { return Duration.ofMinutes(full() ? 12 : 4); }
    }

    @Test void runsOneLocalAutomaticAnalysis(@TempDir Path temp) throws Exception {
        Mode mode = Mode.chosen();
        Path output = mode == Mode.SMOKE && System.getProperty(OUTPUT) == null ? temp
                : Path.of(System.getProperty(OUTPUT, "../target/automatic-catalogue-workload")).toAbsolutePath().resolve(mode.value);
        Files.createDirectories(output);
        Path state = output.resolve("state");
        Files.createDirectory(state);
        if (mode.full()) {
            Path seed = Path.of(System.getProperty("catalogue.workload.seed", output.getParent().resolve("seed").toString()));
            JsonNode expected = JSON.readTree(seed.resolve("closed-seed.json").toFile());
            assertThat(JSON.readTree(JSON.writeValueAsBytes(closedDatabase(seed.resolve("state"))))).isEqualTo(expected);
            try (var files = Files.list(seed.resolve("state"))) {
                for (Path file : files.filter(path -> path.getFileName().toString().startsWith("database.")).toList())
                    Files.copy(file, state.resolve(file.getFileName()));
            }
            assertThat(JSON.readTree(JSON.writeValueAsBytes(closedDatabase(state)))).isEqualTo(expected);
            Files.copy(seed.resolve("identity.json"), output.resolve("seed-identity.json"));
        }
        Path log = output.resolve("application-process.log");
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // Use the normal test JVM's ceiling and retain any existing startup instrumentation.
        command.add("-Xmx" + Runtime.getRuntime().maxMemory());
        command.add("-javaagent:" + Path.of(ByteBuddyAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(arg -> arg.startsWith("-javaagent:") && arg.contains("jacoco") || arg.startsWith("-XX:ActiveProcessorCount="))
                .forEach(command::add);
        command.add("-D" + MODE + "=" + mode.value);
        command.add("-D" + OUTPUT + "=" + output);
        command.add("-D" + STORAGE + "=" + state);
        String plugins = System.getProperty("taxonomy.plugins.directory");
        if (plugins != null) command.add("-Dtaxonomy.plugins.directory=" + plugins);
        String sourceRevision = System.getProperty("catalogue.workload.sourceRevision");
        if (sourceRevision != null) command.add("-Dcatalogue.workload.sourceRevision=" + sourceRevision);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(AutomaticCatalogueWorkloadTest.class.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean finished = process.waitFor(mode.processLimit().toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                save(output.resolve("process-stop.json"), Map.of("status", "INCOMPLETE", "reason", "child deadline",
                        "deadlineSeconds", mode.processLimit().toSeconds()));
                throw new AssertionError("Automatic workload exceeded its child deadline. " + ProcessTestDiagnostics.describe(log));
            }
            ProcessTestDiagnostics.assertCompleted(process.exitValue(), log, COMPLETE);
            if (mode == Mode.SEED) save(output.resolve("closed-seed.json"), closedDatabase(state));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static Map<String, Object> closedDatabase(Path state) throws Exception {
        var files = new TreeMap<String, Object>();
        try (var listing = Files.list(state)) {
            for (Path file : listing.filter(path -> path.getFileName().toString().startsWith("database.")).toList()) {
                String name = file.getFileName().toString();
                assertThat(List.of(".lck", ".log", ".new", ".tmp").stream().anyMatch(name::endsWith)).as(name).isFalse();
                var digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
                files.put(name, Map.of("length", Files.size(file), "sha256", HexFormat.of().formatHex(digest.digest())));
            }
        }
        assertThat(files).containsKeys("database.properties", "database.data", "database.script");
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(state.resolve("database.properties"))) { properties.load(reader); }
        assertThat(properties.getProperty("modified")).isEqualTo("no");
        return files;
    }

    public static void main(String[] args) throws Exception {
        var context = new TestContextManager(Application.class);
        var application = new Application();
        Path output = Path.of(System.getProperty(OUTPUT));
        long started = System.nanoTime();
        try (var probe = AutomaticCatalogueWorkloadProbe.install()) {
            try {
                try (var startup = AutomaticCatalogueWorkloadProbe.enter("startup")) {
                    context.beforeTestClass();
                    context.prepareTestInstance(application);
                }
                application.run(probe);
            } finally {
                context.afterTestClass();
                save(output.resolve("process.json"), Map.of("applicationWallNanos", System.nanoTime() - started,
                        "probe", probe.report()));
            }
            assertThat(probe.errors()).as("Narrow bytecode transformations").isEmpty();
        }
        System.out.println(COMPLETE);
    }

    @SpringBootTest(classes = TaxonomyApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"taxonomy.features.multi-repository-api.enabled=true",
                    "taxonomy.analysis.transport.mode=local", "embedding.enabled=false", "embedding.allow-download=false",
                    "llm.mock=false", "llm.replay=false", "llm.record=false", "llm.provider=CUSTOM_OPENAI",
                    "custom.llm.url=" + ScenarioLlmConfiguration.URL, "custom.llm.model=scenario-fixture",
                    "taxonomy.admin-password=" + ScenarioArchitectureAcceptanceTest.PASSWORD,
                    "taxonomy.security.require-password-change=false", "scenario.compact-playback=true",
                    "taxonomy.portfolio.snapshot.packed-writes=false"})
    @Import({ScenarioLlmConfiguration.class, ObservationConfiguration.class})
    @ActiveProfiles("hsqldb-file")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    static class Application {
        @DynamicPropertySource static void storage(DynamicPropertyRegistry registration) {
            Path state = Path.of(System.getProperty(STORAGE));
            registration.add("TAXONOMY_HSQLDB_FILE_PATH", () -> state.resolve("database").toString());
            registration.add("TAXONOMY_SEARCH_DIRECTORY_ROOT", () -> state.resolve("index").toString());
            if (Mode.chosen() == Mode.SMOKE)
                registration.add("taxonomy.analysis.relations.hierarchical.max-sources", () -> Mode.SMOKE.sources);
        }

        @LocalServerPort int port;
        @Autowired ScenarioLlmPlayback playback;
        @Autowired CatalogueSourceJournal sources;
        @Autowired CatalogueSnapshotService snapshots;
        @Autowired TaxonomyService taxonomy;
        @Autowired AtomicReference<ModelPreflight> modelPreflight;
        @Autowired RelationSearchBudgetSettings budgets;
        @Autowired Environment environment;
        final Path output = Path.of(System.getProperty(OUTPUT));
        final Mode mode = Mode.chosen();
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
        final ScenarioHttpSession session = new ScenarioHttpSession(http, cookies, JSON, "admin", ScenarioArchitectureAcceptanceTest.PASSWORD);
        final Map<String, Long> clientNanos = new TreeMap<>();
        final Map<String, Integer> clientRequests = new TreeMap<>();
        String workspace;

        void run(AutomaticCatalogueWorkloadProbe probe) throws Exception {
            try {
                runAnalysis(probe);
            } finally {
                http.close();
                save(output.resolve("http.json"), Map.of("elapsedNanos", clientNanos, "requests", clientRequests));
            }
        }

        private void runAnalysis(AutomaticCatalogueWorkloadProbe probe) throws Exception {
            JsonNode fixture = playback.fixture();
            JsonNode workspaceView = bootstrap();
            workspace = workspaceView.path("workspaceId").asText();
            assertThat(workspace).isNotBlank();
            assertThat(workspaceView.path("username").asText()).isEqualTo("admin");
            assertThat(workspaceView.path("provisioningStatus").asText()).isEqualTo("READY");
            assertThat(workspaceView.path("archived").asBoolean()).isFalse();
            assertThat(workspaceView.path("shared").asBoolean()).isFalse();
            if (mode == Mode.BOUNDED_FULL) configureBoundedSources();
            verifySettings();
            JsonNode seed = mode.full() ? JSON.readTree(output.resolve("seed-identity.json").toFile()) : null;
            String requirementPath = seed == null ? createRequirement(fixture) : seed.path("requestPath").asText();
            String projectPath = requirementPath.substring(0, requirementPath.indexOf("/requirements/"));
            JsonNode requirement = json("setup", "GET", requirementPath, null, 200, true);
            assertThat(requirement.at("/currentVersion/text").asText()).isEqualTo(fixture.at("/requirement/text").asText());
            assertThat(requirement.path("currentAnalysisSnapshotId").isNull()).isTrue();
            ModelPreflight model = Objects.requireNonNull(modelPreflight.get(), "Authenticated model observation");
            var catalogue = sources.findRevision(model.full().analysisCatalogueRevision()).orElseThrow();
            assertThat(model.full().modelSha256()).matches("[0-9a-f]{64}");
            assertThat(model.full().commit()).matches("[0-9a-f]{40}");
            assertThat(model.full().rootCodes()).isEqualTo(ALL_ROOTS);
            assertThat(model.full().branch()).isEqualTo(workspaceView.path("currentBranch").asText());
            assertThat(model.selected().commit()).isEqualTo(model.full().commit());
            assertThat(model.selected().modelSha256()).isEqualTo(model.full().modelSha256());
            assertThat(model.selected().analysisCatalogueRevision()).isEqualTo(model.full().analysisCatalogueRevision());
            var sourceKinds = new HashMap<String, String>();
            sourceKinds.put(catalogue.workbook().sha256(), "workbook");
            if (catalogue.overlay().sha256() != null) sourceKinds.put(catalogue.overlay().sha256(), "overlay");
            probe.sources(sourceKinds);
            var identity = JSON.createObjectNode().put("fixtureSha256", ScenarioJsonFingerprint.sha256(fixture))
                    .put("workspace", workspace).put("requestPath", requirementPath)
                    .put("version", requirement.path("currentVersionId").asLong())
                    .put("versionNumber", requirement.at("/currentVersion/versionNumber").asInt())
                    .put("requirementHash", requirement.at("/currentVersion/contentHash").asText());
            identity.set("fullModel", JSON.readTree(JSON.writeValueAsBytes(model.full())));
            identity.set("catalogueProvenance", JSON.valueToTree(catalogue));
            if (seed != null) assertThat(JSON.readTree(JSON.writeValueAsBytes(identity))).as("Selected full model, requirement and retained generation match closed seed").isEqualTo(seed);
            save(output.resolve("identity.json"), identity);
            if (mode == Mode.SEED) {
                assertThat(playback.callCount()).isZero();
                return;
            }

            var report = JSON.createObjectNode().put("state", "INCOMPLETE");
            report.set("schedulerShadow", JSON.valueToTree(Map.of("state", "UNAVAILABLE",
                    "reason", mode == Mode.SMOKE ? "BR smoke mode" : "Saved analysis not inspected")));
            saveProgress(probe, report);
            long submitted = System.nanoTime();
            long lastCheckpoint = submitted;
            try {
                JsonNode admitted = json("submit", "POST", requirementPath + "/analyses",
                        Map.of("provider", "CUSTOM_OPENAI", "maxArchitectureNodes", 50, "model", Map.of(
                                "expectedCommit", model.selected().commit(), "scope", mode.scope())), 202, false);
                String jobId = admitted.path("id").asText();
                assertThat(jobId).isNotBlank();
                String jobPath = projectPath + "/analysis-jobs/" + jobId;
                JsonNode actualModel = json("jobModel", "GET", jobPath + "/model", null, 200, false);
                assertThat(JSON.treeToValue(actualModel, com.taxonomy.dto.AnalysisModelBinding.class)).isEqualTo(model.selected());
                report.set("admittedModel", actualModel);
                JsonNode job;
                long pollDeadline = submitted + mode.processLimit().minusSeconds(45).toNanos();
                do {
                    job = json("poll", "GET", jobPath, null, 200, false);
                    report.set("lastJob", job);
                    if (System.nanoTime() - lastCheckpoint >= Duration.ofSeconds(8).toNanos()) {
                        saveProgress(probe, report);
                        lastCheckpoint = System.nanoTime();
                    }
                    if (TERMINAL.contains(job.path("status").asText())) break;
                    if (System.nanoTime() >= pollDeadline) throw new AssertionError("Nonterminal analysis at poll deadline: " + job);
                    Thread.sleep(1000);
                } while (true);
                report.put("submitToTerminalNanos", System.nanoTime() - submitted);
                JsonNode item = job.at("/items/0");
                String snapshotId = item.path("snapshotId").asText();
                if (snapshotId.isBlank()) throw new AssertionError("Terminal job has no saved snapshot: " + job);
                JsonNode snapshot = json("snapshot", "GET", projectPath + "/snapshots/" + snapshotId, null, 200, false);
                JsonNode analysis = snapshot.path("analysis");
                JsonNode search = analysis.path("relationSearchReport");
                report.set("coverage", analysis.path("analysisCoverage"));
                report.set("budget", search.path("budget"));
                report.set("warnings", search.path("warnings"));
                report.put("stopReason", search.path("stopReason").asText());
                report.put("analysisStatus", analysis.path("status").asText());
                report.put("recoveryState", analysis.at("/recovery/state").asText());
                requireOwnership(probe, job, item, snapshot, model, identity);
                requireCounters(probe);
                saveNormalized(snapshot);
                if (mode.full()) inspectFull(probe, report, fixture, snapshot, model, identity);
                assertThat(search.at("/budget/automatic").asBoolean()).isTrue();
                assertThat(search.at("/budget/callsPerSlice").asInt()).isEqualTo(256);
                assertThat(search.at("/budget/maxTotalCalls").asInt()).isEqualTo(4096);
                assertThat(search.at("/budget/sourcesPerSlice").asInt()).isEqualTo(mode.sources);
                assertThat(search.at("/budget/sourceCohortSize").asInt()).isEqualTo(mode.sources);
                assertThat(search.at("/budget/workItemsPerSlice").asInt()).isEqualTo(4096);
                assertThat(search.at("/budget/spentCalls").asInt()).isBetween(1, 4096);
                assertThat(playback.failures()).isEmpty();
                if (mode == Mode.SMOKE) verifySmoke(fixture, analysis, search);
                else verifyFull(fixture, analysis, search);
                report.put("state", "COMPLETE");
            } catch (Exception | AssertionError failure) {
                report.put("failure", failure.toString());
                throw failure;
            } finally { saveProgress(probe, report); }
        }

        private void configureBoundedSources() throws Exception {
            String key = "analysis.relations.max-sources";
            assertThat(budgets.snapshot()).isEqualTo(new RelationSearchBudgetSettings.Snapshot(256, 4096, true, 128, 4096));
            var before = (ObjectNode) json("setupBudget", "GET", "/api/preferences", null, 200, false);
            assertThat(before.path(key).asInt()).isEqualTo(128);
            JsonNode after = json("setupBudget", "PUT", "/api/preferences", Map.of(key, mode.sources), 200, false);
            assertThat(after).as("The dedicated copied database changes only its source allowance")
                    .isEqualTo(before.deepCopy().put(key, mode.sources));
        }

        private void verifySettings() throws Exception {
            var configured = budgets.snapshot();
            assertThat(configured).isEqualTo(new RelationSearchBudgetSettings.Snapshot(256, 4096, true, mode.sources, 4096));
            var guards = new TreeMap<String, Integer>();
            Map.of("warning-percent", 80, "stop-percent", 92, "minimum-headroom-mb", 16,
                    "pressure-seconds", 5, "maximum-duration-seconds", 1800).forEach((key, defaultValue) ->
                            guards.put(key, environment.getProperty("taxonomy.analysis.runtime." + key, Integer.class, defaultValue)));
            save(output.resolve("settings.json"), Map.of("mode", mode.value, "budgets", configured,
                    "heap", AnalysisMemoryGuard.heapSample(), "processors", Runtime.getRuntime().availableProcessors(),
                    "java", Runtime.version().toString(), "driver", Objects.toString(org.hsqldb.jdbc.JDBCDriver.class.getPackage().getImplementationVersion(), "unavailable"),
                    "datasource", environment.getRequiredProperty("spring.datasource.url").replace(System.getProperty(STORAGE), "<row>/state"),
                    "storagePackedWrites", environment.getRequiredProperty("taxonomy.portfolio.snapshot.packed-writes"),
                    "memoryAndTimeGuards", guards, "childDeadlineSeconds", mode.processLimit().toSeconds()));
        }

        private JsonNode bootstrap() throws Exception {
            for (int attempt = 0; attempt < 30; attempt++) {
                long start = System.nanoTime();
                var response = http.send(request("setup", "GET", "/api/workspace/current", null, false),
                        HttpResponse.BodyHandlers.ofString());
                timed("setup", start);
                var value = JSON.readTree(response.body());
                if (response.statusCode() == 200) return value;
                assertThat(response.statusCode()).isEqualTo(202);
                assertThat(value.path("state").asText()).isEqualTo("INITIAL_SOURCE_PENDING");
                Thread.sleep(1000);
            }
            throw new AssertionError("Owned workspace initial source did not become ready");
        }

        private String createRequirement(JsonNode fixture) throws Exception {
            long project = json("setup", "POST", "/api/projects", Map.of("projectKey", "CIV-FLOOD-AUTO",
                    "title", "Flood information", "description", "Source-backed automatic workload", "status", "ACTIVE"), 201, false)
                    .path("id").asLong();
            String path = "/api/projects/" + project + "/requirements";
            var requirement = fixture.path("requirement");
            long id = json("setup", "POST", path, Map.of("requirementKey", requirement.path("key").asText(),
                    "title", requirement.path("title").asText(), "text", requirement.path("text").asText(),
                    "status", "DRAFT", "priority", 80, "criticality", "HIGH", "requirementType", "FUNCTIONAL",
                    "reviewStatus", "PROPOSED"), 201, false).path("id").asLong();
            assertThat(project).isPositive();
            assertThat(id).isPositive();
            return path + "/" + id;
        }

        private void requireOwnership(AutomaticCatalogueWorkloadProbe probe, JsonNode job, JsonNode item, JsonNode snapshot,
                                      ModelPreflight model, JsonNode identity) {
            var summary = snapshot.path("summary");
            assertThat(job.path("items").size()).isEqualTo(1);
            assertThat(job.path("provider").asText()).isEqualTo("CUSTOM_OPENAI");
            assertThat(job.path("requestedBy").asText()).isEqualTo("admin");
            assertThat(job.path("workspaceId").asText()).isEqualTo(workspace);
            assertThat(item.path("attempt").asInt()).isEqualTo(1);
            assertThat(item.path("requirementVersionId").asLong()).isEqualTo(identity.path("version").asLong());
            assertThat(item.path("requirementVersionNumber")).isEqualTo(identity.path("versionNumber"));
            for (String field : List.of("requirementId", "requirementVersionId", "requirementVersionNumber"))
                assertThat(summary.path(field)).as(field).isEqualTo(item.path(field));
            assertThat(summary.path("jobId")).isEqualTo(job.path("id"));
            assertThat(summary.path("id")).isEqualTo(item.path("snapshotId"));
            assertThat(summary.path("workspaceId")).isEqualTo(job.path("workspaceId"));
            assertThat(JSON.treeToValue(snapshot.at("/analysis/modelBinding"), com.taxonomy.dto.AnalysisModelBinding.class)).isEqualTo(model.selected());
            assertThat(probe.winners()).singleElement().satisfies(winner -> {
                assertThat(winner.jobId()).isEqualTo(job.path("id").asText());
                assertThat(winner.itemId()).isEqualTo(item.path("id").asLong());
                assertThat(winner.requirementVersionId()).isEqualTo(item.path("requirementVersionId").asLong());
                assertThat(winner.attempt()).isEqualTo(1);
                assertThat(winner.continuationSnapshotId()).isNull();
            });
            String run = snapshot.at("/analysis/recovery/id").asText();
            assertThat(run).isNotBlank();
            assertThat(probe.begins()).singleElement().satisfies(begin -> {
                assertThat(begin.id()).isEqualTo(run);
                assertThat(begin.hasToken()).isTrue();
                assertThat(begin.replay()).isFalse();
                assertThat(begin.inputHash()).matches("[0-9a-f]{64}");
                assertThat(begin.owner()).isEqualTo(model.owner());
                assertThat(begin.model()).isEqualTo(model.selected());
            });
            assertThat(probe.persisted()).singleElement().satisfies(write -> {
                assertThat(write.snapshotId()).isEqualTo(item.path("snapshotId").asText());
                assertThat(write.jobId()).isEqualTo(job.path("id").asText());
                assertThat(write.requirementVersionId()).isEqualTo(item.path("requirementVersionId").asLong());
                assertThat(write.attempt()).isEqualTo(1);
                assertThat(write.username()).isEqualTo("admin");
                assertThat(write.workspace()).isEqualTo(model.owner());
                assertThat(write.recoveryId()).isEqualTo(run);
            });
        }

        private void requireCounters(AutomaticCatalogueWorkloadProbe probe) {
            assertThat(probe.errors()).isEmpty();
            assertThat(probe.count("worker", "intervals")).isEqualTo(1);
            for (String counter : List.of("winningItems", "durableBegins", "snapshotCommitted",
                    "com.taxonomy.analysis.relations.RequirementRelationSearch.searchWithSourceContinuation"))
                assertThat(probe.count("worker", counter)).as(counter).isEqualTo(1);
            for (String counter : List.of("com.taxonomy.catalog.snapshot.CatalogueSnapshotService.captureRevision",
                    "com.taxonomy.catalog.snapshot.CatalogueSnapshotService.bind",
                    "com.taxonomy.catalog.snapshot.RootCatalogueSnapshot.<init>",
                    "com.taxonomy.catalog.snapshot.FrozenCatalogueView.<init>",
                    "com.taxonomy.catalog.service.TaxonomyService.getFullTree", "digest.treeOnly", "digest.requestSignature",
                    "verifiedBytes.workbook", "verifiedBytes.overlay", "jdbc.sourceBytes.workbook", "jdbc.sourceBytes.overlay",
                    "jdbc.executeQuery", "jdbc.rowsAdvanced", "jdbc.commit", "jdbc.commitTransactionNanos",
                    "jdbc.compatibilityExecutions",
                    "jdbc.capacityCountExecutions", "exactKey.absent", "compatibleQuestion.absent",
                    "progress.snapshot", "progress.publish", "progress.sourceVisits", "allocatedBytes"))
                assertThat(probe.count("worker", counter)).as(counter).isPositive();
            long parse = probe.count("worker", WORKBOOK) + probe.count("startup", WORKBOOK) + probe.count("http.setup", WORKBOOK);
            assertThat(parse).as("Observed actual workbook parsing in cold startup, setup or worker").isPositive();
            assertThat(probe.count("worker", "provider.HTTP")).isEqualTo(playback.callCount());
            assertThat(probe.count("worker", "provider.RESPONSE")).isEqualTo(playback.callCount());
            assertThat(probe.count("worker", "provider.usageUnavailable")).isEqualTo(playback.callCount());
            assertThat(probe.count("worker", "provider.inDatabaseTransaction")).isZero();
            if (mode != Mode.DEFAULT_FULL) {
                assertThat(probe.count("worker", "privateSearchPasses")).isGreaterThanOrEqualTo(2);
                assertThat(probe.count("worker", "com.taxonomy.analysis.relations.RequirementRelationSearch$Budget.nextSourceCohort")).isPositive();
                assertThat(probe.count("worker", "cohort.2.acceptedPrefix.prepares")).isPositive();
                assertThat(probe.count("worker", "cohort.2.acceptedPrefix.payloadBindings")).isPositive();
            }
            var options = probe.options();
            assertThat(options.limits().maxDepth()).isEqualTo(8);
            assertThat(options.limits().batchSize()).isEqualTo(3);
            assertThat(options.maxPromptCharacters()).isEqualTo(16000);
        }

        private void verifySmoke(JsonNode fixture, JsonNode analysis, JsonNode search) {
            var expected = new TreeMap<String, JsonNode>();
            var scoringRules = new ArrayList<String>();
            for (JsonNode rule : fixture.path("replies")) {
                if (!rule.path("keys").valueStream().allMatch(key -> key.asText().equals("BR") || key.asText().startsWith("BR-"))) continue;
                scoringRules.add(rule.path("id").asText());
                rule.path("answers").properties().forEach(answer -> expected.put(answer.getKey(), answer.getValue()));
            }
            var raw = analysis.path("rawScores");
            assertThat(raw.propertyNames()).containsExactlyInAnyOrderElementsOf(expected.keySet());
            expected.forEach((key, answer) -> {
                assertThat(raw.path(key).decimalValue()).as(key).isEqualByComparingTo(answer.path("score").decimalValue());
                assertThat(analysis.path("reasons").path(key).asText()).isEqualTo(answer.path("reason").asText());
            });
            var assessments = search.path("sources").valueStream().toList();
            assertThat(assessments).extracting(source -> source.at("/node/id").asText())
                    .containsExactlyInAnyOrder("BR-1228", "BR-1094");
            for (JsonNode assessment : assessments) {
                String code = assessment.at("/node/id").asText();
                var authored = fixture.at("/relationPlayback/sourceRejections").valueStream()
                        .filter(rejection -> code.equals(rejection.path("nodeId").asText()))
                        .findFirst().orElseThrow();
                assertThat(assessment.path("contributions").size()).isZero();
                assertThat(assessment.path("question").asText()).isEmpty();
                assertThat(assessment.path("rationale").asText()).isEqualTo(authored.path("rationale").asText());
            }
            assertThat(search.at("/result/edges").size()).isZero();
            assertThat(search.at("/result/unfinished").size()).isZero();
            assertThat(analysis.at("/architectureView/includedRelationships").size()).isZero();
            assertThat(search.path("stopReason").asText()).isEmpty();
            assertThat(search.path("warnings").size()).isZero();
            var physical = playback.acceptedCalls();
            assertThat(physical.stream().filter(call -> !call.ruleId().startsWith("relation:")).map(ScenarioLlmPlayback.AcceptedCall::ruleId))
                    .containsExactlyInAnyOrderElementsOf(scoringRules);
            assertThat(physical.stream().filter(call -> call.ruleId().startsWith("relation:"))).hasSize(2);
        }

        private void inspectFull(AutomaticCatalogueWorkloadProbe probe, ObjectNode report, JsonNode fixture,
                                 JsonNode snapshot, ModelPreflight model, JsonNode identity) throws Exception {
            var input = probe.searchInput();
            var language = AnalysisLanguage.fromValue(snapshot.at("/analysis/analysisLanguage").asText(null));
            var snapshotId = snapshot.at("/summary/id").asText();
            FrozenCatalogueContext.Scope view;
            try (var inspection = AutomaticCatalogueWorkloadProbe.enter("shadowCatalogue")) {
                view = AdmittedCatalogue.open(snapshots, model.selected(), model.owner());
            }
            try (view; var locale = AnalysisLanguageContext.open(language)) {
                report.set("localOpportunities", JSON.valueToTree(opportunities(probe)));
                report.set("schedulerShadow", JSON.valueToTree(CatalogueWorkloadShadow.observe(taxonomy,
                        fixture.at("/requirement/text").asText(), input.scores(), input.options(), input.rules(),
                        input.provider(), language, snapshotId, playback.acceptedCalls())));
            }
            var run = JSON.createObjectNode().put("scenario", fixture.path("id").asText()).put("snapshotId", snapshotId)
                    .put("fixtureSha256", identity.path("fixtureSha256").asText()).put("llmCalls", playback.callCount())
                    .put("sourceRevision", System.getProperty("catalogue.workload.sourceRevision", "local-working-tree"));
            ScenarioRelationQuality.verifyAutomatic(output, snapshot, run);
        }

        private Map<String, Object> opportunities(AutomaticCatalogueWorkloadProbe probe) {
            var counts = new TreeMap<String, Long>();
            int maximum = probe.options().maxPromptCharacters();
            var concrete = new NavigateCensus(false, maximum);
            var trueLeaf = new NavigateCensus(true, maximum);
            var view = Objects.requireNonNull(FrozenCatalogueContext.current());
            var leaves = new HashMap<String, Boolean>();
            for (var pair : probe.siblings()) {
                countPair(counts, "all", pair, 0, maximum);
                boolean verify = pair.first().phase() == Phase.VERIFY;
                if (verify) {
                    countPair(counts, "verify", pair, 4, maximum);
                    continue;
                }
                countPair(counts, "concreteNavigate", pair, 0, maximum);
                concrete.record(pair, 0);
                var nodes = new ArrayList<>(pair.first().candidates());
                nodes.addAll(pair.second().candidates());
                if (nodes.stream().anyMatch(node -> !leaves.computeIfAbsent(node.id(), id -> view.children(id).isEmpty()))) continue;
                int worstCase = 2 + 3 * nodes.size();
                countPair(counts, "trueLeafNavigate", pair, worstCase, maximum);
                if (pair.sameSplit()) counts.merge("trueLeafNavigate.sameParentSplit", 1L, Long::sum);
                int joined = CatalogueWorkloadShadow.joinedNavigateCharacters(pair.first(), nodes);
                trueLeaf.record(pair, joined);
                if (joined <= maximum) counts.merge("trueLeafNavigate.joinedFits", 1L, Long::sum);
            }
            return Map.of("adjacentSiblingCounters", counts, "simultaneousGates", "UNAVAILABLE",
                    "maximumRequestCharacters", maximum, "leafAuthority", "Read exact frozen adjacency after worker persistence",
                    "navigateDistributions", Map.of(
                            "pairScope", "Adjacent admitted sibling positions, with overlaps and repeated cohorts retained",
                            "characterUnits", "RelationSearchProtocol.requestCharacters (prompt plus schema)",
                            "characterBinWidth", RenderedCharacters.BIN_WIDTH,
                            "characterOverflowStartsAt", RenderedCharacters.OVERFLOW_START,
                            "concreteNavigate", concrete.checkedSummary(counts, "concreteNavigate"),
                            "trueLeafNavigate", trueLeaf.checkedSummary(counts, "trueLeafNavigate")));
        }

        private static void countPair(Map<String, Long> counts, String kind, AutomaticCatalogueWorkloadProbe.Sibling pair,
                                      int reserve, int maximum) {
            counts.merge(kind + ".pairs", 1L, Long::sum);
            var one = pair.one();
            var two = pair.two();
            if (PairPopulation.of(pair) == PairPopulation.BOTH_FRESH)
                counts.merge(kind + ".bothFreshPrimaries", 1L, Long::sum);
            if (one.rendered > 0 && two.rendered > 0 && one.rendered <= maximum && two.rendered <= maximum)
                counts.merge(kind + ".bothRenderedPrimariesFit", 1L, Long::sum);
            if (one.priorQuestionCount >= 0 && two.priorQuestionCount >= 0
                    && one.priorQuestionCount < one.maximumQuestions && two.priorQuestionCount < two.maximumQuestions)
                counts.merge(kind + ".bothObservedOwnCapacityGate", 1L, Long::sum);
            if (reserve > 0 && one.callsLeft >= reserve && one.workLeft >= reserve && one.cumulativeLeft >= reserve)
                counts.merge(kind + ".numericReserveAtFirstPrimary", 1L, Long::sum);
        }

        private void verifyFull(JsonNode fixture, JsonNode analysis, JsonNode search) throws Exception {
            assertThat(analysis.path("status").asText()).isEqualTo("PARTIAL");
            assertThat(analysis.at("/recovery/state").asText()).isEqualTo("COMPLETED_WITH_GAPS");
            assertThat(search.path("stopReason").asText()).isEmpty();
            playback.verifyCoverage(1);
            playback.verifyRelationCoverage(1);
            ScenarioScoringQuality.verify(fixture, json("inspection", "GET", "/api/taxonomy", null, 200, false), analysis);
        }

        private void saveNormalized(JsonNode snapshot) throws Exception {
            var normalized = (ObjectNode) snapshot.deepCopy();
            var summary = (ObjectNode) normalized.path("summary");
            var analysis = (ObjectNode) normalized.path("analysis");
            summary.remove(List.of("id", "jobId", "createdAt", "durationMs"));
            analysis.remove("analysisDurationMillis");
            if (analysis.path("recovery") instanceof ObjectNode recovery) recovery.remove("id");
            for (String report : List.of("/relationSearchReport", "/architectureView/relationSearchReport")) {
                if (analysis.at(report) instanceof ObjectNode search) {
                    search.remove("durationMillis");
                    if (search.path("result") instanceof ObjectNode result) result.remove("durationMillis");
                }
            }
            for (String field : List.of("elementMappings", "relationMappings")) {
                for (JsonNode mapping : normalized.path(field)) {
                    assertThat(mapping.path("snapshotId")).isEqualTo(snapshot.at("/summary/id"));
                    ((ObjectNode) mapping).remove(List.of("id", "snapshotId"));
                }
            }
            Path artifact = output.resolve("semantics.json.gz");
            try (var stream = new GZIPOutputStream(Files.newOutputStream(artifact))) { JSON.writeValue(stream, normalized); }
        }

        private void saveProgress(AutomaticCatalogueWorkloadProbe probe, ObjectNode report) throws Exception {
            report.set("probe", JSON.valueToTree(probe.report()));
            report.set("orderedExchanges", JSON.valueToTree(playback.acceptedCalls()));
            report.set("playbackFailures", JSON.valueToTree(playback.failures()));
            report.set("relationCoverage", JSON.valueToTree(playback.relationCoverage()));
            ScenarioAcceptanceProcess.saveArtifact(output.resolve("row.json"), report);
        }

        private JsonNode json(String phase, String method, String path, Object body, int status, boolean preflight) throws Exception {
            long started = System.nanoTime();
            try { return ScenarioJsonResponseReader.read(http, request(phase, method, path, body, preflight), JSON, status); }
            finally { timed(phase, started); }
        }

        private HttpRequest request(String phase, String method, String path, Object body, boolean preflight) throws Exception {
            var builder = session.request(port, method, path).header(PHASE, phase).header("Content-Type", "application/json");
            if (workspace != null) builder.header(WorkspaceContextResolver.WORKSPACE_HEADER, workspace);
            if (preflight) builder.header("X-Catalogue-Workload-Model", "true");
            return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        }

        private void timed(String phase, long started) {
            clientNanos.merge(phase, System.nanoTime() - started, Long::sum);
            clientRequests.merge(phase, 1, Integer::sum);
        }
    }

    private enum PairPopulation {
        BOTH_FRESH, BOTH_REPLAY, MIXED_FRESH_REPLAY, OTHER, UNAVAILABLE;

        static PairPopulation of(AutomaticCatalogueWorkloadProbe.Sibling pair) {
            var one = pair.one().admission;
            var two = pair.two().admission;
            var missing = AutomaticCatalogueWorkloadProbe.PrimaryAdmission.UNAVAILABLE;
            var fresh = AutomaticCatalogueWorkloadProbe.PrimaryAdmission.FRESH;
            var replay = AutomaticCatalogueWorkloadProbe.PrimaryAdmission.REPLAY;
            if (one == null || two == null || one == missing || two == missing) return UNAVAILABLE;
            if (one == fresh && two == fresh) return BOTH_FRESH;
            if (one == replay && two == replay) return BOTH_REPLAY;
            if (one == fresh && two == replay || one == replay && two == fresh) return MIXED_FRESH_REPLAY;
            return OTHER;
        }
    }

    private static final class NavigateCensus {
        private final boolean observeJoined;
        private final int maximum;
        private final NavigateCounts structural = new NavigateCounts();
        private final Map<PairPopulation, NavigateCounts> populations = new EnumMap<>(PairPopulation.class);

        NavigateCensus(boolean observeJoined, int maximum) {
            this.observeJoined = observeJoined;
            this.maximum = maximum;
            for (var population : PairPopulation.values()) populations.put(population, new NavigateCounts());
        }

        void record(AutomaticCatalogueWorkloadProbe.Sibling pair, int joined) {
            structural.record(pair, observeJoined, joined, maximum);
            populations.get(PairPopulation.of(pair)).record(pair, observeJoined, joined, maximum);
        }

        Map<String, Object> checkedSummary(Map<String, Long> counts, String kind) {
            assertThat(structural.pairs).as(kind + " structural pairs").isEqualTo(counts.getOrDefault(kind + ".pairs", 0L));
            assertThat(populations.get(PairPopulation.BOTH_FRESH).pairs).as(kind + " fresh pairs")
                    .isEqualTo(counts.getOrDefault(kind + ".bothFreshPrimaries", 0L));
            assertThat(populations.values().stream().mapToLong(p -> p.pairs).sum()).isEqualTo(structural.pairs);
            if (observeJoined) {
                assertThat(structural.joined.withinBudget).isEqualTo(counts.getOrDefault(kind + ".joinedFits", 0L));
                assertThat(structural.sameSplit).isEqualTo(counts.getOrDefault(kind + ".sameParentSplit", 0L));
            }
            var summaries = new EnumMap<PairPopulation, Map<String, Object>>(PairPopulation.class);
            populations.forEach((population, countsForPopulation) -> summaries.put(population, countsForPopulation.summary(observeJoined)));
            return Map.of("structural", structural.summary(observeJoined), "populations", summaries);
        }
    }

    private static final class NavigateCounts {
        private long pairs, sameSplit;
        private final Map<String, Long> orderedCandidateWidths = new TreeMap<>();
        private final RenderedCharacters primaryA = new RenderedCharacters();
        private final RenderedCharacters primaryB = new RenderedCharacters();
        private final RenderedCharacters joined = new RenderedCharacters();

        void record(AutomaticCatalogueWorkloadProbe.Sibling pair, boolean observeJoined, int joinedCharacters, int maximum) {
            pairs++;
            if (pair.sameSplit()) sameSplit++;
            String width = pair.first().candidates().size() + "+" + pair.second().candidates().size();
            orderedCandidateWidths.merge(width, 1L, Long::sum);
            primaryA.record(pair.one().rendered, maximum);
            primaryB.record(pair.two().rendered, maximum);
            if (observeJoined) joined.record(joinedCharacters, maximum);
        }

        Map<String, Object> summary(boolean observeJoined) {
            assertThat(orderedCandidateWidths.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(pairs);
            var summary = new LinkedHashMap<String, Object>();
            summary.put("pairs", pairs);
            summary.put("sameParentSplit", sameSplit);
            summary.put("orderedCandidateWidths", orderedCandidateWidths);
            summary.put("primaryARenderedCharacters", primaryA.summary(pairs));
            summary.put("primaryBRenderedCharacters", primaryB.summary(pairs));
            if (observeJoined) summary.put("joinedRenderedCharacters", joined.summary(pairs));
            return summary;
        }
    }

    private static final class RenderedCharacters {
        static final int BIN_WIDTH = 1_000;
        static final int OVERFLOW_START = 64_000;
        private long observations, unavailable, withinBudget;
        private Integer minimum, maximum;
        private final Map<Integer, Long> binsByLowerBound = new TreeMap<>();

        void record(int characters, int effectiveLimit) {
            if (characters <= 0) {
                unavailable++;
                return;
            }
            observations++;
            if (characters <= effectiveLimit) withinBudget++;
            minimum = minimum == null ? characters : Math.min(minimum, characters);
            maximum = maximum == null ? characters : Math.max(maximum, characters);
            int bin = Math.min(OVERFLOW_START, characters / BIN_WIDTH * BIN_WIDTH);
            binsByLowerBound.merge(bin, 1L, Long::sum);
        }

        Map<String, Object> summary(long expectedPositions) {
            assertThat(observations + unavailable).isEqualTo(expectedPositions);
            assertThat(binsByLowerBound.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(observations);
            var summary = new LinkedHashMap<String, Object>();
            summary.put("observations", observations);
            summary.put("unavailable", unavailable);
            summary.put("minimum", minimum);
            summary.put("maximum", maximum);
            summary.put("withinEffectiveBudget", withinBudget);
            summary.put("binsByLowerBound", binsByLowerBound);
            return summary;
        }
    }

    private record ModelPreflight(com.taxonomy.workspace.service.WorkspaceContext owner,
                                  com.taxonomy.dto.AnalysisModelBinding full, com.taxonomy.dto.AnalysisModelBinding selected) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfiguration {
        @Bean AtomicReference<ModelPreflight> workloadModel() { return new AtomicReference<>(); }

        @Bean FilterRegistrationBean<Filter> workloadObservation(ObjectProvider<WorkspaceResolver> workspace,
                                                                 ObjectProvider<AnalysisAdmissionModelPort> models,
                                                                 AtomicReference<ModelPreflight> observed) {
            Filter recorder = (request, response, next) -> {
                var http = (HttpServletRequest) request;
                String phase = http.getHeader(PHASE);
                if (phase == null) { next.doFilter(request, response); return; }
                try (var interval = AutomaticCatalogueWorkloadProbe.enter("http." + phase)) {
                    if ("true".equals(http.getHeader("X-Catalogue-Workload-Model"))) {
                        // Capture from the same authenticated, explicitly pinned request before submission.
                        var previous = RequestContextHolder.getRequestAttributes();
                        var attributes = new ServletRequestAttributes(http, (HttpServletResponse) response);
                        try {
                            RequestContextHolder.setRequestAttributes(attributes);
                            var owner = WorkspaceResolver.workspaceContext(workspace.getObject().resolveCurrentRepositoryContextForRead());
                            var full = models.getObject().capture(owner, null, null, AnalysisScope.full());
                            var scope = Mode.chosen().scope();
                            var pinned = models.getObject().capture(owner, full.commit(), null, scope);
                            observed.set(new ModelPreflight(owner, full, pinned));
                        } finally {
                            attributes.requestCompleted();
                            RequestContextHolder.setRequestAttributes(previous);
                        }
                    }
                    next.doFilter(request, response);
                }
            };
            var registration = new FilterRegistrationBean<>(recorder);
            registration.addUrlPatterns("/api/*");
            return registration;
        }
    }

    private static void save(Path path, Object value) throws Exception {
        ScenarioAcceptanceProcess.saveArtifact(path, JSON.valueToTree(value));
    }
}
