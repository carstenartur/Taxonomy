package com.taxonomy.portfolio.reformulation;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.TaxonomyApplication;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.composition.reformulation.*;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.workspace.service.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

/** Real worker + gateway + file DB; HTTP replay checks that admission was committed before each request. */
public final class ReformulationUsageWorkerChecks {
    private static final String ORIGINAL = "Arbeitszeiterfassung am Terminal. Keine Browseroberfläche.";
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]); Files.createDirectories(directory);
        String mode = args.length > 1 ? args[1] : "complete";
        boolean recover = mode.equals("recover"), kill = mode.equals("produce");
        var saved = recover ? Files.readAllLines(directory.resolve("worker-identity")) : List.<String>of();
        var json = new ObjectMapper(); var calls = new AtomicInteger(); var failure = new AtomicReference<Throwable>();
        var application = new AtomicReference<ConfigurableApplicationContext>();
        var remote = HttpServer.create(new InetSocketAddress("127.0.0.1", recover ? Integer.parseInt(saved.get(7)) : 0), 0);
        remote.createContext("/v1/chat/completions", exchange -> {
            try {
                int call = calls.incrementAndGet();
                var db = application.get().getBean(JdbcTemplate.class);
                check(db.queryForObject("select count(*) from reformulation_usage_attempt where completed_at is null", Long.class) > 0,
                        "Worker sent HTTP without a committed usage start");
                if (kill && call == 2) {
                    check(db.queryForObject("select count(*) from reformulation_node_checkpoint", Long.class) == 1, "First child not checkpointed");
                    String checkpoint = db.queryForObject("select result_payload from reformulation_node_checkpoint where task_kind='NODE'", String.class);
                    String questionId = json.readTree(checkpoint).at("/questionProposals/0/id").asText();
                    check(!questionId.isBlank(), "Committed child question missing");
                    Files.writeString(directory.resolve("saved-question-id"), questionId);
                    Files.writeString(directory.resolve("usage-kill-ready"), "ready");
                    new CountDownLatch(1).await(90, TimeUnit.SECONDS);
                    throw new AssertionError("Producer must be forcibly terminated");
                }
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String prompt = json.readTree(request).at("/messages/0/content").asText();
                check(prompt.contains(ORIGINAL), "Lost original text");
                check(!prompt.contains("VALIDATION_ERRORS"), "Authored answer must satisfy the exact parser contract");
                Object content;
                if (prompt.contains("RECONCILIATION_DATA_JSON\n")) {
                    content = Map.of("affectedSectionIds", List.of(), "sourceResolutions", List.of(), "findings", List.of());
                } else {
                    var input = json.readTree(prompt.substring(prompt.indexOf("INPUT_DATA_JSON\n") + 16));
                    var ids = new LinkedHashSet<String>(); input.path("directContributions").forEach(s -> ids.add(s.path("id").asText()));
                    String node = input.path("nodeId").asText();
                    var question = Map.ofEntries(Map.entry("subject", "time"), Map.entry("dimension", "correction"), Map.entry("scope", node),
                            Map.entry("wording", "Wer darf Buchungen korrigieren?"), Map.entry("rationale", "Nicht im Original festgelegt"),
                            Map.entry("affectedStatementIds", ids), Map.entry("sourceSpans", List.of()), Map.entry("nodeIds", List.of(node)), Map.entry("edgeIds", List.of()),
                            Map.entry("answerSchema", json.readTree("{\"kind\":\"TEXT\",\"options\":[],\"unit\":null,\"minimum\":null,\"maximum\":null}")), Map.entry("prerequisites", List.of()), Map.entry("consequences", "Rollen klären"));
                    content = Map.of("summary", "Terminalerfassung", "statementProposals", List.of(), "preservedStatementIds", ids,
                            "questionProposals", List.of(question), "preservedQuestionIds", List.of(), "uncoveredSourceRefs", List.of(), "conflictCandidates", List.of());
                }
                byte[] bytes = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", json.writeValueAsString(content)))),
                        "usage", Map.of("prompt_tokens", 7, "completion_tokens", 3, "total_tokens", 10)));
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable error) { failure.set(error); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        remote.start();
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.datasource.url=jdbc:hsqldb:file:" + directory.resolve("worker-db").toAbsolutePath() + ";shutdown=true;hsqldb.write_delay=false",
                "--spring.datasource.username=SA", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--embedding.enabled=false", "--taxonomy.init.async=false", "--llm.mock=false", "--llm.provider=CUSTOM_OPENAI",
                "--custom.llm.model=usage-worker", "--custom.llm.api.key=", "--custom.llm.url=http://127.0.0.1:" + remote.getAddress().getPort() + "/v1/chat/completions",
                "--reformulation.recovery.lease-ms=3000", "--reformulation.recovery.poll-ms=250", "--reformulation.recovery.heartbeat-ms=500",
                "--gemini.api.key=", "--openai.api.key=", "--deepseek.api.key=", "--qwen.api.key=", "--llama.api.key=", "--mistral.api.key=")) {
            application.set(app);
            var projects = app.getBean(ProjectPortfolioService.class); var proposals = app.getBean(ReformulationService.class);
            WorkspaceContext context; long project, requirement; String offer, run;
            if (recover) {
                context = new WorkspaceContext("architect", saved.get(0), saved.get(1), saved.get(2));
                project = Long.parseLong(saved.get(3)); requirement = Long.parseLong(saved.get(4)); offer = saved.get(5); run = saved.get(6);
            } else {
                var workspaces = app.getBean(WorkspaceManager.class); var w = workspaces.createWorkspace("architect", "Usage worker", "Actual catalogue root");
                w = workspaces.provisionWorkspaceRepository("architect", w.getWorkspaceId()); context = new WorkspaceContext("architect", w.getWorkspaceId(), w.getCurrentBranch(), w.getSourceRepositoryId());
                var p = projects.createProject(new CreateProjectRequest("P", "Usage", "Fixture", ProjectStatus.ACTIVE, null, null, null, null), "architect", context); project = p.id();
                var r = projects.createRequirement(project, new CreateRequirementRequest("R", "Time", ORIGINAL, RequirementStatus.APPROVED,
                        50, Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.CONFIRMED, "architect", "Source", null), "architect", context); requirement = r.id();
                var root = app.getBean(TaxonomyService.class).getFullTree().stream().filter(n -> n.getCode().equals("BP")).findFirst().orElseThrow();
                var analyses = app.getBean(PortfolioAnalysisPersistenceService.class); var job = analyses.createOrReuseJob(project, List.of(requirement), null, 25, "usage-worker", "architect", context);
                String snapshot = UUID.randomUUID().toString(); var analysis = new AnalysisResult(Map.of(root.getCode(), 50), List.of(root)); analysis.setStatus("SUCCESS");
                analyses.persistSnapshot(job.items().getFirst().id(), job.id(), project, PortfolioScope.key("architect", context), snapshot, "session", analysis,
                        null, null, null, null, null, "p", "t", "architect", context, 1);
                offer = proposals.create(project, requirement, new ReformulationDtos.CreateRequest(r.currentVersionId(), snapshot, "de"), "architect", context).id();
                run = app.getBean(ReformulationExecutionService.class).start(project, requirement, offer, 1, "architect", context).id();
                Files.write(directory.resolve("worker-identity"), List.of(context.workspaceId(), context.currentBranch(), context.repositoryId(), Long.toString(project), Long.toString(requirement), offer, run, Integer.toString(remote.getAddress().getPort())));
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            String status;
            do {
                status = proposals.runs(project, requirement, offer, "architect", context).getLast().status();
                if (failure.get() != null) throw new AssertionError("Worker metering contract", failure.get());
                if (!status.equals("RUNNING") && !status.equals("QUEUED")) break;
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            check(status.equals("COMPLETED"), "Worker did not complete: " + status);
            var summary = app.getBean(ReformulationUsageService.class).summary(project, requirement, offer, run, "architect", context);
            check(summary.httpAttempts() == (recover ? 3 : 2) && summary.pendingAttempts() == (recover ? 1 : 0), "Incorrect actual attempt evidence: " + summary);
            check(summary.inputTokens().reported().equals("14") && summary.inputTokens().unknown() == (recover ? 1 : 0), "Missing outcome was made zero");
            check(calls.get() == (recover ? 1 : 2), "Checkpoint reuse did not avoid provider work");
            var questions = proposals.get(project, requirement, offer, "architect", context).currentRevision().questions();
            check(questions.size() == 1, "Question lost on restart");
            if (recover) check(questions.getFirst().id().equals(Files.readString(directory.resolve("saved-question-id"))), "Recovery changed the committed question identity");
            check(projects.listRequirementVersions(project, requirement, "architect", context).size() == 1, "Worker changed original version history");
            System.out.println(recover ? "REFORMULATION_USAGE_KILL_RECOVERY_OK" : "REFORMULATION_USAGE_WORKER_OK");
        } finally { remote.stop(0); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
