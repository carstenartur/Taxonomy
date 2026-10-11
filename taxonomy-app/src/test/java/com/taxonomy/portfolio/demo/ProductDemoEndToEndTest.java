package com.taxonomy.portfolio.demo;

import com.taxonomy.analysis.demo.DemoScenario;
import com.taxonomy.analysis.demo.DemoScenarioCatalog;
import com.taxonomy.analysis.demo.evaluation.DemoReferenceEvaluator;
import com.taxonomy.composition.reformulation.ReformulationExecutionService;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.reformulation.ReformulationAdoptionDtos;
import com.taxonomy.portfolio.reformulation.ReformulationAdoptionService;
import com.taxonomy.portfolio.reformulation.ReformulationDtos;
import com.taxonomy.portfolio.reformulation.ReformulationService;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioReportService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

/**
 * Complete product demo (issue #1184) through the real product paths: R0, analysis A0,
 * bottom-up reformulation with questions, visible demo answers and a rejected addition,
 * explicit adoption, R1, analysis A1 and the independent reference evaluation. Only the
 * external AI reply is replaced by the scenario replay; any outbound provider HTTP fails.
 */
@SpringBootTest(properties = {"llm.mock=true"})
@AutoConfigureMockMvc
@WithMockUser(username = "architect", roles = "ARCHITECT")
class ProductDemoEndToEndTest {
    private static final String USER = "architect";

    @Autowired ProductDemoService demos;
    @Autowired ProjectPortfolioService projects;
    @Autowired ProjectRequirementAnalysisService analysis;
    @Autowired PortfolioAnalysisPersistenceService analyses;
    @Autowired ReformulationService reformulations;
    @Autowired ReformulationExecutionService execution;
    @Autowired ReformulationAdoptionService adoptions;
    @Autowired PortfolioReportService reports;
    @Autowired WorkspaceManager workspaces;
    @Autowired RestTemplate transport;
    @MockitoBean WorkspaceResolver resolver;

    private final DemoScenario scenario = DemoScenarioCatalog.builtin().defaultScenario();

