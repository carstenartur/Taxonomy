package com.taxonomy.portfolio.reformulation;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.TaxonomyApplication;
import com.taxonomy.analysis.reformulation.CrossTaxonomyReconciler;
import com.taxonomy.analysis.reformulation.FrozenReformulationEngine;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.composition.reformulation.ReformulationExecutionService;
import com.taxonomy.composition.reformulation.ReformulationRecoveryCoordinator;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.workspace.service.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real lease/usage persistence and HTTP transport; only dispatch and provider timing are controlled. */
public final class ReformulationLocalRecoveryChecks {
    private static final String ORIGINAL = "Arbeitszeiterfassung am Terminal. Keine Browseroberfläche.";
    private ReformulationLocalRecoveryChecks() {}

    public static void main(String[] args) throws Exception {
        boolean shutdown = args.length > 0 && args[0].equals("shutdown");
        boolean finalizationRace = args.length > 0 && args[0].equals("finalization-race");
        boolean finalizationAdmitted = args.length > 0 && args[0].equals("finalization-admitted");
        var permitObserved = new AtomicBoolean();
        var finishEntered = new CountDownLatch(1); var releaseFinish = new CountDownLatch(1);
        var firstEntered = new CountDownLatch(1); var releaseFirst = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1); var releaseSecond = new CountDownLatch(1);
        var calls = new AtomicInteger(); var remoteFailure = new AtomicReference<Throwable>();
        var json = new ObjectMapper();
        var remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var httpWorkers = Executors.newFixedThreadPool(2);
        remote.setExecutor(httpWorkers);
        remote.createContext("/v1/chat/completions", exchange -> {
            try {
                int call = calls.incrementAndGet();
                if (call == 1) { firstEntered.countDown(); await(releaseFirst); }
                if (call == 2) { secondEntered.countDown(); await(releaseSecond); }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String prompt = json.readTree(body).at("/messages/0/content").asText();
                check(prompt.contains(ORIGINAL), "Original omitted");
                check(!prompt.contains("VALIDATION_ERRORS"), "Unexpected parser repair");
                Object response;
                if (prompt.contains("RECONCILIATION_DATA_JSON\n")) {
                    response = Map.of("affectedSectionIds", List.of(), "sourceResolutions", List.of(), "findings", List.of());
                } else {
                    var input = json.readTree(prompt.substring(prompt.indexOf("INPUT_DATA_JSON\n") + 16));
                    var ids = new LinkedHashSet<String>(); input.path("directContributions").forEach(s -> ids.add(s.path("id").asText()));
                    response = Map.of("summary", "Terminalerfassung", "statementProposals", List.of(), "preservedStatementIds", ids,
                            "questionProposals", List.of(), "preservedQuestionIds", List.of(), "uncoveredSourceRefs", List.of(), "conflictCandidates", List.of());
                }
                byte[] bytes = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", json.writeValueAsString(response)))),
                        "usage", Map.of("prompt_tokens", 7, "completion_tokens", 3, "total_tokens", 10)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable failure) { remoteFailure.set(failure); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        remote.start();
        var threads = new ArrayList<Thread>();
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.datasource.url=jdbc:hsqldb:mem:local-recovery-" + UUID.randomUUID(),
                "--spring.datasource.username=SA", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--embedding.enabled=false", "--taxonomy.init.async=false", "--llm.mock=false", "--llm.provider=CUSTOM_OPENAI",
                "--custom.llm.model=local-recovery", "--custom.llm.api.key=", "--custom.llm.url=http://127.0.0.1:" + remote.getAddress().getPort() + "/v1/chat/completions",
                "--gemini.api.key=", "--openai.api.key=", "--deepseek.api.key=", "--qwen.api.key=", "--llama.api.key=", "--mistral.api.key=")) {
            // Stop automatic timers BEFORE creating test work; this test drives the same real methods explicitly.
            app.getBean(ReformulationRecoveryCoordinator.class).stop();
            var projects = app.getBean(ProjectPortfolioService.class); var proposals = app.getBean(ReformulationService.class);
            var workspaces = app.getBean(WorkspaceManager.class); var w = workspaces.createWorkspace("architect", "Local recovery", "Claim capacity regression");
            w = workspaces.provisionWorkspaceRepository("architect", w.getWorkspaceId());
            var context = new WorkspaceContext("architect", w.getWorkspaceId(), w.getCurrentBranch(), w.getSourceRepositoryId());
            var project = projects.createProject(new CreateProjectRequest("P", "Recovery", "Fixture", ProjectStatus.ACTIVE, null, null, null, null), "architect", context);
            var r = projects.createRequirement(project.id(), new CreateRequirementRequest("R", "Time", ORIGINAL, RequirementStatus.APPROVED,
                    50, Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.CONFIRMED, "architect", "Original", null), "architect", context);
            var root = app.getBean(TaxonomyService.class).getFullTree().stream().filter(n -> n.getCode().equals("BP")).findFirst().orElseThrow();
            var analyses = app.getBean(PortfolioAnalysisPersistenceService.class);
            var job = analyses.createOrReuseJob(project.id(), List.of(r.id()), null, 25, "local-recovery", "architect", context);
            String snapshot = UUID.randomUUID().toString(); var result = new AnalysisResult(Map.of(root.getCode(), 50), List.of(root)); result.setStatus("SUCCESS");
            analyses.persistSnapshot(job.items().getFirst().id(), job.id(), project.id(), PortfolioScope.key("architect", context), snapshot, "session", result,
                    null, null, null, null, null, "p", "t", "architect", context, 1);
            var offer = proposals.create(project.id(), r.id(), new ReformulationDtos.CreateRequest(r.currentVersionId(), snapshot, "de"), "architect", context);
            var queued = new CopyOnWriteArrayList<Runnable>();
            AsyncTaskExecutor executor = queued::add;
            var recovery = app.getBean(ReformulationRecoveryService.class);
            if (finalizationRace || finalizationAdmitted) {
                // Hold the actual transactional call before it acquires DB locks, after
                // the worker's old local retired check. A driver can consume interruption.
                var proxy = new ProxyFactory(recovery); proxy.setProxyTargetClass(true);
                proxy.addAdvice((MethodInterceptor) invocation -> {
                    if (invocation.getMethod().getName().equals("finish")) {
                        if (finalizationAdmitted && invocation.getArguments().length == 4) {
                            var original = (java.util.function.BooleanSupplier) invocation.getArguments()[3];
                            invocation.getArguments()[3] = (java.util.function.BooleanSupplier) () -> {
                                boolean accepted = original.getAsBoolean();
                                permitObserved.set(accepted); finishEntered.countDown();
                                holdFinalization(releaseFinish);
                                return accepted;
                            };
                        } else {
                            finishEntered.countDown(); holdFinalization(releaseFinish);
                        }
                    }
                    return invocation.proceed();
                });
                recovery = (ReformulationRecoveryService) proxy.getProxy();
                releaseFirst.countDown(); releaseSecond.countDown();
            }
            var execution = new ReformulationExecutionService(proposals, app.getBean(FrozenReformulationEngine.class), app.getBean(CrossTaxonomyReconciler.class),
                    app.getBean(LlmProviderConfig.class), executor, app.getBean(ObjectMapper.class), recovery, app.getBean(ReformulationUsageService.class));
            var run = execution.start(project.id(), r.id(), offer.id(), 1, "architect", context);
            check(queued.size() == 1, "Initial dispatch absent");
            var workerFailure = new AtomicReference<Throwable>();
            Thread first = launch(queued.getFirst(), workerFailure); threads.add(first); await(firstEntered);
            var db = app.getBean(JdbcTemplate.class);
            if (finalizationRace || finalizationAdmitted) {
                await(finishEntered);
                if (finalizationAdmitted) check(permitObserved.get(), "Worker finalization has no lock-scoped permit");
                execution.shutdown();
                releaseFinish.countDown(); join(first);
                var after = proposals.runs(project.id(), r.id(), offer.id(), "architect", context).getFirst();
                check(after.status().equals(finalizationAdmitted ? "COMPLETED" : "RUNNING"),
                        "Unexpected shutdown/finalization order: " + after.status());
                check(proposals.get(project.id(), r.id(), offer.id(), "architect", context).currentRevision().number() == (finalizationAdmitted ? 2 : 1),
                        "Finalization did not respect its single admission order");
                check(db.queryForObject("select active from reformulation_recovery_lease where run_id=?", Boolean.class, run.id()) == !finalizationAdmitted,
                        "Retired-before-finalization run is no longer recoverable");
                check(calls.get() == 2, "Unexpected additional provider work");
                System.out.println(finalizationAdmitted ? "REFORMULATION_FINALIZATION_ADMITTED_OK" : "REFORMULATION_FINALIZATION_RETIREMENT_OK");
            } else if (shutdown) {
                var coordinator = new ReformulationRecoveryCoordinator(execution, 1000, 1000, 120_000, 1);
                coordinator.stop();
                var extra = proposals.create(project.id(), r.id(), new ReformulationDtos.CreateRequest(r.currentVersionId(), snapshot, "de"), "architect", context);
                try {
                    execution.start(project.id(), r.id(), extra.id(), 1, "architect", context);
                    throw new AssertionError("Stopped execution service admitted a new run");
                } catch (IllegalStateException expected) {
                    check("REFORMULATION_EXECUTOR_STOPPED".equals(expected.getMessage()), "Unexpected shutdown error");
                }
                check(proposals.runs(project.id(), r.id(), extra.id(), "architect", context).isEmpty(), "Shutdown persisted a phantom run");
                releaseFirst.countDown(); join(first);
                check(proposals.runs(project.id(), r.id(), offer.id(), "architect", context).getFirst().status().equals("RUNNING"),
                        "Graceful stop destroyed recoverable work");
                check(calls.get() == 1, "Shutdown initiated another model call");
                check(queued.size() == 1, "Shutdown scheduled another job");
                System.out.println("REFORMULATION_GRACEFUL_STOP_OK");
            } else {
                db.update("update reformulation_recovery_lease set lease_until=? where run_id=?", Timestamp.from(Instant.EPOCH), run.id());
                execution.heartbeat(); execution.recoverAvailable(1);
                check(queued.size() == 2, "Expired local worker permanently consumes the recovery slot");
                // Its finally runs while the successor is queued but has NOT claimed yet.
                releaseFirst.countDown(); join(first); execution.recoverAvailable(1);
                check(queued.size() == 2, "Old worker cleanup removed the queued successor reservation");
                check(proposals.get(project.id(), r.id(), offer.id(), "architect", context).currentRevision().number() == 1, "Stale worker published a draft");
                Thread second = launch(queued.get(1), workerFailure); threads.add(second); await(secondEntered);
                execution.heartbeat(); execution.recoverAvailable(1);
                check(queued.size() == 2, "Live successor lost its local capacity reservation");
                releaseSecond.countDown(); join(second);
                var finished = proposals.runs(project.id(), r.id(), offer.id(), "architect", context).getFirst();
                check(finished.status().equals("COMPLETED"), "Successor failed: " + finished.failureCode());
                check(proposals.get(project.id(), r.id(), offer.id(), "architect", context).currentRevision().number() == 2, "Expected exactly one published draft");
                check(db.queryForObject("select lease_epoch from reformulation_recovery_lease where run_id=?", Long.class, run.id()) == 2L, "Wrong recovery epoch");
                check(db.queryForObject("select count(*) from reformulation_usage_attempt where run_id=? and completed_at is not null", Long.class, run.id()) == 3L,
                        "Late own usage receipt or successor receipt lost");
                // A delayed duplicate queue delivery cannot reclaim a completed slot or make HTTP calls.
                queued.get(1).run(); check(calls.get() == 3, "Delayed duplicate dispatch issued new HTTP work");
                System.out.println("REFORMULATION_LOCAL_CAPACITY_OK calls=" + calls.get());
            }
            check(workerFailure.get() == null, "Worker threw uncaught failure: " + workerFailure.get());
            check(remoteFailure.get() == null, "Invalid authored response: " + remoteFailure.get());
            check(projects.getRequirement(project.id(), r.id(), "architect", context).currentVersionId().equals(r.currentVersionId()), "Active requirement changed");
            check(projects.listRequirementVersions(project.id(), r.id(), "architect", context).size() == 1, "Additional requirement version created");
        } finally {
            releaseFirst.countDown(); releaseSecond.countDown(); releaseFinish.countDown();
            for (Thread thread : threads) { thread.join(10_000); }
            remote.stop(0); httpWorkers.shutdownNow();
        }
    }
    private static void holdFinalization(CountDownLatch release) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (release.getCount() != 0) {
            check(System.nanoTime() < deadline, "Finalization barrier timed out");
            try { release.await(100, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { /* Model a driver consuming interruption. */ }
        }
        Thread.interrupted();
    }
    private static Thread launch(Runnable work, AtomicReference<Throwable> error) {
        return Thread.ofPlatform().daemon().start(() -> { try { work.run(); } catch (Throwable failure) { error.set(failure); } });
    }
    private static void join(Thread thread) throws InterruptedException { thread.join(15_000); check(!thread.isAlive(), "Worker failed to return"); }
    private static void await(CountDownLatch latch) throws InterruptedException { check(latch.await(15, TimeUnit.SECONDS), "Provider barrier not reached"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
