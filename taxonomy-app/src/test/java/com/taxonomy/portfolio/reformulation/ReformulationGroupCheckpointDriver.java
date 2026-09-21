package com.taxonomy.portfolio.reformulation;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.TaxonomyApplication;
import com.taxonomy.analysis.reformulation.NodeReformulationService;
import com.taxonomy.composition.reformulation.ReformulationExecutionService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.reformulation.*;
import com.taxonomy.workspace.service.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Authored large child formulations with real gateway/DB checkpoints; not live-model quality evidence. */
public final class ReformulationGroupCheckpointDriver {
    private static final String ORIGINAL = "Ausschließlich Terminal, keine Browseroberfläche. Frist: 2 Sekunden.";
    private ReformulationGroupCheckpointDriver() {}

    public static void main(String[] args) throws Exception {
        boolean write = args[0].equals("write");
        Path directory = Path.of(args[1]); Files.createDirectories(directory);
        List<String> identity = write ? List.of() : Files.readAllLines(directory.resolve("identity"));
        var json = new ObjectMapper(); var calls = new AtomicInteger(); var remoteFailure = new AtomicReference<Throwable>();
        var remote = HttpServer.create(new InetSocketAddress("127.0.0.1", write ? 0 : Integer.parseInt(identity.get(8))), 0);
        remote.createContext("/v1/chat/completions", exchange -> {
            try {
                calls.incrementAndGet();
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String prompt = json.readTree(raw).at("/messages/0/content").asText();
                check(!prompt.contains("VALIDATION_ERRORS"), "Authored answer failed parsing");
                var input = json.readTree(prompt.substring(prompt.indexOf("INPUT_DATA_JSON\n") + 16));
                check(input.at("/baseline/originalText").asText().equals(ORIGINAL), "Original missing from grouped prompt");
                var sids = new LinkedHashSet<String>(); var qids = new LinkedHashSet<String>();
                input.path("directContributions").forEach(s -> sids.add(s.path("id").asText()));
                input.path("openDecisions").forEach(q -> qids.add(q.path("id").asText()));
                input.path("children").forEach(child -> {
                    child.path("statementProposals").forEach(s -> sids.add(s.path("id").asText()));
                    child.path("preservedStatementIds").forEach(s -> sids.add(s.asText()));
                    child.path("questionProposals").forEach(q -> qids.add(q.path("id").asText()));
                    child.path("preservedQuestionIds").forEach(q -> qids.add(q.asText()));
                });
                var schema = new LinkedHashMap<String,Object>();
                schema.put("kind", "TEXT"); schema.put("options", List.of()); schema.put("unit", null); schema.put("minimum", null); schema.put("maximum", null);
                var question = Map.ofEntries(Map.entry("subject", "time"), Map.entry("dimension", "group-clarification"), Map.entry("scope", "BP"),
                        Map.entry("wording", "Klärung für " + String.join(",", sids)), Map.entry("rationale", "Zusätzliche Details sind Vorschläge"),
                        Map.entry("affectedStatementIds", sids), Map.entry("sourceSpans", List.of()), Map.entry("nodeIds", List.of("BP")),
                        Map.entry("edgeIds", List.of()), Map.entry("answerSchema", schema), Map.entry("prerequisites", List.of()),
                        Map.entry("consequences", "Fachliche Ergänzungen prüfen"));
                var response = Map.of("summary", "Terminalerfassung", "statementProposals", List.of(), "preservedStatementIds", sids,
                        "questionProposals", List.of(question), "preservedQuestionIds", qids, "uncoveredSourceRefs", List.of(), "conflictCandidates", List.of());
                byte[] bytes = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", json.writeValueAsString(response)), "finish_reason", "stop"))));
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Throwable failure) { remoteFailure.set(failure); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        remote.start();
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.datasource.url=jdbc:hsqldb:file:" + directory.resolve("database").toAbsolutePath() + ";shutdown=true;hsqldb.write_delay=false",
                "--spring.datasource.username=SA", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--embedding.enabled=false", "--taxonomy.init.async=false", "--llm.mock=false", "--llm.provider=CUSTOM_OPENAI",
                "--custom.llm.model=group-checkpoint-test", "--custom.llm.api.key=", "--custom.llm.url=http://127.0.0.1:" + remote.getAddress().getPort() + "/v1/chat/completions",
                "--gemini.api.key=", "--openai.api.key=", "--deepseek.api.key=", "--qwen.api.key=", "--llama.api.key=", "--mistral.api.key=")) {
            var proposals = app.getBean(ReformulationService.class); var projects = app.getBean(ProjectPortfolioService.class);
            var factory = app.getBean(ReformulationExecutionService.class); var nodes = app.getBean(NodeReformulationService.class);
            long projectId, requirementId, sourceVersion; String offerId; WorkspaceContext scope; NodeSynthesisInput input;
            if (write) {
                var manager = app.getBean(WorkspaceManager.class);
                var workspace = manager.createWorkspace("architect", "Group checkpoint", "Authored large child formulations");
                workspace = manager.provisionWorkspaceRepository("architect", workspace.getWorkspaceId());
                scope = new WorkspaceContext("architect", workspace.getWorkspaceId(), workspace.getCurrentBranch(), workspace.getSourceRepositoryId());
                var project = projects.createProject(new CreateProjectRequest("P", "Group checkpoint", "Stress fixture", ProjectStatus.ACTIVE, null, null, null, null), "architect", scope);
                projectId = project.id();
                var requirement = projects.createRequirement(projectId, new CreateRequirementRequest("R", "Original", ORIGINAL, RequirementStatus.APPROVED,
                        50, Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.CONFIRMED, "architect", "Original", null), "architect", scope);
                requirementId = requirement.id(); sourceVersion = requirement.currentVersionId();
                var analyses = app.getBean(PortfolioAnalysisPersistenceService.class);
                var job = analyses.createOrReuseJob(projectId, List.of(requirementId), null, 25, "group-checkpoint", "architect", scope);
                var root = new TaxonomyNodeDto(); root.setCode("BP"); root.setNameEn("Business Processes");
                var result = new AnalysisResult(Map.of("BP", 100), List.of(root)); result.setStatus("PARTIAL");
                String snapshot = UUID.randomUUID().toString();
                analyses.persistSnapshot(job.items().getFirst().id(), job.id(), projectId, PortfolioScope.key("architect", scope), snapshot, "session", result,
                        null, null, null, null, null, "p", "t", "architect", scope, 1);
                var proposal = proposals.create(projectId, requirementId, new ReformulationDtos.CreateRequest(sourceVersion, snapshot, "de"), "architect", scope);
                offerId = proposal.id(); input = largeInput(proposal.baseline());
                Files.writeString(directory.resolve("input.json"), json.writeValueAsString(input));
                var run = proposals.beginRun(projectId, requirementId, offerId, 1, "CUSTOM_OPENAI", "group-checkpoint-test", "p", "s", "frozen", "architect", scope);
                proposals.running(projectId, requirementId, offerId, run.id(), "architect", scope);
                var checkpoints = factory.checkpoints(projectId, requirementId, offerId, run, "architect", scope);
                var count = new AtomicInteger(); var discovered = new AtomicReference<String>();
                var interrupted = new ReformulationStepExecutor() {
                    @Override public <T> T execute(String kind, Object value, Class<T> type, Supplier<T> work) {
                        if (kind.equals("NODE_GROUP") && count.incrementAndGet() == 2) throw new InterruptedGroup();
                        T saved = checkpoints.execute(kind, value, type, work);
                        if (saved instanceof NodeSynthesisResult part) discovered.set(part.questionProposals().getFirst().id());
                        return saved;
                    }
                };
                try { nodes.synthesize(input, interrupted); throw new AssertionError("Expected interruption between groups"); }
                catch (InterruptedGroup expected) { /* The first group is committed, the user run remains active. */ }
                check(calls.get() == 1 && discovered.get() != null, "First group was not checkpointed before interruption");
                check(proposals.get(projectId, requirementId, offerId, "architect", scope).currentRevision().number() == 1, "Group alone published a revision");
                Files.write(directory.resolve("identity"), List.of(scope.repositoryId(), scope.workspaceId(), scope.currentBranch(), "" + projectId, "" + requirementId,
                        offerId, "" + sourceVersion, run.id(), "" + remote.getAddress().getPort(), discovered.get()));
                System.out.println("REFORMULATION_GROUP_WRITE_OK calls=" + calls.get());
            } else {
                scope = new WorkspaceContext("architect", identity.get(1), identity.get(2), identity.get(0));
                projectId = Long.parseLong(identity.get(3)); requirementId = Long.parseLong(identity.get(4)); offerId = identity.get(5); sourceVersion = Long.parseLong(identity.get(6));
                input = json.readValue(Files.readString(directory.resolve("input.json")), NodeSynthesisInput.class);
                proposals.cancelRun(projectId, requirementId, offerId, identity.get(7), 1, "architect", scope);
                var run = proposals.beginRun(projectId, requirementId, offerId, 1, "CUSTOM_OPENAI", "group-checkpoint-test", "p", "s", "frozen", "architect", scope);
                proposals.running(projectId, requirementId, offerId, run.id(), "architect", scope);
                var checkpoints = factory.checkpoints(projectId, requirementId, offerId, run, "architect", scope);
                var output = nodes.synthesize(input, checkpoints);
                check(calls.get() == 2, "Stored group was re-requested; expected one group plus aggregate, got " + calls.get());
                check(output.questionProposals().stream().anyMatch(q -> q.id().equals(identity.get(9))), "Checkpointed question identity lost");
                check(nodes.synthesize(input, checkpoints).equals(output) && calls.get() == 2, "Identical retry changed result or used provider");
                var statements = new ArrayList<>(input.directContributions()); input.children().forEach(c -> statements.addAll(c.statementProposals())); statements.addAll(output.statementProposals());
                var document = new ReformulationDocument(output.summary(), List.of(), statements, output.questionProposals(),
                        new ValidationReport(output.conflictCandidates()), List.of(output));
                proposals.finishRun(projectId, requirementId, offerId, run.id(), document, null, "architect", scope);
                check(proposals.get(projectId, requirementId, offerId, "architect", scope).currentRevision().number() == 2, "Completed grouped result not published");
                boolean fenced = false;
                try { nodes.synthesize(input, checkpoints); } catch (PortfolioException expected) { fenced = true; }
                check(fenced && calls.get() == 2, "Inactive run returned cached group or issued remote calls");
                check(projects.getRequirement(projectId, requirementId, "architect", scope).currentVersionId() == sourceVersion, "Original active version changed");
                check(projects.listRequirementVersions(projectId, requirementId, "architect", scope).size() == 1, "Original history changed");
                System.out.println("REFORMULATION_GROUP_RESTART_OK calls=" + calls.get());
            }
            check(remoteFailure.get() == null, "Remote fixture failed: " + remoteFailure.get());
        } finally { remote.stop(0); }
    }

    private static NodeSynthesisInput largeInput(ReformulationBaseline baseline) {
        var children = new ArrayList<NodeSynthesisResult>();
        for (String root : List.of("CP", "BR", "CR", "CO", "IP", "UA")) {
            var statement = new Statement("detail-" + root, "Authored stress detail for " + root + ": " + "x".repeat(24000), List.of(),
                    Statement.Provenance.MODEL_ADDITION, List.of(root), List.of(), null, Statement.EditingOrigin.MODEL, "UNREVIEWED");
            children.add(new NodeSynthesisResult(root, "Proposed detail " + root, List.of(statement), List.of(), List.of(), List.of(), List.of(), List.of()));
        }
        var source = new Statement("source", baseline.originalText(), List.of(new Statement.SourceSpan(0, baseline.originalText().length(), baseline.originalText())),
                Statement.Provenance.ORIGINAL, List.of(), List.of(), null, Statement.EditingOrigin.SOURCE, "UNREVIEWED");
        return new NodeSynthesisInput(baseline, "BP", null, "Authored large-child stress input, not a generated architecture", List.of(), List.of(source),
                children, Map.of(), List.of(), List.of(), "Preserve original and all child statements; additions remain unreviewed.");
    }
    private static final class InterruptedGroup extends RuntimeException {}
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