    @Test
    void completeDemoRunsWithoutExternalAiAndPassesTheIndependentReference() {
        var server = MockRestServiceServer.bindTo(transport).build();
        server.expect(ExpectedCount.never(), MockRestRequestMatchers.anything());
        WorkspaceContext context = workspace();

        // An unrelated draft stays untouched; demo and normal projects are isolated.
        var normal = projects.createProject(new CreateProjectRequest("NORMAL", "Normal", null, ProjectStatus.ACTIVE,
                null, null, null, null), USER, context);
        var draft = projects.createRequirement(normal.id(), new CreateRequirementRequest("D-1", "Draft",
                "Mein unvollständiger Entwurf", RequirementStatus.DRAFT, null, null, null, null, null, "draft", null),
                USER, context);

        var run = demos.start(null, USER, context);
        assertThat(run.scenarioId()).isEqualTo(scenario.id());
        assertThat(run.banner().get("de")).startsWith("DEMODUS");
        assertThat(run.activeProvider()).isEqualTo("DEMO.TIME-TRACKING-V1.R0");
        assertThat(run.projectId()).isNotEqualTo(normal.id());
        assertThat(projects.getRequirement(run.projectId(), run.requirementId(), USER, context).currentVersion().text())
                .isEqualTo(scenario.r0Text());
        assertThat(projects.getRequirement(normal.id(), draft.id(), USER, context).currentVersion().text())
                .isEqualTo("Mein unvollständiger Entwurf");

        // Admission: no demo provider for normal projects, no live provider for demo projects.
        assertThatThrownBy(() -> analysis.analyzeRequirement(normal.id(), draft.id(), "DEMO.TIME-TRACKING-V1.R0", 25,
                UUID.randomUUID().toString(), USER, context)).isInstanceOf(PortfolioException.class);
        assertThatThrownBy(() -> analysis.analyzeRequirement(run.projectId(), run.requirementId(), "GEMINI", 25,
                UUID.randomUUID().toString(), USER, context)).isInstanceOf(PortfolioException.class);

        // A0 on R0.
        var a0 = analysis.analyzeRequirement(run.projectId(), run.requirementId(), null, 25,
                UUID.randomUUID().toString(), USER, context);
        assertThat(a0.provider()).isEqualTo("DEMO.TIME-TRACKING-V1.R0");
        assertThat(a0.status()).as(() -> "A0 job " + a0 + " warnings " + analyses.getSnapshot(run.projectId(),
                a0.items().getFirst().snapshotId(), USER, context).analysis().getWarnings()).isEqualTo(AnalysisStatus.SUCCESS);
        String a0Snapshot = a0.items().getFirst().snapshotId();

        // Bottom-up reformulation with five questions and one surveillance addition.
        var requirement = projects.getRequirement(run.projectId(), run.requirementId(), USER, context);
        var proposal = reformulations.create(run.projectId(), run.requirementId(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(), a0Snapshot, "de"), USER, context);
        runReformulation(run, proposal, context, "COMPLETED");
        proposal = reformulations.get(run.projectId(), run.requirementId(), proposal.id(), USER, context);
        var first = proposal.currentRevision();
        assertThat(first.questions()).hasSize(5);
        String gps = scenario.demoReviews().getFirst().wording();
        var gpsStatement = first.statements().stream().filter(s -> s.wording().equals(gps)).findFirst().orElseThrow();

        // Visible human review: reject the addition and answer with the scenario's demo decisions.
        proposal = reformulations.statement(run.projectId(), run.requirementId(), proposal.id(),
                proposal.currentRevision().number(), gpsStatement.id(),
                new ReformulationDtos.StatementRequest("REJECT", null, scenario.demoReviews().getFirst().rationale()),
                USER, context);
        for (var decision : scenario.demoDecisions()) {
            var authored = scenario.question(decision.question());
            var question = proposal.currentRevision().questions().stream()
                    .filter(q -> q.key().subject().equals(authored.subject())).findFirst().orElseThrow();
            proposal = reformulations.answer(run.projectId(), run.requirementId(), proposal.id(),
                    proposal.currentRevision().number(), new ReformulationDtos.AnswerRequest(question.id(), "ANSWER",
                            decision.values(), "", decision.rationale()), USER, context);
        }
        assertThat(projects.getRequirement(run.projectId(), run.requirementId(), USER, context).currentVersion()
                .versionNumber()).as("answers never adopt").isEqualTo(1);

        // The regeneration never overwrites the human review: it is offered as a protected candidate
        // that the user takes over explicitly.
        var protectedRun = runReformulation(run, proposal, context, "PARTIAL");
        assertThat(protectedRun.failureCode()).isEqualTo("MANUAL_DRAFT_PROTECTED");
        String reformulated = protectedRun.candidate().text();
        assertThat(reformulated).contains(scenario.r1RequiredPhrases().toArray(String[]::new)).doesNotContain(gps)
                .contains(scenario.r0Text());
        assertThat(protectedRun.candidate().statements()).filteredOn(s -> s.wording().equals(gps))
                .allSatisfy(s -> assertThat(s.reviewState()).isEqualTo("REJECTED"));
        proposal = reformulations.saveDraft(run.projectId(), run.requirementId(), proposal.id(),
                proposal.currentRevision().number(), new ReformulationDtos.SaveDraftRequest(reformulated,
                        "Produktdemo: geprüften Kandidaten übernehmen"), USER, context);

        // Explicit adoption: preview plus confirmation; a duplicate confirmation creates no second version.
        var preview = adoptions.preview(run.projectId(), run.requirementId(), proposal.id(),
                proposal.currentRevision().number(), USER, context);
        var confirm = new ReformulationAdoptionDtos.ConfirmRequest(UUID.randomUUID().toString(), preview.content().id(),
                preview.hash(), true, true, "Produktdemo: geprüfte Neufassung übernehmen");
        var adopted = adoptions.adopt(run.projectId(), run.requirementId(), proposal.id(),
                proposal.currentRevision().number(), confirm, USER, context);
        var again = adoptions.adopt(run.projectId(), run.requirementId(), proposal.id(),
                proposal.currentRevision().number(), confirm, USER, context);
        assertThat(adopted.targetVersionNumber()).isEqualTo(2);
        assertThat(again.targetVersionId()).isEqualTo(adopted.targetVersionId());
        assertThat(projects.listRequirementVersions(run.projectId(), run.requirementId(), USER, context)).hasSize(2);
        assertThat(demos.get(run.id(), USER, context).activeProvider()).isEqualTo("DEMO.TIME-TRACKING-V1.R1");

        // A1 on R1 and the independent reference evaluation.
        var a1 = analysis.analyzeRequirement(run.projectId(), run.requirementId(), null, 25,
                UUID.randomUUID().toString(), USER, context);
        assertThat(a1.provider()).isEqualTo("DEMO.TIME-TRACKING-V1.R1");
        assertThat(a1.status()).as(() -> "A1 job " + a1 + " warnings " + analyses.getSnapshot(run.projectId(),
                a1.items().getFirst().snapshotId(), USER, context).analysis().getWarnings()).isEqualTo(AnalysisStatus.SUCCESS);
        var comparison = demos.compare(run.id(), USER, context);
        assertThat(comparison.a0SnapshotId()).isEqualTo(a0Snapshot);
        assertThat(comparison.report().overall()).as("reference checks %s", comparison.report().checks())
                .isEqualTo(DemoReferenceEvaluator.Overall.PASS);

        // Second generation on the adopted R1: no repeated questions, the rejected addition stays out.
        var r1Requirement = projects.getRequirement(run.projectId(), run.requirementId(), USER, context);
        var next = reformulations.create(run.projectId(), run.requirementId(), new ReformulationDtos.CreateRequest(
                r1Requirement.currentVersionId(), a1.items().getFirst().snapshotId(), "de"), USER, context);
        assertThat(next.baseline().frozenContext()).containsKeys("adoptedLineage", "inheritedDecisionContext");
        var secondGeneration = runReformulation(run, next, context, "COMPLETED");
        assertThat(secondGeneration.provider()).isEqualTo("DEMO.TIME-TRACKING-V1.R1");
        var nextRevision = reformulations.get(run.projectId(), run.requirementId(), next.id(), USER, context).currentRevision();
        assertThat(nextRevision.text()).doesNotContain(gps);
        assertThat(nextRevision.questions()).isEmpty();

        // Exports of the demo project stay labelled as demo output.
        var export = reports.render(run.projectId(), run.requirementId(), PortfolioReportService.Format.MARKDOWN,
                null, USER, context);
        assertThat(export.filename()).startsWith("DEMO-");
        assertThat(new String(export.bytes(), java.nio.charset.StandardCharsets.UTF_8)).contains("Produktdemo · ");

        // Closing the demo stops further replay admission.
        demos.close(run.id(), USER, context);
        assertThatThrownBy(() -> analysis.analyzeRequirement(run.projectId(), run.requirementId(), null, 25,
                UUID.randomUUID().toString(), USER, context)).isInstanceOf(PortfolioException.class)
                .hasMessageContaining("DEMO_RUN_CLOSED");
        server.verify();
    }

