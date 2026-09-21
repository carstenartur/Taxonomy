package com.taxonomy.portfolio.reformulation;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.TaxonomyApplication;
import com.taxonomy.analysis.reformulation.CrossTaxonomyReconciler;
import com.taxonomy.analysis.reformulation.FrozenReformulationEngine;
import com.taxonomy.composition.reformulation.ReformulationExecutionService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.reformulation.*;
import com.taxonomy.workspace.service.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Small persisted tree fixture; real walk-up, parser, gateway and file database in separate JVMs. */
public final class ReformulationCheckpointDriver {
    private static final String ORIGINAL = "Ausschließlich Terminal, keine Browseroberfläche. Frist: 2 Sekunden.";
    private ReformulationCheckpointDriver() {}

    public static void main(String[] args) throws Exception {
        Path identity = Path.of(args[2]);
        boolean write = args[1].equals("write");
        List<String> saved = write ? List.of() : Files.readAllLines(identity);
        var json = new ObjectMapper();
        var calls = new AtomicInteger();
        var questionId = new AtomicReference<String>();
        var remote = HttpServer.create(new InetSocketAddress("127.0.0.1", write ? 0 : Integer.parseInt(saved.get(9))), 0);
        remote.createContext("/v1/chat/completions", exchange -> {
            try {
                calls.incrementAndGet();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String prompt = json.readTree(body).at("/messages/0/content").asText();
                check(prompt.contains(ORIGINAL), "Original absent from a real provider prompt");
                check(!prompt.contains("VALIDATION_ERRORS"), "Authored response failed validation: " + prompt.substring(Math.max(0, prompt.indexOf("VALIDATION_ERRORS"))));
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
                    String node = input.path("nodeId").asText();
                    List<?> proposedQuestions = node.equals("BP-A") ? List.of(Map.ofEntries(
                            Map.entry("subject", "time"), Map.entry("dimension", "correction"), Map.entry("scope", "BP-A"),
                            Map.entry("wording", "Wer darf Zeitbuchungen korrigieren?"), Map.entry("rationale", "Im Original offen"),
                            Map.entry("affectedStatementIds", List.copyOf(statements)), Map.entry("sourceSpans", List.of()),
                            Map.entry("nodeIds", List.of("BP-A")), Map.entry("edgeIds", List.of()),
                            Map.entry("answerSchema", json.readTree("{\"kind\":\"TEXT\",\"options\":[],\"unit\":null,\"minimum\":null,\"maximum\":null}")),
                            Map.entry("prerequisites", List.of()), Map.entry("consequences", "Rollenentscheidung"))) : List.of();
                    response = Map.of("summary", "Abschnitt " + node, "statementProposals", List.of(),
                            "preservedStatementIds", statements, "questionProposals", proposedQuestions,
                            "preservedQuestionIds", questions, "uncoveredSourceRefs", List.of(), "conflictCandidates", List.of());
                }
                byte[] bytes = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", json.writeValueAsString(response))))));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Throwable failure) {
                failure.printStackTrace();
                byte[] message = failure.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, message.length);
                exchange.getResponseBody().write(message);
            } finally {
                exchange.close();
            }
        });
        remote.start();
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + args[0], "--spring.datasource.username=SA", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver", "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap", "--embedding.enabled=false", "--taxonomy.init.async=false",
                "--llm.mock=false", "--llm.provider=CUSTOM_OPENAI", "--custom.llm.model=checkpoint-test", "--custom.llm.api.key=",
                "--custom.llm.url=http://127.0.0.1:" + remote.getAddress().getPort() + "/v1/chat/completions",
                "--gemini.api.key=", "--openai.api.key=", "--deepseek.api.key=", "--qwen.api.key=", "--llama.api.key=", "--mistral.api.key=")) {
            var proposals = app.getBean(ReformulationService.class);
            var projects = app.getBean(ProjectPortfolioService.class);
            var factory = app.getBean(ReformulationExecutionService.class);
            var engine = app.getBean(FrozenReformulationEngine.class);
            var reconciler = app.getBean(CrossTaxonomyReconciler.class);
            WorkspaceContext scope;
            long projectId, requirementId;
            String proposalId, runId;
            long sourceVersion;
            if (write) {
                var workspaces = app.getBean(WorkspaceManager.class);
                var workspace = workspaces.createWorkspace("architect", "Checkpoint recovery", "Restart proof");
                workspace = workspaces.provisionWorkspaceRepository("architect", workspace.getWorkspaceId());
                scope = new WorkspaceContext("architect", workspace.getWorkspaceId(), workspace.getCurrentBranch(), workspace.getSourceRepositoryId());
                var project = projects.createProject(new CreateProjectRequest("P", "Checkpoint recovery", "Fixture", ProjectStatus.ACTIVE, null, null, null, null), "architect", scope);
                projectId = project.id();
                var requirement = projects.createRequirement(projectId, new CreateRequirementRequest("R", "Original", ORIGINAL,
                        RequirementStatus.APPROVED, 50, Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.CONFIRMED, "architect", "Original", null), "architect", scope);
                requirementId = requirement.id(); sourceVersion = requirement.currentVersionId();
                var analysis = app.getBean(PortfolioAnalysisPersistenceService.class);
                var job = analysis.createOrReuseJob(projectId, List.of(requirementId), null, 25, "checkpoint-recovery", "architect", scope);
                var root = node("BP", node("BP-A", node("BP-A-1")), node("BP-B", node("BP-B-1")));
                var result = new AnalysisResult(Map.of("BP", 100, "BP-A", 50, "BP-A-1", 50, "BP-B", 50, "BP-B-1", 50), List.of(root));
                result.setStatus("SUCCESS"); String snapshot = UUID.randomUUID().toString();
                analysis.persistSnapshot(job.items().getFirst().id(), job.id(), projectId, PortfolioScope.key("architect", scope), snapshot, "session", result,
                        null, null, null, null, null, "p", "t", "architect", scope, 1);
                var proposal = proposals.create(projectId, requirementId, new ReformulationDtos.CreateRequest(sourceVersion, snapshot, "de"), "architect", scope);
                proposalId = proposal.id();
                var run = proposals.beginRun(projectId, requirementId, proposalId, 1, "CUSTOM_OPENAI", "checkpoint-test", "p", "s", "frozen", "architect", scope);
                runId = run.id(); proposals.running(projectId, requirementId, proposalId, runId, "architect", scope);
                var steps = factory.checkpoints(projectId, requirementId, proposalId, run, "architect", scope);
                // A real provider result commits before the next step is deliberately interrupted.
                var interruptAfterFirst = new ReformulationStepExecutor() {
                    @Override public <T> T execute(String kind, Object input, Class<T> type, Supplier<T> work) {
                        check(!TransactionSynchronizationManager.isActualTransactionActive(), "Provider boundary holds a transaction");
                        if (input instanceof NodeSynthesisInput node && node.nodeId().equals("BP-B")) throw new Stopped();
                        T value = steps.execute(kind, input, type, work);
                        if (value instanceof NodeSynthesisResult node && node.nodeId().equals("BP-A")) questionId.set(node.questionProposals().getFirst().id());
                        return value;
                    }
                };
                try {
                    engine.synthesize(proposal.baseline(), List.of(), List.of(), List.of(), interruptAfterFirst);
                    throw new AssertionError("Interruption did not stop the walk-up");
                } catch (Stopped expected) { /* JVM exits below with a still-RUNNING, partially checkpointed run. */ }
                check(calls.get() == 1, "Expected exactly one committed remote result before restart; calls=" + calls);
                check(questionId.get() != null, "The first checkpoint must contain the discovered question");
                Files.write(identity, List.of(scope.repositoryId(), scope.workspaceId(), scope.currentBranch(), Long.toString(projectId),
                        Long.toString(requirementId), proposalId, Long.toString(sourceVersion), runId, questionId.get(), Integer.toString(remote.getAddress().getPort())));
            } else {
                scope = new WorkspaceContext("architect", saved.get(1), saved.get(2), saved.get(0));
                projectId = Long.parseLong(saved.get(3)); requirementId = Long.parseLong(saved.get(4)); proposalId = saved.get(5);
                sourceVersion = Long.parseLong(saved.get(6)); runId = saved.get(7);
                var old = proposals.runs(projectId, requirementId, proposalId, "architect", scope).getFirst();
                check(old.status().equals("RUNNING"), "Interrupted run state was not persisted");
                var proposal = proposals.get(projectId, requirementId, proposalId, "architect", scope);
                check(proposal.currentRevision().number() == 1, "A partial step published a proposal revision");
                proposals.cancelRun(projectId, requirementId, proposalId, runId, 1, "architect", scope);
                check(proposals.cancelRun(projectId, requirementId, proposalId, runId, 1, "architect", scope).status().equals("CANCELLED"), "Cancel is not idempotent");
                var jdbc = app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
                check("architect".equals(jdbc.queryForObject("select cancelled_by from reformulation_run where id=?", String.class, runId)), "Cancellation actor missing");
                check(jdbc.queryForObject("select cancelled_at from reformulation_run where id=?", java.time.OffsetDateTime.class, runId) != null, "Cancellation time missing");
                var obsolete = factory.checkpoints(projectId, requirementId, proposalId, old, "architect", scope);
                boolean fenced = false;
                try { obsolete.execute("NODE", Map.of("late", true), String.class, () -> { throw new AssertionError("Cancelled worker ran provider work"); }); }
                catch (PortfolioException expected) { fenced = true; }
                check(fenced, "Cancelled worker was not fenced");
                var run = proposals.beginRun(projectId, requirementId, proposalId, 1, "CUSTOM_OPENAI", "checkpoint-test", "p", "s", "frozen", "architect", scope);
                boolean blocked = false;
                try { proposals.beginRun(projectId, requirementId, proposalId, 1, "CUSTOM_OPENAI", "checkpoint-test", "p", "s", "frozen", "architect", scope); }
                catch (PortfolioException expected) { blocked = true; }
                check(blocked, "A second active run was accepted");
                proposals.running(projectId, requirementId, proposalId, run.id(), "architect", scope);
                var steps = factory.checkpoints(projectId, requirementId, proposalId, run, "architect", scope);
                var phaseA = engine.synthesize(proposal.baseline(), List.of(), List.of(), List.of(), steps);
                check(phaseA.questions().stream().anyMatch(q -> q.id().equals(saved.get(8))), "Recovered child question identity changed");
                var document = reconciler.reconcile(proposal.baseline(), phaseA, List.of(), List.of(), steps);
                check(calls.get() == 3, "First child was repeated after restart; expected two nodes and one reconciliation, calls=" + calls);
                check(document.questions().stream().anyMatch(q -> q.id().equals(saved.get(8))), "Reconciliation lost the recovered question");
                // Exact same inputs, including nested maps with different insertion order, reuse one result.
                var first = new LinkedHashMap<String, Object>(); first.put("a", Map.of("x", 1, "y", 2)); first.put("b", "value");
                var second = new LinkedHashMap<String, Object>(); second.put("b", "value"); second.put("a", Map.of("y", 2, "x", 1));
                check(steps.execute("NODE", first, String.class, () -> "stable").equals("stable"), "Initial checkpoint failed");
                check(steps.execute("NODE", second, String.class, () -> { throw new AssertionError("Object ordering changed cache identity"); }).equals("stable"), "Cached payload changed");
                check(steps.execute("NODE", Map.of("a", 2), String.class, () -> "changed").equals("changed"), "Changed input reused an old checkpoint");
                // Failure is never a cache hit on retry.
                try { steps.execute("NODE", Map.of("failure", 1), String.class, () -> { throw new Stopped(); }); }
                catch (Stopped expected) { /* no checkpoint */ }
                check(steps.execute("NODE", Map.of("failure", 1), String.class, () -> "recovered").equals("recovered"), "Failure was cached");
                var foreign = new WorkspaceContext("architect", "foreign-workspace", scope.currentBranch(), scope.repositoryId());
                boolean denied = false;
                try { factory.checkpoints(projectId, requirementId, proposalId, run, "architect", foreign).execute("NODE", first, String.class, () -> "leak"); }
                catch (PortfolioException expected) { denied = true; }
                check(denied, "Foreign workspace received a cached result");
                proposals.finishRun(projectId, requirementId, proposalId, runId, document, null, "architect", scope);
                check(proposals.get(projectId, requirementId, proposalId, "architect", scope).currentRevision().number() == 1, "Late cancelled result published");
                proposals.finishRun(projectId, requirementId, proposalId, run.id(), document, null, "architect", scope);
                check(proposals.get(projectId, requirementId, proposalId, "architect", scope).currentRevision().number() == 2, "Recovered run was not published");
                check(proposals.runs(projectId, requirementId, proposalId, "architect", scope).getLast().status().equals("COMPLETED"), "Recovered run was not completed");
                // Cancellation races a provider result AFTER its preflight, not only before it starts.
                var racing = proposals.beginRun(projectId, requirementId, proposalId, 2, "CUSTOM_OPENAI", "checkpoint-test", "p", "s", "frozen", "architect", scope);
                proposals.running(projectId, requirementId, proposalId, racing.id(), "architect", scope);
                var racingSteps = factory.checkpoints(projectId, requirementId, proposalId, racing, "architect", scope);
                final var heldScope = scope;
                final long heldProject = projectId, heldRequirement = requirementId;
                final String heldProposal = proposalId;
                boolean lateRejected = false;
                try {
                    racingSteps.execute("NODE", Map.of("race", true), String.class, () -> {
                        proposals.cancelRun(heldProject, heldRequirement, heldProposal, racing.id(), 2, "architect", heldScope);
                        return "obsolete result";
                    });
                } catch (PortfolioException expected) { lateRejected = true; }
                check(lateRejected, "A response arriving after cancellation was checkpointed");
                var replacement = proposals.beginRun(projectId, requirementId, proposalId, 2, "CUSTOM_OPENAI", "checkpoint-test", "p", "s", "frozen", "architect", scope);
                proposals.running(projectId, requirementId, proposalId, replacement.id(), "architect", scope);
                var replacementSteps = factory.checkpoints(projectId, requirementId, proposalId, replacement, "architect", scope);
                check(replacementSteps.execute("NODE", Map.of("race", true), String.class, () -> "fresh result").equals("fresh result"), "Cancelled output polluted the retry cache");
                proposals.cancelRun(projectId, requirementId, proposalId, replacement.id(), 2, "architect", scope);
                // A matching input in a different offer is NOT a shared cache hit.
                var other = proposals.create(projectId, requirementId, new ReformulationDtos.CreateRequest(sourceVersion, proposal.baseline().snapshotId(), "de"), "architect", scope);
                var otherRun = proposals.beginRun(projectId, requirementId, other.id(), 1, "CUSTOM_OPENAI", "checkpoint-test", "p", "s", "frozen", "architect", scope);
                proposals.running(projectId, requirementId, other.id(), otherRun.id(), "architect", scope);
                check(factory.checkpoints(projectId, requirementId, other.id(), otherRun, "architect", scope)
                        .execute("NODE", first, String.class, () -> "other offer").equals("other offer"), "Cache crossed proposal boundaries");
                proposals.cancelRun(projectId, requirementId, other.id(), otherRun.id(), 1, "architect", scope);
            }
            check(projects.listRequirementVersions(projectId, requirementId, "architect", scope).size() == 1, "Original version history changed");
            check(projects.getRequirement(projectId, requirementId, "architect", scope).currentVersionId() == sourceVersion, "Active requirement version changed");
            check(proposals.get(projectId, requirementId, proposalId, "architect", scope).baseline().originalText().equals(ORIGINAL), "Original text changed");
        } finally {
            remote.stop(0);
        }
        System.out.println("REFORMULATION_CHECKPOINT_OK " + args[1] + " remote_calls=" + calls.get());
    }
    private static TaxonomyNodeDto node(String code, TaxonomyNodeDto... children) {
        var node = new TaxonomyNodeDto(); node.setCode(code); node.setNameEn(code); node.setDescriptionEn("Test time recording " + code);
        node.setChildren(List.of(children)); return node;
    }
    private static void check(boolean success, String message) { if (!success) throw new AssertionError(message); }
    private static final class Stopped extends RuntimeException { private static final long serialVersionUID = 1L; }
}
