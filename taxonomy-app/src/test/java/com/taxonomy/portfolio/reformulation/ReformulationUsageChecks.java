package com.taxonomy.portfolio.reformulation;

import com.taxonomy.TaxonomyApplication;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.workspace.service.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/** Real application, auth, database, leases, HTTP; only the usage event inputs are authored. */
public final class ReformulationUsageChecks {
    private static final String PASSWORD = "Usage-Only-Test-983!";
    private static final String ORIGINAL = "Arbeitszeiterfassung. Keine automatische Übernahme.";
    private ReformulationUsageChecks() {}
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]); Files.createDirectories(directory);
        boolean read = args.length > 1 && args[1].equals("read");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.datasource.url=jdbc:hsqldb:file:" + directory.resolve("db").toAbsolutePath() + ";shutdown=true",
                "--spring.datasource.username=SA", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--embedding.enabled=false", "--taxonomy.init.async=false", "--llm.mock=true",
                "--taxonomy.admin-password=" + PASSWORD, "--taxonomy.security.require-password-change=false")) {
            app.getBean(com.taxonomy.composition.reformulation.ReformulationRecoveryCoordinator.class).stop();
            var json = app.getBean(ObjectMapper.class);
            var projects = app.getBean(ProjectPortfolioService.class);
            var proposals = app.getBean(ReformulationService.class);
            var recovery = app.getBean(ReformulationRecoveryService.class);
            var workspaces = app.getBean(WorkspaceManager.class);
            var jdbc = app.getBean(JdbcTemplate.class);
            String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
            if (read) {
                var state = Files.readAllLines(directory.resolve("identity"));
                workspaces.switchWorkspace("admin", state.get(0));
                var result = get(client, json, base + state.get(1), 200);
                assertSummary(result);
                check(result.path("pendingAttempts").asLong() == 1, "Restart invented a completed outcome");
                check(result.path("fromFirstAttempt").asBoolean(), "Recording origin absent: late activation must be distinguishable");
                check(!get(client, json, base + state.get(2), 200).path("fromFirstAttempt").asBoolean(), "Restart rewrote incomplete recording history");
                System.out.println("REFORMULATION_USAGE_RESTART_OK"); return;
            }
            var workspace = workspaces.createWorkspace("admin", "Usage persistence", "Transport evidence, not billing");
            workspace = workspaces.provisionWorkspaceRepository("admin", workspace.getWorkspaceId());
            workspaces.switchWorkspace("admin", workspace.getWorkspaceId());
            var context = new WorkspaceContext("admin", workspace.getWorkspaceId(), workspace.getCurrentBranch(), workspace.getSourceRepositoryId());
            var project = projects.createProject(new CreateProjectRequest("USAGE", "Usage", "Fixture", ProjectStatus.ACTIVE, null, null, null, null), "admin", context);
            var requirement = projects.createRequirement(project.id(), new CreateRequirementRequest("TIME", "Time", ORIGINAL, RequirementStatus.APPROVED,
                    50, Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.CONFIRMED, "admin", "Original", null), "admin", context);
            var analyses = app.getBean(PortfolioAnalysisPersistenceService.class);
            var job = analyses.createOrReuseJob(project.id(), List.of(requirement.id()), null, 25, "usage-fixture", "admin", context);
            var root = app.getBean(TaxonomyService.class).getFullTree().stream().filter(n -> n.getCode().equals("BP")).findFirst().orElseThrow();
            var analysis = new AnalysisResult(Map.of(root.getCode(), 50), List.of(root)); analysis.setStatus("SUCCESS");
            String snapshot = UUID.randomUUID().toString();
            analyses.persistSnapshot(job.items().getFirst().id(), job.id(), project.id(), PortfolioScope.key("admin", context), snapshot, "usage-session", analysis,
                    null, null, null, null, null, "p", "t", "admin", context, 1);
            var offer = proposals.create(project.id(), requirement.id(), new ReformulationDtos.CreateRequest(requirement.currentVersionId(), snapshot, "de"), "admin", context);
            var dispatch = recovery.enqueue(project.id(), requirement.id(), offer.id(), 1, "CUSTOM_OPENAI", "fixture", "p", "s", "private prompt", Map.of(), "endpoint-hash", "admin", context);
            var claim = recovery.claim(dispatch, UUID.randomUUID().toString()).orElseThrow();
            String path = "/api/projects/" + project.id() + "/requirements/" + requirement.id() + "/reformulations/" + offer.id()
                    + "/synthesis-runs/" + dispatch.run().id() + "/usage";
            var empty = get(client, json, base + path, 200); // RED before the usage feature: authenticated endpoint absent.
            check(!empty.path("recorded").asBoolean(), "Legacy/unsubscribed run invented zero measured usage");
            var offerBefore = proposals.get(project.id(), requirement.id(), offer.id(), "admin", context);
            var requirementBefore = projects.getRequirement(project.id(), requirement.id(), "admin", context);
            Object usage = app.getBean("reformulationUsageService");
            expectFailure(() -> invoke(json, usage, "start", claim, start(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "HTTP", 0)), "Admission before recording activation succeeded");
            invoke(json, usage, "activate", claim);
            var activated = get(client, json, base + path, 200);
            check(activated.path("recorded").asBoolean() && activated.path("fromFirstAttempt").asBoolean()
                    && !activated.path("recordedSince").isNull(), "Initial recording boundary not persisted");
            invoke(json, usage, "activate", claim);
            check(get(client, json, base + path, 200).path("recordedSince").equals(activated.path("recordedSince")), "Activation changed its origin");
            String invocation = UUID.randomUUID().toString();
            String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
            var a = start(first, invocation, "HTTP", 0); var b = start(second, invocation, "HTTP", 1);
            invoke(json, usage, "start", claim, a); invoke(json, usage, "start", claim, a);
            expectFailure(() -> invoke(json, usage, "start", claim, start(first, UUID.randomUUID().toString(), "HTTP", 0)), "Conflicting start replaced evidence");
            var wrongProvider = new HashMap<String,Object>(a); wrongProvider.put("id", UUID.randomUUID().toString()); wrongProvider.put("provider", "GEMINI");
            expectFailure(() -> invoke(json, usage, "start", claim, wrongProvider), "Wrong provider was admitted");
            check(get(client, json, base + path, 200).path("pendingAttempts").asLong() == 1, "Start not committed or duplicated");
            invoke(json, usage, "complete", claim, first, result(503, "HTTP_ERROR", null, null, null));
            invoke(json, usage, "start", claim, b);
            var completed = result(200, "RESPONSE", 7L, 3L, 10L); completed.put("cachedInputTokens", 0L);
            invoke(json, usage, "complete", claim, second, completed);
            var stamp = jdbc.queryForObject("select completed_at from reformulation_usage_attempt where id=?", OffsetDateTime.class, second);
            invoke(json, usage, "complete", claim, second, completed);
            check(stamp.equals(jdbc.queryForObject("select completed_at from reformulation_usage_attempt where id=?", OffsetDateTime.class, second)), "Duplicate completion changed evidence");
            expectFailure(() -> invoke(json, usage, "complete", claim, second, result(200, "RESPONSE", 8L, 3L, 11L)), "Conflicting completion was accepted");
            String late = UUID.randomUUID().toString();
            invoke(json, usage, "start", claim, start(late, UUID.randomUUID().toString(), "HTTP", 0));
            jdbc.update("update reformulation_recovery_lease set lease_until=? where run_id=?", OffsetDateTime.parse("2020-01-01T00:00:00Z"), dispatch.run().id());
            var successor = recovery.claim(dispatch, UUID.randomUUID().toString()).orElseThrow();
            expectFailure(() -> invoke(json, usage, "start", claim, start(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "HTTP", 0)), "Stale claim admitted new paid work");
            expectFailure(() -> invoke(json, usage, "complete", successor, late, completed), "Successor overwrote another attempt's receipt");
            invoke(json, usage, "complete", claim, late, result(200, "RESPONSE", Long.MAX_VALUE, null, null));
            check(!recovery.finish(claim, null, "STALE_FAILURE"), "Late accounting restored publication rights");
            String pending = UUID.randomUUID().toString();
            invoke(json, usage, "start", successor, start(pending, UUID.randomUUID().toString(), "HTTP", 0));
            String replay = UUID.randomUUID().toString();
            invoke(json, usage, "start", successor, start(replay, UUID.randomUUID().toString(), "RECORDING_REPLAY", 0));
            expectFailure(() -> invoke(json, usage, "complete", successor, replay, completed), "Replay was counted as live usage");
            invoke(json, usage, "complete", successor, replay, result(null, "RESPONSE", null, null, null));
            assertSummary(get(client, json, base + path, 200));
            var foreign = workspaces.createWorkspace("admin", "Foreign usage", "No cross-workspace access");
            foreign = workspaces.provisionWorkspaceRepository("admin", foreign.getWorkspaceId()); workspaces.switchWorkspace("admin", foreign.getWorkspaceId());
            get(client, json, base + path, 404); workspaces.switchWorkspace("admin", context.workspaceId());
            get(client, json, base + path.replace(dispatch.run().id(), UUID.randomUUID().toString()), 404);
            var anonymous = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(anonymous.statusCode() == 401, "Anonymous usage disclosure");
            check(proposals.get(project.id(), requirement.id(), offer.id(), "admin", context).equals(offerBefore), "Usage mutated the offer");
            check(projects.getRequirement(project.id(), requirement.id(), "admin", context).equals(requirementBefore), "Usage mutated original requirement");
            check(projects.listRequirementVersions(project.id(), requirement.id(), "admin", context).size() == 1, "Usage created requirement version");
            // Prevent the autonomous scheduler from consuming the deliberately incomplete evidence fixture.
            proposals.cancelRun(project.id(), requirement.id(), offer.id(), dispatch.run().id(), 1, "admin", context);
            assertSummary(get(client, json, base + path, 200));
            // First enabling recording during a later lease cannot certify the earlier unmetered history.
            var resumed = recovery.enqueue(project.id(), requirement.id(), offer.id(), 1, "CUSTOM_OPENAI", "fixture", "p", "s", "private prompt", Map.of(), "endpoint-hash", "admin", context);
            recovery.claim(resumed, UUID.randomUUID().toString()).orElseThrow();
            jdbc.update("update reformulation_recovery_lease set lease_until=? where run_id=?", OffsetDateTime.parse("2020-01-01T00:00:00Z"), resumed.run().id());
            var resumedClaim = recovery.claim(resumed, UUID.randomUUID().toString()).orElseThrow();
            invoke(json, usage, "activate", resumedClaim);
            String resumedPath = path.replace(dispatch.run().id(), resumed.run().id());
            var resumedSummary = get(client, json, base + resumedPath, 200);
            check(resumedSummary.path("recorded").asBoolean() && !resumedSummary.path("fromFirstAttempt").asBoolean(), "Resumed recording hid missing earlier history");
            String afterCancel = UUID.randomUUID().toString();
            invoke(json, usage, "start", resumedClaim, start(afterCancel, UUID.randomUUID().toString(), "HTTP", 0));
            proposals.cancelRun(project.id(), requirement.id(), offer.id(), resumed.run().id(), 1, "admin", context);
            invoke(json, usage, "complete", resumedClaim, afterCancel, completed);
            check(get(client, json, base + resumedPath, 200).path("inputTokens").path("reported").asText().equals("7"), "Cancelled run lost its late usage");
            check(!recovery.finish(resumedClaim, null, "LATE_FAILURE"), "Late usage revived a cancelled run");
            Files.write(directory.resolve("identity"), List.of(context.workspaceId(), path, resumedPath));
            System.out.println("REFORMULATION_USAGE_PERSISTENCE_OK");
        }
    }
    private static Map<String,Object> start(String id, String invocation, String source, int retry) {
        return Map.of("id", id, "invocationId", invocation, "provider", "CUSTOM_OPENAI", "source", source, "retryIndex", retry);
    }
    private static Map<String,Object> result(Integer code, String outcome, Long input, Long output, Long total) {
        var map = new HashMap<String,Object>(); map.put("statusCode", code); map.put("outcome", outcome); map.put("durationMillis", code == null ? 0 : 12);
        map.put("inputTokens", input); map.put("outputTokens", output); map.put("totalTokens", total); map.put("invalidUsage", false); return map;
    }
    private static void assertSummary(JsonNode result) {
        check(result.path("httpAttempts").asLong() == 4 && result.path("replays").asLong() == 1, "Conflated HTTP/replay evidence: " + result);
        check(result.path("pendingAttempts").asLong() == 1 && result.path("retries").asLong() == 1, "Missing pending/retry evidence");
        check(result.path("inputTokens").path("reported").asText().equals("9223372036854775814"), "Token sum overflow or unknowns coerced");
        check(result.path("inputTokens").path("unknown").asLong() == 2, "Unreported usage hidden");
        check(result.path("cachedInputTokens").path("reported").asText().equals("0"), "True zero lost");
        check(!result.toString().contains(ORIGINAL) && !result.toString().contains("private prompt"), "Usage disclosed text");
    }
    private static Object invoke(ObjectMapper json, Object target, String name, Object... values) throws Exception {
        var method = Arrays.stream(target.getClass().getMethods()).filter(m -> m.getName().equals(name) && m.getParameterCount() == values.length).findFirst().orElseThrow();
        var args = values.clone(); for (int i = 0; i < args.length; i++) if (args[i] instanceof Map<?,?>) args[i] = json.convertValue(args[i], method.getParameterTypes()[i]);
        try { return method.invoke(target, args); } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }
    private static JsonNode get(HttpClient client, ObjectMapper json, String url, int status) throws Exception {
        String auth = Base64.getEncoder().encodeToString(("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        var response = client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Basic " + auth).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == status, "HTTP " + response.statusCode() + " expected " + status + ": " + response.body());
        if (status == 200) check(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"), "Usage responses are cacheable");
        return json.readTree(response.body());
    }
    interface Action { void run() throws Exception; }
    private static void expectFailure(Action action, String message) throws Exception { try { action.run(); } catch (PortfolioException expected) { return; } throw new AssertionError(message); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
