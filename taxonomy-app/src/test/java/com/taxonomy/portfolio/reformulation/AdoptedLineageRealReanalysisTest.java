package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.dto.CopilotDtos.CopilotRunRequest;
import com.taxonomy.portfolio.model.AnalysisAutomationProfile;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.CopilotAutomationService;
import com.taxonomy.portfolio.service.PortablePortfolioGitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Real persisted post-adoption analysis; only outbound provider HTTP replies are replaced. */
@SpringBootTest(properties = {"llm.mock=false", "llm.provider=CUSTOM_OPENAI",
        "custom.llm.url=http://localhost:9999/v1/chat/completions",
        "custom.llm.model=reformulation-test", "custom.llm.api.key="})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@WithMockUser(username = "architect", roles = "ARCHITECT")
class AdoptedLineageRealReanalysisTest extends ReformulationWorkflowFixture {
    private static final Pattern KEYS = Pattern.compile("EXACTLY these keys: ([^\\r\\n]+)");
    private static final Pattern BUDGET = Pattern.compile("distribute the parent relevance score of (\\d+)");
    @Autowired org.springframework.web.client.RestTemplate transport;
    @Autowired ReformulationAdoptionService adoptions;
    @Autowired CopilotAutomationService copilot;
    @Autowired PortablePortfolioGitService git;

    @Test void adoptionThenRealAnalysisSnapshotNewOfferAndCheckpointPreserveFrozenLineage() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var server = MockRestServiceServer.bindTo(transport).build();
        server.expect(ExpectedCount.manyTimes(), MockRestRequestMatchers.anything()).andRespond(request -> {
            String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
            String prompt = json.readTree(body).at("/messages/0/content").asText();
            calls.add(prompt);
            String response;
            if (prompt.contains("RECONCILIATION_DATA_JSON\n")) {
                response = json.writeValueAsString(Map.of("affectedSectionIds", List.of(),
                        "sourceResolutions", List.of(), "findings", List.of()));
            } else if (prompt.contains("INPUT_DATA_JSON\n")) {
                var input = json.readTree(prompt.substring(prompt.indexOf("INPUT_DATA_JSON\n")
                        + "INPUT_DATA_JSON\n".length()));
                var statements = new ArrayList<String>();
                input.path("directContributions").forEach(s -> statements.add(s.path("id").asText()));
                input.path("children").forEach(child -> {
                    child.path("statementProposals").forEach(s -> statements.add(s.path("id").asText()));
                    child.path("preservedStatementIds").forEach(s -> statements.add(s.asText()));
                });
                var questions = new ArrayList<String>();
                input.path("openDecisions").forEach(q -> questions.add(q.path("id").asText()));
                input.path("children").forEach(child -> {
                    child.path("questionProposals").forEach(q -> questions.add(q.path("id").asText()));
                    child.path("preservedQuestionIds").forEach(q -> questions.add(q.asText()));
                });
                response = json.writeValueAsString(Map.of("summary", "Unreviewed selected source",
                        "statementProposals", List.of(), "preservedStatementIds", statements.stream().distinct().toList(),
                        "questionProposals", List.of(), "preservedQuestionIds", questions.stream().distinct().toList(),
                        "uncoveredSourceRefs", List.of(), "conflictCandidates", List.of()));
            } else {
                var match = KEYS.matcher(prompt);
                if (!match.find()) throw new AssertionError("Unexpected outbound model task: " + prompt.substring(0, Math.min(180, prompt.length())));
                var keys = Arrays.stream(match.group(1).split(",")).map(String::strip).toList();
                var budget = BUDGET.matcher(prompt);
                boolean categories = budget.find();
                var scores = new LinkedHashMap<String, Object>();
                for (int i = 0; i < keys.size(); i++) scores.put(keys.get(i),
                        Map.of("score", categories && i == 0 ? Integer.parseInt(budget.group(1)) : 0,
                                "reason", "Transport-only deterministic analysis reply"));
                response = json.writeValueAsString(scores);
            }
            return MockRestResponseCreators.withSuccess(json.writeValueAsString(Map.of("choices",
                    List.of(Map.of("message", Map.of("role", "assistant", "content", response))))),
                    MediaType.APPLICATION_JSON).createResponse(request);
        });

        var old = seed();
        var preview = adoptions.preview(project.id(), requirement.id(), old.id(),
                old.currentRevision().number(), "architect", context);
        adoptions.adopt(project.id(), requirement.id(), old.id(), old.currentRevision().number(),
                new ReformulationAdoptionDtos.ConfirmRequest(UUID.randomUUID().toString(),
                        preview.content().id(), preview.hash(), true, true, "Adopt before real reanalysis"),
                "architect", context);
        requirement = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        var operation = copilot.enqueueManual(project.id(), requirement.id(),
                new CopilotRunRequest("CUSTOM_OPENAI", 25, AnalysisAutomationProfile.EXHAUSTIVE,
                        1, true, true, true), "architect", context);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(90)).until(() ->
                EnumSet.of(AnalysisStatus.SUCCESS, AnalysisStatus.PARTIAL, AnalysisStatus.FAILED,
                        AnalysisStatus.CANCELLED).contains(copilot.getOperation(project.id(), operation.operationId(),
                        "architect", context).status()));
        var completed = copilot.getOperation(project.id(), operation.operationId(), "architect", context);
        var terminalSnapshot = completed.selectedSnapshotId() == null ? null
                : analyses.getSnapshot(project.id(), completed.selectedSnapshotId(), "architect", context);
        assertThat(completed.status())
                .as("terminal operation: %s; snapshot summary: %s; analysis warnings: %s",
                        completed, terminalSnapshot == null ? null : terminalSnapshot.summary(),
                        terminalSnapshot == null ? null : terminalSnapshot.analysis().getWarnings())
                .isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(completed.selectedSnapshotId()).isNotBlank().isNotEqualTo(snapshot);
        assertThat(completed.jobs()).isNotEmpty();
        var next = reformulations.create(project.id(), requirement.id(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(),
                        completed.selectedSnapshotId(), "de"), "architect", context);
        assertThat(next.baseline().frozenContext()).containsKeys("adoptedLineage", "inheritedDecisionContext");
        assertThat(next.baseline().snapshotId()).isEqualTo(completed.selectedSnapshotId());
        assertThat(git.exportPortfolio("architect", context)).contains("reformulationEvidence P R 2");
        var checkpoint = git.commit(context.currentBranch(), "Post-adoption reanalysis", "architect", context);
        assertThat(checkpoint.changed()).isTrue();
        assertThat(calls).anyMatch(prompt -> prompt.contains("EXACTLY these keys:"));
        server.verify();
    }
}
