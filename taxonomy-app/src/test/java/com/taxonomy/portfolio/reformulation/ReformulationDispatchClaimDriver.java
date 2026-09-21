package com.taxonomy.portfolio.reformulation;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.TaxonomyApplication;
import com.taxonomy.analysis.reformulation.CrossTaxonomyReconciler;
import com.taxonomy.analysis.reformulation.FrozenReformulationEngine;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.composition.reformulation.ReformulationExecutionService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.workspace.service.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.task.AsyncTaskExecutor;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A repeated dispatch must not fail the worker that already owns the run. */
public final class ReformulationDispatchClaimDriver {
    private static final String ORIGINAL = "Ausschließlich Terminal, keine Browseroberfläche. Frist: 2 Sekunden.";
    private ReformulationDispatchClaimDriver() {}

    public static void main(String[] args) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var remoteFailure = new AtomicReference<Throwable>();
        var json = new ObjectMapper();
        var remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/v1/chat/completions", exchange -> {
            try {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    entered.countDown();
                    check(release.await(30, TimeUnit.SECONDS), "Release of first provider response timed out");
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String prompt = json.readTree(body).at("/messages/0/content").asText();
                check(prompt.contains(ORIGINAL), "Original omitted from provider prompt");
                Object response;
                if (prompt.contains("RECONCILIATION_DATA_JSON\n")) {
                    response = Map.of("affectedSectionIds", List.of(), "sourceResolutions", List.of(), "findings", List.of());
                } else {
                    var input = json.readTree(prompt.substring(prompt.indexOf("INPUT_DATA_JSON\n") + 16));
                    var statements = new LinkedHashSet<String>();
                    var questions = new LinkedHashSet<String>();
                    input.path("directContributions").forEach(s -> statements.add(s.path("id").asText()));
                    input.path("openDecisions").forEach(q -> questions.add(q.path("id").asText()));
                    input.path("children").forEach(child -> {
                        child.path("statementProposals").forEach(s -> statements.add(s.path("id").asText()));
                        child.path("questionProposals").forEach(q -> questions.add(q.path("id").asText()));
                    });
                    response = Map.of("summary", "Terminalerfassung", "statementProposals", List.of(),
                            "preservedStatementIds", statements, "questionProposals", List.of(),
                            "preservedQuestionIds", questions, "uncoveredSourceRefs", List.of(), "conflictCandidates", List.of());
                }
                byte[] bytes = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", json.writeValueAsString(response))))));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Throwable failure) {
                remoteFailure.set(failure);
                exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        });
        remote.start();
        var workers = Executors.newSingleThreadExecutor();
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.datasource.url=jdbc:hsqldb:mem:dispatch-claim-" + UUID.randomUUID(),
                "--spring.datasource.username=SA", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver", "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--embedding.enabled=false", "--taxonomy.init.async=false", "--llm.mock=false",
                "--llm.provider=CUSTOM_OPENAI", "--custom.llm.model=claim-test", "--custom.llm.api.key=",
                "--custom.llm.url=http://127.0.0.1:" + remote.getAddress().getPort() + "/v1/chat/completions",
                "--gemini.api.key=", "--openai.api.key=", "--deepseek.api.key=", "--qwen.api.key=", "--llama.api.key=", "--mistral.api.key=")) {
            var projects = app.getBean(ProjectPortfolioService.class);
            var proposals = app.getBean(ReformulationService.class);
            var workspaces = app.getBean(WorkspaceManager.class);
            var workspace = workspaces.createWorkspace("architect", "Repeated dispatch", "Claim ownership regression");
            workspace = workspaces.provisionWorkspaceRepository("architect", workspace.getWorkspaceId());
            var context = new WorkspaceContext("architect", workspace.getWorkspaceId(), workspace.getCurrentBranch(), workspace.getSourceRepositoryId());
            var project = projects.createProject(new CreateProjectRequest("P", "Dispatch", "Fixture", ProjectStatus.ACTIVE, null, null, null, null), "architect", context);
            var requirement = projects.createRequirement(project.id(), new CreateRequirementRequest("R", "Original", ORIGINAL,
                    RequirementStatus.APPROVED, 50, Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.CONFIRMED, "architect", "Original", null), "architect", context);
            var analyses = app.getBean(PortfolioAnalysisPersistenceService.class);
            var job = analyses.createOrReuseJob(project.id(), List.of(requirement.id()), null, 25, "dispatch-claim", "architect", context);
            var node = new TaxonomyNodeDto(); node.setCode("BP-1"); node.setNameEn("Time capture");
            var analysis = new AnalysisResult(Map.of("BP-1", 50), List.of(node)); analysis.setStatus("SUCCESS");
            String snapshot = UUID.randomUUID().toString();
            analyses.persistSnapshot(job.items().getFirst().id(), job.id(), project.id(), PortfolioScope.key("architect", context),
                    snapshot, "session", analysis, null, null, null, null, null, "p", "t", "architect", context, 1);
            var proposal = proposals.create(project.id(), requirement.id(), new ReformulationDtos.CreateRequest(requirement.currentVersionId(), snapshot, "de"), "architect", context);
            var dispatched = new AtomicReference<Runnable>();
            AsyncTaskExecutor executor = command -> {
                check(dispatched.compareAndSet(null, command), "Unexpected additional dispatch");
            };
            var service = new ReformulationExecutionService(proposals, app.getBean(FrozenReformulationEngine.class),
                    app.getBean(CrossTaxonomyReconciler.class), app.getBean(LlmProviderConfig.class), executor, app.getBean(ObjectMapper.class), app.getBean(ReformulationRecoveryService.class));
            var run = service.start(project.id(), requirement.id(), proposal.id(), 1, "architect", context);
            Runnable work = Objects.requireNonNull(dispatched.get());
            Future<?> first = workers.submit(work);
            try {
                check(entered.await(30, TimeUnit.SECONDS), "First worker did not reach the real gateway");
                // Deliberately deliver the identical persisted job twice. Only one may own it.
                try { work.run(); } catch (PortfolioException claimRejected) { /* Losing dispatch may report conflict. */ }
                var during = proposals.runs(project.id(), requirement.id(), proposal.id(), "architect", context).getFirst();
                check(during.status().equals("RUNNING"), "Losing dispatch changed the winning run to " + during.status());
                check(calls.get() == 1, "Duplicate dispatch issued provider work");
            } finally { release.countDown(); }
            first.get(45, TimeUnit.SECONDS);
            check(remoteFailure.get() == null, "Invalid playback response: " + remoteFailure.get());
            var completed = proposals.runs(project.id(), requirement.id(), proposal.id(), "architect", context).getFirst();
            check(completed.status().equals("COMPLETED"), "Winning worker did not complete: " + completed.failureCode());
            check(proposals.get(project.id(), requirement.id(), proposal.id(), "architect", context).currentRevision().number() == 2,
                    "Expected exactly one published proposal revision");
            try { work.run(); } catch (PortfolioException claimRejected) { /* Completed work cannot be reclaimed. */ }
            check(proposals.runs(project.id(), requirement.id(), proposal.id(), "architect", context).getFirst().equals(completed),
                    "Repeated completion changed historical run");
            check(projects.getRequirement(project.id(), requirement.id(), "architect", context).currentVersionId().equals(requirement.currentVersionId()),
                    "Worker altered the active requirement");
            check(projects.listRequirementVersions(project.id(), requirement.id(), "architect", context).size() == 1, "Worker created a requirement version");
            System.out.println("REFORMULATION_DISPATCH_CLAIM_OK run=" + run.id() + " calls=" + calls.get());
        } finally {
            release.countDown(); workers.shutdownNow(); remote.stop(0);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
