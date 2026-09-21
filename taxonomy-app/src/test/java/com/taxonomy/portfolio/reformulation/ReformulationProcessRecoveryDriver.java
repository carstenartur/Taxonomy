package com.taxonomy.portfolio.reformulation;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.TaxonomyApplication;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.composition.reformulation.ReformulationExecutionService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.TaxonomyNodeDto;
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

/** Real process-death recovery; only remote model responses are authored test data. */
public final class ReformulationProcessRecoveryDriver {
    private static final String ORIGINAL = "Arbeitszeiten ausschließlich am Terminal erfassen. Keine Browseroberfläche. Frist: 2 Sekunden.";
    private ReformulationProcessRecoveryDriver() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]); Files.createDirectories(directory);
        boolean boundaries = args[0].equals("boundaries");
        boolean produce = args[0].equals("produce") || boundaries;
        List<String> saved = produce ? List.of() : Files.readAllLines(directory.resolve("identity"));
        ObjectMapper json = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> remoteFailure = new AtomicReference<>();
        AtomicReference<ConfigurableApplicationContext> application = new AtomicReference<>();
        HttpServer remote = HttpServer.create(new InetSocketAddress("127.0.0.1", produce ? 0 : Integer.parseInt(saved.get(8))), 0);
        remote.createContext("/v1/chat/completions", exchange -> {
            try {
                int call = calls.incrementAndGet();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String prompt = json.readTree(body).at("/messages/0/content").asText();
                check(prompt.contains(ORIGINAL), "Original missing from provider input");
                check(!prompt.contains("VALIDATION_ERRORS"), "Playback must pass the real response validator");
                if (produce && call == 2) {
                    var jdbc = application.get().getBean(JdbcTemplate.class);
                    List<String> checkpoints = jdbc.queryForList("select result_payload from reformulation_node_checkpoint where task_kind='NODE'", String.class);
                    check(checkpoints.size() == 1, "First node must commit before process death");
                    String question = json.readTree(checkpoints.getFirst()).path("questionProposals").get(0).path("id").asText();
                    Files.writeString(directory.resolve("kill-ready"), question);
                    new CountDownLatch(1).await(90, TimeUnit.SECONDS); // Parent forcibly kills this JVM, not a graceful close.
                    throw new AssertionError("Producer was not killed by its parent");
                }
                Object content;
                if (prompt.contains("RECONCILIATION_DATA_JSON\n")) {
                    content = Map.of("affectedSectionIds", List.of(), "sourceResolutions", List.of(), "findings", List.of());
                } else {
                    var input = json.readTree(prompt.substring(prompt.indexOf("INPUT_DATA_JSON\n") + 16));
                    Set<String> statements = new LinkedHashSet<>(), questions = new LinkedHashSet<>();
                    input.path("directContributions").forEach(s -> statements.add(s.path("id").asText()));
                    input.path("openDecisions").forEach(q -> questions.add(q.path("id").asText()));
                    input.path("children").forEach(child -> {
                        child.path("statementProposals").forEach(s -> statements.add(s.path("id").asText()));
                        child.path("questionProposals").forEach(q -> questions.add(q.path("id").asText()));
                    });
                    String node = input.path("nodeId").asText();
                    List<?> additions = produce && call == 1 ? List.of(Map.ofEntries(
                            Map.entry("subject", "time"), Map.entry("dimension", "correction"), Map.entry("scope", node),
                            Map.entry("wording", "Wer darf Zeitbuchungen korrigieren?"), Map.entry("rationale", "Im Original nicht festgelegt"),
                            Map.entry("affectedStatementIds", List.copyOf(statements)), Map.entry("sourceSpans", List.of()),
                            Map.entry("nodeIds", List.of(node)), Map.entry("edgeIds", List.of()),
                            Map.entry("answerSchema", json.readTree("{\"kind\":\"TEXT\",\"options\":[],\"unit\":null,\"minimum\":null,\"maximum\":null}")),
                            Map.entry("prerequisites", List.of()), Map.entry("consequences", "Rollenentscheidung offen"))) : List.of();
                    content = Map.of("summary", "Teilanforderung " + node, "statementProposals", List.of(),
                            "preservedStatementIds", statements, "questionProposals", additions, "preservedQuestionIds", questions,
                            "uncoveredSourceRefs", List.of(), "conflictCandidates", List.of());
                }
                byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", json.writeValueAsString(content))))));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Throwable failure) {
                remoteFailure.set(failure); failure.printStackTrace(); exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        });
        remote.start();
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.datasource.url=jdbc:hsqldb:file:" + directory.resolve("database").toAbsolutePath() + ";shutdown=true;hsqldb.write_delay=false",
                "--spring.datasource.username=SA", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--embedding.enabled=false", "--taxonomy.init.async=false", "--llm.mock=false", "--llm.provider=CUSTOM_OPENAI",
                "--custom.llm.model=recovery-test", "--custom.llm.api.key=", "--custom.llm.url=http://127.0.0.1:" + remote.getAddress().getPort() + "/v1/chat/completions",
                "--reformulation.recovery.lease-ms=3000", "--reformulation.recovery.poll-ms=250", "--reformulation.recovery.heartbeat-ms=500",
                "--gemini.api.key=", "--openai.api.key=", "--deepseek.api.key=", "--qwen.api.key=", "--llama.api.key=", "--mistral.api.key=")) {
            application.set(app);
            var proposals = app.getBean(ReformulationService.class);
            var projects = app.getBean(ProjectPortfolioService.class);
            if (produce) {
                var workspaces = app.getBean(WorkspaceManager.class);
                var w = workspaces.createWorkspace("architect", "Process recovery", "Real catalogue, authored model replies");
                w = workspaces.provisionWorkspaceRepository("architect", w.getWorkspaceId());
                var scope = new WorkspaceContext("architect", w.getWorkspaceId(), w.getCurrentBranch(), w.getSourceRepositoryId());
                var p = projects.createProject(new CreateProjectRequest("P", "Recovery", "Fixture", ProjectStatus.ACTIVE, null, null, null, null), "architect", scope);
                var r = projects.createRequirement(p.id(), new CreateRequirementRequest("R", "Time capture", ORIGINAL, RequirementStatus.APPROVED,
                        50, Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.CONFIRMED, "architect", "Source", null), "architect", scope);
                // Small authored analysis fixture; this test checks recovery, not catalogue relevance.
                var leafA = new TaxonomyNodeDto(); leafA.setCode("BP-A-1"); leafA.setNameEn("Record time");
                var leafB = new TaxonomyNodeDto(); leafB.setCode("BP-B-1"); leafB.setNameEn("Review time");
                var parentA = new TaxonomyNodeDto(); parentA.setCode("BP-A"); parentA.setNameEn("Capture"); parentA.setChildren(List.of(leafA));
                var parentB = new TaxonomyNodeDto(); parentB.setCode("BP-B"); parentB.setNameEn("Review"); parentB.setChildren(List.of(leafB));
                var selected = new TaxonomyNodeDto(); selected.setCode("BP"); selected.setNameEn("Processes"); selected.setChildren(List.of(parentA, parentB));
                var scores = Map.of("BP",100,"BP-A",50,"BP-A-1",50,"BP-B",50,"BP-B-1",50);
                var analyses = app.getBean(PortfolioAnalysisPersistenceService.class);
                var job = analyses.createOrReuseJob(p.id(), List.of(r.id()), null, 25, "process-recovery", "architect", scope);
                String snapshot = UUID.randomUUID().toString(); var result = new AnalysisResult(scores, List.of(selected)); result.setStatus("SUCCESS");
                analyses.persistSnapshot(job.items().getFirst().id(), job.id(), p.id(), PortfolioScope.key("architect", scope), snapshot,
                        "session", result, null, null, null, null, null, "p", "t", "architect", scope, 1);
                var offer = proposals.create(p.id(), r.id(), new ReformulationDtos.CreateRequest(r.currentVersionId(), snapshot, "de"), "architect", scope);
                if (boundaries) {
                    verifyLeaseFencing(app, p.id(), r.id(), offer.id(), scope);
                    check(projects.listRequirementVersions(p.id(), r.id(), "architect", scope).size() == 1, "Lease checks changed original versions");
                    System.out.println("REFORMULATION_LEASE_FENCING_OK");
                    return;
                }
                var run = app.getBean(ReformulationExecutionService.class).start(p.id(), r.id(), offer.id(), 1, "architect", scope);
                Files.write(directory.resolve("identity"), List.of(scope.repositoryId(), scope.workspaceId(), scope.currentBranch(), p.id().toString(),
                        r.id().toString(), offer.id(), r.currentVersionId().toString(), run.id(), Integer.toString(remote.getAddress().getPort())));
                new CountDownLatch(1).await(90, TimeUnit.SECONDS);
                throw new AssertionError("Producer must be terminated forcibly");
            } else {
                var scope = new WorkspaceContext("architect", saved.get(1), saved.get(2), saved.get(0));
                long p = Long.parseLong(saved.get(3)), r = Long.parseLong(saved.get(4)); String offer = saved.get(5);
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (System.nanoTime() < deadline && proposals.get(p, r, offer, "architect", scope).currentRevision().number() == 1) Thread.sleep(100);
                var after = proposals.get(p, r, offer, "architect", scope);
                check(after.currentRevision().number() == 2, "Interrupted synthesis was not automatically recovered; revision=" + after.currentRevision().number());
                check(proposals.runs(p, r, offer, "architect", scope).size() == 1, "Recovery created a new user run");
                check(proposals.runs(p, r, offer, "architect", scope).getFirst().status().equals("COMPLETED"), "Recovered run did not complete");
                String question = Files.readString(directory.resolve("kill-ready"));
                check(after.currentRevision().questions().stream().anyMatch(q -> q.id().equals(question)), "Committed question identity lost");
                check(calls.get() == 3, "Committed child must be reused; expected 3 remaining requests, got " + calls.get());
                check(remoteFailure.get() == null, "Remote fixture failed: " + remoteFailure.get());
                check(projects.getRequirement(p, r, "architect", scope).currentVersionId() == Long.parseLong(saved.get(6)), "Original version moved");
                check(projects.listRequirementVersions(p, r, "architect", scope).size() == 1, "Original history changed");
                System.out.println("REFORMULATION_PROCESS_RECOVERY_OK calls=" + calls.get());
            }
        } finally { remote.stop(0); }
    }
    private static void verifyLeaseFencing(org.springframework.context.ConfigurableApplicationContext app,
            Long project, Long requirement, String offer, WorkspaceContext scope) throws Exception {
        app.getBean(com.taxonomy.composition.reformulation.ReformulationRecoveryCoordinator.class).stop();
        var leases = app.getBean(ReformulationRecoveryService.class);
        var proposals = app.getBean(ReformulationService.class);
        var first = leases.enqueue(project, requirement, offer, 1, "CUSTOM_OPENAI", "recovery-test", "v", "v", "prompt",
                Map.of(), "endpoint", "architect", scope);
        var variant=proposals.variant(project, requirement, offer, 1, new ReformulationDtos.VariantRequest("Another queue entry"), "architect", scope);
        var peer=leases.enqueue(project, requirement, variant.id(), 1, "CUSTOM_OPENAI", "recovery-test", "v", "v", "prompt", Map.of(), "endpoint", "architect", scope);
        var lower=List.of(first.run().id(),peer.run().id()).stream().sorted().toList();
        check(leases.due(1).getFirst().run().id().equals(lower.getFirst()), "Queue page is not ordered");
        check(leases.dueAfter(1,lower.getFirst()).getFirst().run().id().equals(lower.getLast()), "Blocked entry prevents later queue progress");
        proposals.cancelRun(project, requirement, variant.id(), peer.run().id(), 1, "architect", scope);
        check(leases.claim(peer, "cleanup").isEmpty(), "Cancelled queue peer recovered");
        var ownerA = leases.claim(first, "owner-a").orElseThrow();
        check(leases.claim(first, "owner-b").isEmpty(), "Unexpired lease stolen");
        check(leases.heartbeat(ownerA), "Owner could not renew");
        leases.completeCheckpoint(ownerA, "NODE", "a".repeat(64), "\"first\"");
        Thread.sleep(3200);
        var ownerB = leases.claim(first, "owner-b").orElseThrow();
        check(ownerB.epoch() == ownerA.epoch() + 1, "Takeover did not advance epoch");
        check(!leases.heartbeat(ownerA), "Expired owner renewed the replacement lease");
        check(!leases.finish(ownerA, null, "STALE_FAILURE"), "Expired owner failed the winning run");
        expectLeaseRejection(() -> leases.checkpoint(ownerA, "NODE", "a".repeat(64)));
        expectLeaseRejection(() -> leases.completeCheckpoint(ownerA, "NODE", "b".repeat(64), "\"stale\""));
        check(leases.checkpoint(ownerB, "NODE", "a".repeat(64)).orElseThrow().equals("\"first\""), "Committed result lost on takeover");
        var foreign = new WorkspaceContext("architect", "foreign-workspace", scope.currentBranch(), scope.repositoryId());
        var forged = new ReformulationRecoveryService.Dispatch(project, requirement, offer, "architect", foreign, first.run(), first.endpointHash());
        expectLeaseRejection(() -> leases.claim(forged, "owner-c"));
        proposals.cancelRun(project, requirement, offer, first.run().id(), 1, "architect", scope);
        check(!leases.finish(ownerB, null, "LATE"), "Cancelled run accepted completion");
        check(leases.claim(first, "owner-c").isEmpty(), "Cancelled run was recovered");
        var second = leases.enqueue(project, requirement, offer, 1, "CUSTOM_OPENAI", "recovery-test", "v", "v", "prompt", Map.of(), "endpoint", "architect", scope);
        var manualToken = leases.claim(second, "owner-a").orElseThrow();
        proposals.saveDraft(project, requirement, offer, 1, new ReformulationDtos.SaveDraftRequest("Protected manual wording", "Human edit"), "architect", scope);
        check(leases.source(second).currentRevision().number() == 1, "Recovery read a different source revision");
        var original = leases.source(second).baseline().originalText();
        var document = new com.taxonomy.reformulation.ReformulationDocument(original, List.of(), List.of(), List.of(), new com.taxonomy.reformulation.ValidationReport(List.of()), List.of());
        check(leases.finish(manualToken, document, null), "Valid candidate was not recorded");
        check(proposals.get(project, requirement, offer, "architect", scope).currentRevision().text().equals("Protected manual wording"), "Recovery overwrote a human edit");
        check(proposals.runs(project, requirement, offer, "architect", scope).getLast().status().equals("PARTIAL"), "Manual conflict did not retain a partial candidate");
        var limited = leases.enqueue(project, requirement, offer, 2, "CUSTOM_OPENAI", "recovery-test", "v", "v", "prompt", Map.of(), "endpoint", "architect", scope);
        for (int attempt=1; attempt<=3; attempt++) {
            check(leases.claim(limited, "attempt-"+attempt).orElseThrow().epoch()==attempt, "Unexpected attempt epoch");
            Thread.sleep(3200);
        }
        check(leases.claim(limited, "exhausted").isEmpty(), "Exceeded bounded recovery attempts");
        check(proposals.runs(project, requirement, offer, "architect", scope).getLast().failureCode().equals("RECOVERY_ATTEMPTS_EXHAUSTED"), "Exhausted run not explained");
        check(leases.due(10).isEmpty(), "Terminal jobs remained in recovery queue");
    }
    private static void expectLeaseRejection(Runnable action) {
        try { action.run(); }
        catch (com.taxonomy.portfolio.service.PortfolioException expected) { return; }
        throw new AssertionError("Stale or foreign attempt was not rejected");
    }
    private static TaxonomyNodeDto shallow(TaxonomyNodeDto original) {
        var n = new TaxonomyNodeDto(); n.setCode(original.getCode()); n.setNameEn(original.getNameEn()); n.setNameDe(original.getNameDe());
        n.setDescriptionEn(original.getDescriptionEn()); n.setDescriptionDe(original.getDescriptionDe()); return n;
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
