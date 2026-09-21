package com.taxonomy.portfolio.reformulation;

import com.taxonomy.TaxonomyApplication;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.reformulation.*;
import com.taxonomy.workspace.service.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Real HTTP/persistence read contract. Checkpoint outputs are explicit authored storage fixtures. */
public final class ReformulationProgressChecks {
    private static final String ORIGINAL = "Arbeitszeiterfassung. Keine automatische Übernahme.";
    private static final String PASSWORD = "Progress-Test-Only-983!";
    private ReformulationProgressChecks() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]); Files.createDirectories(directory);
        boolean verifyRestart = args.length > 1 && args[1].equals("read");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.datasource.url=jdbc:hsqldb:file:" + directory.resolve("db").toAbsolutePath() + ";shutdown=true",
                "--spring.datasource.username=SA", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--embedding.enabled=false", "--taxonomy.init.async=false", "--llm.mock=true",
                "--taxonomy.admin-password=" + PASSWORD, "--taxonomy.security.require-password-change=false")) {
            var json = app.getBean(ObjectMapper.class);
            var projects = app.getBean(ProjectPortfolioService.class);
            var proposals = app.getBean(ReformulationService.class);
            var workspaces = app.getBean(WorkspaceManager.class);
            var jdbc = app.getBean(JdbcTemplate.class);
            String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
            WorkspaceContext scope;
            long project, requirement;
            String proposal, run;
            if (verifyRestart) {
                var saved = Files.readAllLines(directory.resolve("identity"));
                scope = new WorkspaceContext("admin", saved.get(0), saved.get(1), saved.get(2));
                project = Long.parseLong(saved.get(3)); requirement = Long.parseLong(saved.get(4)); proposal = saved.get(5); run = saved.get(6);
                workspaces.switchWorkspace("admin", scope.workspaceId());
            } else {
                var workspace = workspaces.createWorkspace("admin", "Partial result inspection", "Read-only HTTP regression");
                workspace = workspaces.provisionWorkspaceRepository("admin", workspace.getWorkspaceId());
                workspaces.switchWorkspace("admin", workspace.getWorkspaceId());
                scope = new WorkspaceContext("admin", workspace.getWorkspaceId(), workspace.getCurrentBranch(), workspace.getSourceRepositoryId());
                var p = projects.createProject(new CreateProjectRequest("PROGRESS", "Progress", "Read fixture", ProjectStatus.ACTIVE, null, null, null, null), "admin", scope);
                project = p.id();
                var r = projects.createRequirement(project, new CreateRequirementRequest("TIME", "Time recording", ORIGINAL, RequirementStatus.APPROVED,
                        50, Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.CONFIRMED, "admin", "Original", null), "admin", scope);
                requirement = r.id();
                var analyses = app.getBean(PortfolioAnalysisPersistenceService.class);
                var job = analyses.createOrReuseJob(project, List.of(requirement), null, 25, "progress-fixture", "admin", scope);
                // Use the actual catalog root, not guessed taxonomy identifiers.
                var root = app.getBean(TaxonomyService.class).getFullTree().stream().filter(n -> n.getCode().equals("BP")).findFirst().orElseThrow();
                var analysis = new AnalysisResult(Map.of(root.getCode(), 50), List.of(root)); analysis.setStatus("SUCCESS");
                String snapshot = UUID.randomUUID().toString();
                analyses.persistSnapshot(job.items().getFirst().id(), job.id(), project, PortfolioScope.key("admin", scope), snapshot, "progress-session", analysis,
                        null, null, null, null, null, "p", "t", "admin", scope, 1);
                var offer = proposals.create(project, requirement, new ReformulationDtos.CreateRequest(r.currentVersionId(), snapshot, "de"), "admin", scope);
                proposal = offer.id();
                var started = proposals.beginRun(project, requirement, proposal, 1, "TEST", "storage-only", "p", "s", "Do not expose this prompt", "admin", scope);
                run = started.id(); proposals.running(project, requirement, proposal, run, "admin", scope);
            }
            String path = "/api/projects/" + project + "/requirements/" + requirement + "/reformulations/" + proposal;
            // Verify real authenticated workspace routing before testing the new feature.
            get(client, json, base + path, 200);
            var originalBefore = projects.getRequirement(project, requirement, "admin", scope);
            var proposalBefore = proposals.get(project, requirement, proposal, "admin", scope);
            String url = base + path + "/synthesis-runs/" + run + "/progress";
            var statistics=app.getBean(jakarta.persistence.EntityManagerFactory.class).unwrap(org.hibernate.SessionFactory.class).getStatistics();
            statistics.setStatisticsEnabled(true);
            long fullProposalLoads=statistics.getEntityStatistics(ReformulationProposal.class.getName()).getLoadCount();
            long fullRevisionLoads=statistics.getEntityStatistics(ReformulationRevision.class.getName()).getLoadCount();
            var empty = get(client, json, url, 200); // Baseline endpoint was absent (404).
            check(statistics.getEntityStatistics(ReformulationProposal.class.getName()).getLoadCount()==fullProposalLoads
                    && statistics.getEntityStatistics(ReformulationRevision.class.getName()).getLoadCount()==fullRevisionLoads,
                    "Progress polling loaded full proposal/revision payloads");
            statistics.setStatisticsEnabled(false);
            if (!verifyRestart) {
                check(empty.path("runCheckpointCount").asLong() == 0, "New run already has results");
                var q = new DecisionQuestion("question-storage-fixture", new DecisionQuestion.Key("time", "entry", "BP"),
                        "Browser oder Terminal? <img src=x onerror=alert(1)>",
                        List.of(new DecisionQuestion.Discovery("BP", "Stored context", "Unresolved in original", List.of(), List.of("BP"), List.of())),
                        List.of(), new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.SINGLE_CHOICE, List.of("Browser", "Terminal"), null, null, null),
                        List.of(), List.of(), "Compare alternatives", DecisionQuestion.State.OPEN);
                var result = new NodeSynthesisResult("BP", "Teiltext – noch ungeprüft", List.of(), List.of(), List.of(q), List.of(), List.of(), List.of());
                for (String kind : List.of("NODE", "NODE_GROUP", "NODE_AGGREGATE", "REWORD"))
                    proposals.completeCheckpoint(project, requirement, proposal, run, kind, com.taxonomy.identity.StableIdentityHash.sha256(kind), json.writeValueAsString(result), "admin", scope);
                proposals.completeCheckpoint(project, requirement, proposal, run, "RECONCILE", com.taxonomy.identity.StableIdentityHash.sha256("reconcile"),
                        json.writeValueAsString(new ReconciliationResult(List.of("BP"), Map.of(), List.of())), "admin", scope);
            }
            if (!verifyRestart) jdbc.update("update reformulation_node_checkpoint set created_at=? where run_id=?",
                    java.time.OffsetDateTime.parse("2026-09-21T12:00:00Z"), run);
            var page = get(client, json, url + "?limit=2", 200);
            check(page.path("runCheckpointCount").asLong() == 5, "Wrong persisted result count: " + page);
            check(page.path("proposalCheckpointCount").asLong() >= 5, "Proposal count lost results");
            check(page.path("items").size() == 2, "Unbounded metadata page");
            check(!page.toString().contains("Do not expose this prompt") && !page.toString().contains("Browser oder Terminal"), "Overview loads internal prompts or full results");
            check(!page.has("percentComplete") && !page.has("llmCalls"), "Invented completion or request metric");
            var ids = new LinkedHashSet<String>();
            int pages = 0;
            while (true) {
                check(++pages < 10, "Pagination did not terminate");
                for (var item : page.path("items")) {
                    String id = item.path("checkpointId").asText(); check(ids.add(id), "Pagination repeats a result");
                    var detail = get(client, json, url + "/checkpoints/" + id, 200);
                    check(detail.path("sourceRevision").asLong() == 1, "Wrong origin revision");
                    if (!item.path("kind").asText().equals("RECONCILE")) {
                        check(detail.path("node").path("summary").asText().equals("Teiltext – noch ungeprüft"), "Partial text changed");
                        check(detail.path("node").path("questionProposals").get(0).path("id").asText().equals("question-storage-fixture"), "Question identity lost");
                    } else check(detail.path("reconciliation").path("affectedSectionIds").get(0).asText().equals("BP"), "Reconciliation evidence lost");
                }
                String after = page.path("nextAfter").asText(""); if (after.isEmpty()) break;
                page = get(client, json, url + "?limit=2&after=" + after, 200);
            }
            check(ids.size() == 5, "Pagination lost results");
            get(client, json, url + "?limit=0", 400); get(client, json, url + "?limit=51", 400);
            get(client, json, url + "?after=not-a-checkpoint", 400);
            get(client, json, url + "?after=&limit=50", 200);
            get(client, json, url.replace(run, UUID.randomUUID().toString()), 404);
            get(client, json, url + "/checkpoints/" + "0".repeat(64), 404);
            var anonymous = client.send(HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            check(anonymous.statusCode() == 401, "Unauthenticated progress leaked");
            check(proposals.get(project, requirement, proposal, "admin", scope).equals(proposalBefore), "Inspection edited proposal");
            check(projects.getRequirement(project, requirement, "admin", scope).equals(originalBefore), "Inspection edited original");
            if (!verifyRestart) {
                proposals.cancelRun(project, requirement, proposal, run, 1, "admin", scope);
                check(get(client, json, url, 200).path("status").asText().equals("CANCELLED"), "Cancelled run loses progress");
                var second = proposals.beginRun(project, requirement, proposal, 1, "TEST", "storage-only", "p", "s", "frozen", "admin", scope);
                String secondUrl = base + path + "/synthesis-runs/" + second.id() + "/progress";
                var secondProgress = get(client, json, secondUrl, 200);
                check(secondProgress.path("runCheckpointCount").asLong() == 0 && secondProgress.path("proposalCheckpointCount").asLong() == 5, "Prior cached results counted as new work");
                get(client, json, secondUrl + "/checkpoints/" + ids.iterator().next(), 404);
                get(client, json, secondUrl + "?after=" + ids.iterator().next(), 404);
                proposals.cancelRun(project, requirement, proposal, second.id(), 1, "admin", scope);
                // Corrupt/legacy payload must never be returned as arbitrary raw data.
                String id = ids.iterator().next();
                String stored = jdbc.queryForObject("select result_payload from reformulation_node_checkpoint where id=?", String.class, id);
                jdbc.update("update reformulation_node_checkpoint set result_payload=? where id=?", "\"sensitive-untyped-data\"", id);
                var unavailable = get(client, json, url + "/checkpoints/" + id, 409);
                check(!unavailable.toString().contains("sensitive-untyped-data"), "Untyped payload exposed");
                jdbc.update("update reformulation_node_checkpoint set result_payload=? where id=?", stored, id);
                String originalKind=jdbc.queryForObject("select task_kind from reformulation_node_checkpoint where id=?",String.class,id);
                jdbc.update("update reformulation_node_checkpoint set task_kind=? where id=?", "UNKNOWN", id);
                get(client,json,url+"/checkpoints/"+id,409);
                jdbc.update("update reformulation_node_checkpoint set task_kind=? where id=?",originalKind,id);
                proposals.saveDraft(project, requirement, proposal, 1, new ReformulationDtos.SaveDraftRequest("Menschliche Überarbeitung", "Preserve manual wording"), "admin", scope);
                check(get(client, json, url, 200).path("sourceRevision").asLong()==1, "Progress rebound to newer manual draft");
                get(client,json,url+"/checkpoints/"+id,200);
                check(proposals.get(project,requirement,proposal,"admin",scope).currentRevision().text().equals("Menschliche Überarbeitung"), "Inspection overwrote newer manual text");
                Files.write(directory.resolve("identity"), List.of(scope.workspaceId(), scope.currentBranch(), scope.repositoryId(), Long.toString(project), Long.toString(requirement), proposal, run));
                // Same authenticated actor, different saved workspace: access denied without revealing metadata.
                var foreign = workspaces.createWorkspace("admin", "Foreign", "Scope isolation");
                foreign = workspaces.provisionWorkspaceRepository("admin", foreign.getWorkspaceId()); workspaces.switchWorkspace("admin", foreign.getWorkspaceId());
                get(client, json, url, 404); get(client, json, url + "/checkpoints/" + id, 404);
            }
            System.out.println(verifyRestart ? "REFORMULATION_PROGRESS_RESTART_OK" : "REFORMULATION_PROGRESS_HTTP_OK");
        }
    }
    private static JsonNode get(HttpClient client, ObjectMapper json, String url, int expected) throws Exception {
        String basic = Base64.getEncoder().encodeToString(("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        var response = client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Basic " + basic).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == expected, "HTTP " + response.statusCode() + " instead of " + expected + " for " + URI.create(url).getPath() + ": " + response.body());
        if (expected==200 && url.contains("/progress")) check(response.headers().firstValue("cache-control").orElse("").contains("no-store"), "Private progress must not be cached");
        return json.readTree(response.body());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
