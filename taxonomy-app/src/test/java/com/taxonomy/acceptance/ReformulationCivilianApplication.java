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

    private ReformulationCivilianApplication(int port, Path output, ScenarioLlmPlayback playback) {
        this.base = "http://127.0.0.1:" + port; this.output = output; this.playback = playback;
    }

    public static void main(String[] args) throws Exception {
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class, CivilianLlmConfiguration.class).run(
                "--server.port=0", "--embedding.enabled=false", "--embedding.allow-download=false",
                "--taxonomy.init.async=false", "--llm.mock=false", "--llm.provider=CUSTOM_OPENAI",
                "--custom.llm.url=" + CivilianLlmConfiguration.URL, "--custom.llm.model=civilian-fixture",
                "--taxonomy.admin-password=" + PASSWORD, "--taxonomy.security.require-password-change=false")) {
            var scenario = new ReformulationCivilianApplication(Integer.parseInt(app.getEnvironment().getProperty("local.server.port")),
                    Path.of(args[0]), app.getBean(ScenarioLlmPlayback.class));
            scenario.analysisAndOffer();
        }
        System.out.println("REFORMULATION_CIVILIAN_PROPOSAL_OK");
    }

    private void analysisAndOffer() throws Exception {
        var source = playback.fixture().path("requirement");
        long project = request("POST", "/api/projects", Map.of("projectKey", "REF-" + UUID.randomUUID(),
                "title", "Civilian reformulation", "description", "Authored remote-response acceptance", "status", "ACTIVE"), 201).path("id").asLong();
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
        assertThat(offer.at("/currentRevision/text").asText()).contains("15 minutes", "surface-water", "safety-critical");
        assertThat(offer.at("/currentRevision/sections")).isNotEmpty();
        assertThat(offer.at("/currentRevision/questions")).isNotEmpty();
        assertThat(request("GET", requirementPath, null, 200)).isEqualTo(before);
        assertThat(request("GET", projectPath + "/snapshots/" + snapshotId, null, 200)).isEqualTo(snapshot);
    }

    private JsonNode awaitTerminal(String path, boolean array) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        JsonNode latest;
        do {
            latest = request("GET", path, null, 200);
            JsonNode value = array ? latest.path(0) : latest;
            if (Set.of("COMPLETED", "SUCCESS", "PARTIAL", "FAILED", "CANCELLED").contains(value.path("status").asText())) return latest;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out: " + latest.toPrettyString());
    }

    private JsonNode request(String method, String path, Object body, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }

    private void save(String name, JsonNode value) throws Exception { Files.writeString(output.resolve(name), value.toPrettyString()); }
}
