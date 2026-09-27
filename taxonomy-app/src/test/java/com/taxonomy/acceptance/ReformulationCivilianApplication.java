package com.taxonomy.acceptance;

import com.taxonomy.TaxonomyApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Authenticated HTTP commands and reads; never inserts analysis or proposal state. */
public final class ReformulationCivilianApplication {
    private static final String PASSWORD = "Reformulation-Civilian-Test-2026!";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;
    private final Path output;
    private final ScenarioLlmPlayback playback;
    private ReformulationCivilianBrowser browser;
    private boolean browserMode;
    private boolean fullLifecycle = true;

    private ReformulationCivilianApplication(int port, Path output, ScenarioLlmPlayback playback) {
        this.base = "http://127.0.0.1:" + port; this.output = output; this.playback = playback;
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class, CivilianLlmConfiguration.class).run(
                "--server.port=0", "--embedding.enabled=false", "--embedding.allow-download=false",
                "--spring.datasource.url=jdbc:hsqldb:file:" + directory.resolve("db").toAbsolutePath() + ";shutdown=true",
                "--spring.datasource.username=SA", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--taxonomy.init.async=false", "--civilian.reformulation=true", "--llm.mock=false", "--llm.provider=CUSTOM_OPENAI",
                "--civilian.reformulation-case=" + (args[1].startsWith("authored-") ? args[1].substring(9) : "flood"),
                "--custom.llm.url=" + CivilianLlmConfiguration.URL, "--custom.llm.model=civilian-fixture",
                "--taxonomy.admin-password=" + PASSWORD, "--taxonomy.security.require-password-change=false")) {
            var scenario = new ReformulationCivilianApplication(Integer.parseInt(app.getEnvironment().getProperty("local.server.port")),
                    Path.of(args[0]), app.getBean(ScenarioLlmPlayback.class));
            scenario.browserMode = args[1].equals("browser");
            scenario.fullLifecycle = !args[1].startsWith("authored-");
            try { if (args[1].equals("read")) scenario.verifyRestart(); else scenario.analysisAndOffer(); }
            finally {
                scenario.save(args[1] + "-llm-calls.json", scenario.json.valueToTree(scenario.playback.calls()));
                scenario.save(args[1] + "-llm-prompts.json", scenario.json.valueToTree(scenario.playback.prompts()));
                scenario.save(args[1] + "-llm-failures.json", scenario.json.valueToTree(scenario.playback.failures()));
                if (scenario.browser != null) scenario.browser.close();
            }
        }
        System.out.println("REFORMULATION_CIVILIAN_" + args[1].toUpperCase(Locale.ROOT) + "_OK");
    }

    private void analysisAndOffer() throws Exception {
        var source = playback.fixture().path("requirement");
        long project = request("POST", "/api/projects", Map.of("projectKey", "REF-" + UUID.randomUUID(),
                "title", "Civilian reformulation", "description", "Authored remote-response acceptance", "status", "ACTIVE"), 201).path("id").asLong();
        request("POST", "/api/workspace/provision", null, 200);
        String projectPath = "/api/projects/" + project;
        var requirement = request("POST", projectPath + "/requirements", Map.of("requirementKey", source.path("key").asText(),
                "title", source.path("title").asText(), "text", source.path("text").asText(), "status", "DRAFT",
                "priority", 80, "criticality", "HIGH", "requirementType", "FUNCTIONAL", "reviewStatus", "PROPOSED"), 201);
        String requirementPath = projectPath + "/requirements/" + requirement.path("id").asLong();
        var job = request("POST", requirementPath + "/analyses", Map.of("provider", "CUSTOM_OPENAI",
                "idempotencyKey", UUID.randomUUID().toString()), 202);
        var completed = awaitTerminal(projectPath + "/analysis-jobs/" + job.path("id").asText(), false);
        save("analysis-job.json", completed);
        assertThat(playback.failures()).isEmpty();
        assertThat(completed.path("status").asText()).as(completed.toPrettyString()).isEqualTo("SUCCESS");
        String snapshotId = completed.path("items").get(0).path("snapshotId").asText();
        var snapshot = request("GET", projectPath + "/snapshots/" + snapshotId, null, 200);
        save("snapshot.json", snapshot);
        assertThat(snapshot.at("/analysis/scores/BP-1017").asInt()).isPositive();
        assertThat(snapshot.at("/analysis/architectureView/includedElements")).isNotEmpty();
        var before = request("GET", requirementPath, null, 200);
        assertThat(before.path("currentAnalysisSnapshotId").asText()).isEqualTo(snapshotId);
        String offers = requirementPath + "/reformulations";
        var created = request("POST", offers, Map.of("snapshotId", snapshotId,
                "sourceVersionId", before.path("currentVersionId").asLong(), "language", "en"), 202);
        String offerPath = offers + "/" + created.path("id").asText();
        var runs = awaitTerminal(offerPath + "/synthesis-runs", true);
        save("synthesis-runs.json", runs);
        save("llm-calls.json", json.valueToTree(playback.calls()));
        assertThat(playback.failures()).isEmpty();
        assertThat(runs.get(0).path("status").asText()).as(runs.toPrettyString()).isEqualTo("COMPLETED");
        var offer = request("GET", offerPath, null, 200);
        save("proposal.json", offer);
        assertThat(offer.at("/baseline/originalText").asText()).isEqualTo(source.path("text").asText());
        if (fullLifecycle) assertThat(offer.at("/currentRevision/text").asText()).contains("15 minutes", "surface-water", "safety-critical");
        else {
            for (var fragment : playback.fixture().at("/application/protectedFragments"))
                assertThat(offer.at("/currentRevision/text").asText()).contains(fragment.asText());
            assertThat(snapshot.at("/analysis/scores/CR").asInt()).isZero();
            assertThat(offer.at("/currentRevision/validation/findings").valueStream().filter(f -> f.path("kind").asText().equals("UNMAPPED_SOURCE")))
                    .isNotEmpty();
            assertThat(offer.at("/currentRevision/questions")).hasSize(3);
        }
        assertThat(offer.at("/currentRevision/sections")).isNotEmpty();
        assertThat(offer.at("/currentRevision/questions")).isNotEmpty();
        assertThat(playback.calls().stream().map(ScenarioLlmPlayback.Call::ruleId).filter(id -> id.startsWith("civilian:NODE:")))
                .containsExactlyInAnyOrder("civilian:NODE:BP-1060", "civilian:NODE:BP-1327", "civilian:NODE:BP-1000", "civilian:NODE:BP",
                        "civilian:NODE:IP-1102", "civilian:NODE:IP-1005", "civilian:NODE:IP-1000", "civilian:NODE:IP");
        assertThat(request("GET", requirementPath, null, 200)).isEqualTo(before);
        assertThat(request("GET", projectPath + "/snapshots/" + snapshotId, null, 200)).isEqualTo(snapshot);
        if (!fullLifecycle) return;
        if (browserMode) {
            browser = new ReformulationCivilianBrowser(URI.create(base).getPort(), output); browser.login(PASSWORD);
            browser.inspect(offerPath, source.path("text").asText(), findQuestion(offer.at("/currentRevision/questions"), "stale-observation").path("id").asText());
            offer = request("GET", offerPath, null, 200);
        }
        decisionsAndAdoption(projectPath, requirementPath, offerPath, offer, before, snapshotId, snapshot);
    }

    private void decisionsAndAdoption(String projectPath, String requirementPath, String offerPath,
            JsonNode initial, JsonNode before, String snapshotId, JsonNode snapshot) throws Exception {
        var questions = initial.at("/currentRevision/questions");
        assertThat(questions).hasSize(3);
        var shared = findQuestion(questions, "stale-observation");
        var ingestion = findQuestion(questions, "acquisition");
        var publication = findQuestion(questions, "publication");
        assertThat(shared.path("discoveries")).hasSize(2);
        assertThat(ingestion.path("wording")).isEqualTo(publication.path("wording"));
        assertThat(ingestion.path("id")).isNotEqualTo(publication.path("id"));
        assertThat(ingestion.path("key")).isNotEqualTo(publication.path("key"));
        long originalRevision = revision(initial);
        var answered = request("POST", offerPath + "/answers", Map.of("questionId", shared.path("id").asText(),
                "action", "ANSWER", "values", List.of("Retain last observation with timestamp"),
                "rationale", "Human civilian acceptance decision"), 201, originalRevision);
        var deferred = request("POST", offerPath + "/answers", Map.of("questionId", ingestion.path("id").asText(),
                "action", "DEFER", "values", List.of(), "rationale", "Obtain evidence for a maximum age"), 201, revision(answered));
        assertThat(findQuestion(deferred.at("/currentRevision/questions"), "acquisition").path("state").asText()).isEqualTo("DEFERRED");
        String statementId = deferred.at("/currentRevision/statements").valueStream()
                .filter(s -> s.path("provenance").asText().equals("MODEL_ADDITION")).findFirst().orElseThrow().path("id").asText();
        request("POST", offerPath + "/revisions", Map.of("text", "Stale overwrite", "rationale", "Must be rejected"), 412, originalRevision);
        var targeted = request("POST", offerPath + "/synthesis-runs", null, 202, revision(deferred));
        var runs = awaitTerminal(offerPath + "/synthesis-runs", true);
        save("targeted-runs.json", runs);
        assertThat(playback.failures()).isEmpty();
        assertThat(runs.valueStream().filter(r -> r.path("id").equals(targeted.path("id"))).findFirst().orElseThrow()
                .path("status").asText()).as(runs.toPrettyString()).isEqualTo("COMPLETED");
        var revised = request("GET", offerPath, null, 200);
        assertThat(playback.calls().stream().map(ScenarioLlmPlayback.Call::ruleId).filter(id -> id.startsWith("civilian:REWORD:")))
                .hasSize(8).doesNotHaveDuplicates();
        save("answered-proposal.json", revised);
        assertThat(revised.at("/currentRevision/text").asText()).contains("retain last observation with timestamp");
        verifyManualProtection(offerPath, revised, statementId);
        assertThat(request("GET", offerPath + "/revisions/" + originalRevision, null, 200)).isEqualTo(initial.path("currentRevision"));
        assertThat(request("GET", requirementPath, null, 200)).isEqualTo(before);
        assertThat(request("GET", projectPath + "/snapshots/" + snapshotId, null, 200)).isEqualTo(snapshot);
        var preview = request("POST", offerPath + "/adoption-previews", null, 201, revision(revised));
        save("preview.json", preview);
        assertThat(preview.at("/content/blockingReasons")).isEmpty();
        assertThat(preview.at("/content/unresolvedQuestionIds")).isNotEmpty();
        assertThat(request("GET", requirementPath, null, 200)).isEqualTo(before);
        String command = UUID.randomUUID().toString();
        JsonNode browserReceipt = null;
        String rationale = "Explicit adoption as a draft; numeric decisions remain open";
        if (browser != null) {
            browser.preview(offerPath);
            assertThat(request("GET", requirementPath, null, 200)).isEqualTo(before);
            browser.confirm(rationale);
            var receipts = request("GET", offerPath + "/adoptions", null, 200); assertThat(receipts).hasSize(1);
            browserReceipt = receipts.get(0); command = browserReceipt.path("commandId").asText();
            preview = request("GET", offerPath + "/adoption-previews/" + browserReceipt.path("previewId").asText(), null, 200);
            save("preview.json", preview);
        }
        var confirmation = Map.of("commandId", command, "previewId", preview.at("/content/id").asText(),
                "previewHash", preview.path("hash").asText(), "confirmed", true, "acknowledgeWarnings", true,
                "rationale", rationale);
        var adopted = browserReceipt == null ? request("POST", offerPath + "/adoptions", confirmation, 200, revision(revised)) : browserReceipt;
        assertThat(request("POST", offerPath + "/adoptions", confirmation, 200, revision(revised))).isEqualTo(adopted);
        save("receipt.json", adopted);
        var current = request("GET", requirementPath, null, 200);
        assertThat(current.path("currentVersionId")).isNotEqualTo(before.path("currentVersionId"));
        assertThat(current.at("/currentVersion/text")).isEqualTo(preview.at("/content/finalText"));
        assertThat(adopted.path("analysisNeedsRefresh").asBoolean()).isTrue();
        String receiptPath = offerPath + "/adoptions/" + command + "/export";
        String revisionPath = offerPath + "/revisions/" + revision(revised) + "/export";
        exportAll("revision", revisionPath); exportAll("adoption", receiptPath);
        playback.registerAdoptedSource(current.at("/currentVersion/text").asText());
        var newJob = request("POST", requirementPath + "/analyses", Map.of("provider", "CUSTOM_OPENAI",
                "idempotencyKey", UUID.randomUUID().toString()), 202);
        var newResult = awaitTerminal(projectPath + "/analysis-jobs/" + newJob.path("id").asText(), false);
        save("reanalysis.json", newResult);
        assertThat(playback.failures()).isEmpty();
        assertThat(newResult.path("status").asText()).as(newResult.toPrettyString()).isEqualTo("SUCCESS");
        String newSnapshot = newResult.at("/items/0/snapshotId").asText();
        var next = request("POST", requirementPath + "/reformulations", Map.of("snapshotId", newSnapshot,
                "sourceVersionId", current.path("currentVersionId").asLong(), "language", "en"), 202);
        String nextPath = requirementPath + "/reformulations/" + next.path("id").asText();
        var nextRuns = awaitTerminal(nextPath + "/synthesis-runs", true);
        assertThat(playback.failures()).isEmpty();
        assertThat(nextRuns.get(0).path("status").asText()).as(nextRuns.toPrettyString()).isEqualTo("COMPLETED");
        var inherited = request("GET", nextPath, null, 200);
        save("inherited-proposal.json", inherited);
        verifyLineage(inherited);
        var checkpoint = request("POST", "/api/projects/git/commit", Map.of("message", "Explicit civilian reformulation checkpoint"), 200);
        save("checkpoint.json", checkpoint);
        assertThat(checkpoint.path("commitId").asText()).matches("[0-9a-f]{40}");
        var materialized = request("POST", "/api/projects/git/materialize", Map.of("branch", checkpoint.path("branch").asText(),
                "expectedHead", checkpoint.path("commitId").asText()), 200);
        save("materialized.json", materialized);
        assertThat(request("GET", nextPath, null, 200)).isEqualTo(inherited);
        var workspace = request("GET", "/api/workspace/current", null, 200);
        var identity = Map.of("requirementPath", requirementPath, "nextPath", nextPath, "receiptPath", receiptPath,
                "revisionPath", revisionPath, "workspaceId", workspace.path("workspaceId").asText(), "checkpoint", checkpoint,
                "current", request("GET", requirementPath, null, 200));
        save("identity.json", json.valueToTree(identity));
        var foreign = request("POST", "/api/workspace/create", Map.of("displayName", "Foreign civilian scope", "description", "Must not read this offer"), 200);
        request("POST", "/api/workspace/" + foreign.path("workspaceId").asText() + "/switch", null, 200);
        request("POST", "/api/workspace/provision", null, 200);
        request("GET", offerPath, null, 404);
        request("POST", "/api/workspace/" + workspace.path("workspaceId").asText() + "/switch", null, 200);
    }

    private void verifyManualProtection(String offerPath, JsonNode source, String statementId) throws Exception {
        var variant = request("POST", offerPath + "/variants", Map.of("rationale", "Independent manual-edit protection check"), 201, revision(source));
        String path = offerPath.substring(0, offerPath.lastIndexOf('/') + 1) + variant.path("id").asText();
        var edited = request("POST", path + "/statements/" + statementId,
                Map.of("action", "EDIT", "text", "Human wording: preserve official warning channels.", "rationale", "Keep my precise wording"),
                201, revision(variant));
        var started = request("POST", path + "/synthesis-runs", null, 202, revision(edited));
        var runs = awaitTerminal(path + "/synthesis-runs", true);
        var run = runs.valueStream().filter(r -> r.path("id").equals(started.path("id"))).findFirst().orElseThrow();
        save("protected-manual-run.json", run);
        assertThat(playback.failures()).isEmpty();
        assertThat(run.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(run.path("failureCode").asText()).isEqualTo("MANUAL_DRAFT_PROTECTED");
        assertThat(run.path("resultRevision").isNull()).isTrue();
        assertThat(run.at("/candidate/statements").valueStream().filter(s -> s.path("id").asText().equals(statementId)))
                .singleElement().satisfies(s -> assertThat(s.path("wording").asText()).isEqualTo("Human wording: preserve official warning channels."));
        assertThat(request("GET", path, null, 200)).isEqualTo(edited);
        assertThat(request("GET", offerPath, null, 200)).isEqualTo(source);
    }

    private void verifyRestart() throws Exception {
        var identity = json.readTree(Files.readString(output.resolve("identity.json")));
        assertThat(request("GET", identity.path("requirementPath").asText(), null, 200)).isEqualTo(identity.path("current"));
        var inherited = request("GET", identity.path("nextPath").asText(), null, 200);
        assertThat(inherited).isEqualTo(json.readTree(Files.readString(output.resolve("inherited-proposal.json"))));
        verifyLineage(inherited);
        for (String kind : List.of("revision", "adoption")) {
            String endpoint = identity.path(kind.equals("revision") ? "revisionPath" : "receiptPath").asText();
            for (String format : List.of("json", "md", "html", "docx")) {
                byte[] actual = download(endpoint + "?format=" + format);
                byte[] expected = Files.readAllBytes(output.resolve(kind + "." + format));
                if (format.equals("docx")) assertThat(docxText(actual)).isEqualTo(docxText(expected));
                else assertThat(actual).isEqualTo(expected);
            }
        }
        assertThat(playback.calls()).isEmpty();
    }
    private void verifyLineage(JsonNode offer) {
        var inherited = json.readTree(offer.at("/baseline/frozenContext/inheritedDecisionContext").asText());
        assertThat(inherited.toString()).contains("Human civilian acceptance decision", "Obtain evidence for a maximum age", "DEFERRED");
        assertThat(offer.at("/currentRevision/statements").valueStream().filter(s -> s.path("editingOrigin").asText().equals("SOURCE")))
                .allSatisfy(s -> assertThat(s.path("provenance").asText()).isEqualTo("ADOPTED_SOURCE"));
    }
    private void exportAll(String prefix, String path) throws Exception {
        for (String format : List.of("json", "md", "html", "docx")) {
            byte[] bytes = download(path + "?format=" + format);
            Files.write(output.resolve(prefix + "." + format), bytes);
            String readable = format.equals("docx") ? docxText(bytes) : new String(bytes, StandardCharsets.UTF_8);
            assertThat(readable).contains("15 minutes", "surface-water", "Human civilian acceptance decision");
        }
    }
    private static String docxText(byte[] bytes) throws Exception {
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            return doc.getParagraphs().stream().map(p -> p.getText()).collect(java.util.stream.Collectors.joining("\n"));
        }
    }
    private byte[] download(String path) throws Exception {
        var response = http.send(builder("GET", path, null, null).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment;");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        return response.body();
    }
    private JsonNode findQuestion(JsonNode questions, String subject) {
        return questions.valueStream().filter(q -> q.at("/key/subject").asText().equals(subject)).findFirst().orElseThrow();
    }
    private static long revision(JsonNode proposal) { return proposal.at("/currentRevision/number").asLong(); }

    private JsonNode awaitTerminal(String path, boolean array) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        JsonNode latest;
        do {
            latest = request("GET", path, null, 200);
            var terminal = Set.of("COMPLETED", "SUCCESS", "PARTIAL", "FAILED", "CANCELLED", "SUPERSEDED");
            if (array ? !latest.isEmpty() && latest.valueStream().allMatch(v -> terminal.contains(v.path("status").asText()))
                    : terminal.contains(latest.path("status").asText())) return latest;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out: " + latest.toPrettyString());
    }

    private JsonNode request(String method, String path, Object body, int status) throws Exception {
        return request(method, path, body, status, null);
    }
    private HttpRequest.Builder builder(String method, String path, Object body, Long revision) {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (revision != null) builder.header("If-Match", "\"" + revision + "\"");
        return builder;
    }
    private JsonNode request(String method, String path, Object body, int status, Long revision) throws Exception {
        var response = http.send(builder(method, path, body, revision).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }

    private void save(String name, JsonNode value) throws Exception { Files.writeString(output.resolve(name), value.toPrettyString()); }
}
