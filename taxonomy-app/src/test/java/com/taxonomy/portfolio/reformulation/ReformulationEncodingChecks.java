package com.taxonomy.portfolio.reformulation;

import com.taxonomy.TaxonomyApplication;
import com.taxonomy.analysis.reformulation.ReformulationPromptBuilder;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.composition.reformulation.ReformulationExecutionService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.reformulation.*;
import com.taxonomy.workspace.service.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Old prompt-encoding cache entries stay historical and cannot masquerade as newly evaluated output. */
public final class ReformulationEncodingChecks {
    private static final String ACTOR = "architect";
    private static final String ORIGINAL = "Arbeitszeiterfassung ohne automatische Freigabe.";
    private static final String ENDPOINT = "http://127.0.0.1:1/v1/chat/completions";
    private ReformulationEncodingChecks() {}

    public static void main(String[] args) throws Exception {
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.datasource.url=jdbc:hsqldb:mem:encoding-" + UUID.randomUUID(),
                "--spring.datasource.username=SA", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver", "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--embedding.enabled=false", "--taxonomy.init.async=false", "--llm.mock=false",
                "--llm.provider=CUSTOM_OPENAI", "--custom.llm.model=encoding-test", "--custom.llm.api.key=",
                "--custom.llm.url=" + ENDPOINT, "--gemini.api.key=", "--openai.api.key=",
                "--deepseek.api.key=", "--qwen.api.key=", "--llama.api.key=", "--mistral.api.key=")) {
            var projects = app.getBean(ProjectPortfolioService.class);
            var proposals = app.getBean(ReformulationService.class);
            var execution = app.getBean(ReformulationExecutionService.class);
            var json = app.getBean(ObjectMapper.class).rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
            var manager = app.getBean(WorkspaceManager.class);
            var workspace = manager.createWorkspace(ACTOR, "Encoding regression", "Exact input identity");
            workspace = manager.provisionWorkspaceRepository(ACTOR, workspace.getWorkspaceId());
            var context = new WorkspaceContext(ACTOR, workspace.getWorkspaceId(), workspace.getCurrentBranch(), workspace.getSourceRepositoryId());
            var project = projects.createProject(new CreateProjectRequest("ENC", "Encoding regression", "Fixture",
                    ProjectStatus.ACTIVE, null, null, null, null), ACTOR, context);
            var requirement = projects.createRequirement(project.id(), new CreateRequirementRequest("ENC-R", "Original", ORIGINAL,
                    RequirementStatus.APPROVED, 50, Criticality.HIGH, RequirementType.FUNCTIONAL,
                    ReviewStatus.CONFIRMED, ACTOR, "Original", null), ACTOR, context);
            var root = app.getBean(TaxonomyService.class).getFullTree().stream().filter(n -> n.getCode().equals("BP")).findFirst().orElseThrow();
            var analysis = new AnalysisResult(Map.of(root.getCode(), 50), List.of(root));
            analysis.setStatus("SUCCESS");
            var persistence = app.getBean(PortfolioAnalysisPersistenceService.class);
            var job = persistence.createOrReuseJob(project.id(), List.of(requirement.id()), null, 25, "encoding", ACTOR, context);
            String snapshot = UUID.randomUUID().toString();
            persistence.persistSnapshot(job.items().getFirst().id(), job.id(), project.id(), PortfolioScope.key(ACTOR, context),
                    snapshot, "encoding", analysis, null, null, null, null, null, "p", "t", ACTOR, context, 1);
            var offer = proposals.create(project.id(), requirement.id(),
                    new ReformulationDtos.CreateRequest(requirement.currentVersionId(), snapshot, "de"), ACTOR, context);
            var savedOffer = proposals.get(project.id(), requirement.id(), offer.id(), ACTOR, context);
            var run = proposals.beginRun(project.id(), requirement.id(), offer.id(), 1, "CUSTOM_OPENAI", "encoding-test",
                    "p", "s", "frozen", ACTOR, context);
            proposals.running(project.id(), requirement.id(), offer.id(), run.id(), ACTOR, context);
            var input = new NodeSynthesisInput(offer.baseline(), root.getCode(), null, "Selected catalogue root",
                    List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), "Preserve original.");
            var oldResult = new NodeSynthesisResult(root.getCode(), "Old projection", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            // Exact predecessor format: intentionally contains no inputEncoding property.
            String oldFingerprint = StableIdentityHash.sha256(json.writeValueAsString(Map.ofEntries(
                    Map.entry("format", "reformulation-step-v1"), Map.entry("provider", run.provider()), Map.entry("model", run.model()),
                    Map.entry("endpointHash", StableIdentityHash.sha256(ENDPOINT)), Map.entry("prompt", run.promptContent()),
                    Map.entry("promptVersion", run.promptVersion()), Map.entry("schemaVersion", run.schemaVersion()),
                    Map.entry("reconciliation", run.reconcileContext()), Map.entry("resultType", NodeSynthesisResult.class.getName()),
                    Map.entry("input", input))));
            proposals.completeCheckpoint(project.id(), requirement.id(), offer.id(), run.id(), "NODE",
                    oldFingerprint, json.writeValueAsString(oldResult), ACTOR, context);
            var steps = execution.checkpoints(project.id(), requirement.id(), offer.id(), run, ACTOR, context);
            var calls = new AtomicInteger();
            var current = new NodeSynthesisResult(root.getCode(), "Full group formulation reviewed as input",
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            var actual = steps.execute("NODE", input, NodeSynthesisResult.class, () -> { calls.incrementAndGet(); return current; });
            check(actual.equals(current) && calls.get() == 1, "Old lossy prompt cache was reused after an encoding change");
            check(steps.execute("NODE", input, NodeSynthesisResult.class, () -> { throw new AssertionError("Current cache missed"); }).equals(current),
                    "Current encoding did not reuse its own validated result");
            check(proposals.checkpoint(project.id(), requirement.id(), offer.id(), run.id(), "NODE", oldFingerprint, ACTOR, context)
                    .orElseThrow().equals(json.writeValueAsString(oldResult)), "Historical checkpoint was modified");
            check(proposals.get(project.id(), requirement.id(), offer.id(), ACTOR, context).equals(savedOffer), "Read/write checkpoint changed the saved offer");
            check(projects.getRequirement(project.id(), requirement.id(), ACTOR, context).currentVersionId().equals(requirement.currentVersionId()),
                    "Changed active requirement version");
            check(projects.listRequirementVersions(project.id(), requirement.id(), ACTOR, context).size() == 1,
                    "Created another requirement version");
            System.out.println("REFORMULATION_ENCODING_CACHE_OK");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