    private ReformulationDtos.Run runReformulation(ProductDemoService.DemoRunView run, ReformulationDtos.Proposal proposal,
                                                   WorkspaceContext context, String expectedStatus) {
        var started = execution.start(run.projectId(), run.requirementId(), proposal.id(),
                proposal.currentRevision().number(), USER, context);
        assertThat(started.provider()).startsWith("DEMO.TIME-TRACKING-V1.R");
        await().atMost(Duration.ofSeconds(60)).until(() -> reformulations.runs(run.projectId(), run.requirementId(),
                proposal.id(), USER, context).stream().filter(r -> r.id().equals(started.id()))
                .anyMatch(r -> List.of("COMPLETED", "PARTIAL", "FAILED", "CANCELLED").contains(r.status())));
        var finished = reformulations.runs(run.projectId(), run.requirementId(), proposal.id(), USER, context).stream()
                .filter(r -> r.id().equals(started.id())).findFirst().orElseThrow();
        assertThat(finished.status()).as("run %s", finished).isEqualTo(expectedStatus);
        return finished;
    }

    private WorkspaceContext workspace() {
        var workspace = workspaces.createWorkspace(USER, "Demo " + UUID.randomUUID(), "Product demo");
        workspace = workspaces.provisionWorkspaceRepository(USER, workspace.getWorkspaceId());
        var context = new WorkspaceContext(USER, workspace.getWorkspaceId(), workspace.getCurrentBranch(),
                workspace.getSourceRepositoryId());
        when(resolver.resolveCurrentUsername()).thenReturn(USER);
        when(resolver.resolveCurrentContext()).thenReturn(context);
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(RepositoryContext.workspace(
                context.repositoryId(), context.workspaceId(), context.currentBranch(), USER));
        return context;
    }
}
