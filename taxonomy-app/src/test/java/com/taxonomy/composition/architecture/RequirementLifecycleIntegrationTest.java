package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.requirements.RequirementRealization;
import com.taxonomy.dsl.requirements.RequirementTrace;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The complete later-project lifecycle runs against the real portfolio, editor journal and native JGit storage. */
@SpringBootTest
@WithMockUser(username = "alice", roles = "ADMIN")
class RequirementLifecycleIntegrationTest extends LifecycleTestSupport {
    @Test
    void nineRequirementsRemainInOneArchitectureThroughLaterProjectsAndAnExactNinthVersionChange() throws Exception {
        var context = workspace("nine-through-twelve");
        List<ArchitectureRequirementService.Saved> initial = new ArrayList<>();
        Long firstProjectId = null;
        for (int number = 1; number <= 9; number++) {
            String key = "REQ-" + number;
            var saved = create(context, firstProjectId, firstProjectId == null ? "INITIAL" : null, key,
                    number == 9 ? "Preserve the exact ninth decision and its audit evidence" : "Original requirement " + number);
            firstProjectId = saved.projectId();
            initial.add(saved);
            decide(context, saved.projectId(), saved.requirementId(), null, "COMPLETE",
                    List.of(number == 9 ? "element:recorder" : "element:dashboard"));
        }
        var ninth = initial.get(8);
        var originalNine = lifecycle.overview(context, null, null);
        assertThat(originalNine.requirements()).hasSize(9).allSatisfy(requirement -> {
            assertThat(requirement.version()).isEqualTo(1);
            assertThat(requirement.realization().state()).isEqualTo(RequirementRealization.State.COMPLETE);
        });
        var originalNinth = requirement(originalNine, "INITIAL", "REQ-9");
        String ninthHash = originalNinth.contentHash();
        Long ninthVersionId = originalNinth.versionId();

        var tenth = create(context, null, "LATER", "REQ-10", "Export the retained event stream from the later project");
        var tenthPartial = decide(context, tenth.projectId(), tenth.requirementId(), null, "PARTIAL", List.of("element:exporter"));
        var partial = lifecycle.overview(context, null, null);
        assertThat(partial.requirements()).hasSize(10);
        assertThat(requirement(partial, "LATER", "REQ-10").realization().state()).isEqualTo(RequirementRealization.State.PARTIAL);
        assertThat(requirement(partial, "INITIAL", "REQ-9").realization()).isEqualTo(originalNinth.realization());
        var tenthComplete = decide(context, tenth.projectId(), tenth.requirementId(), null, "COMPLETE",
                List.of("relation:recorder DEPENDS_ON exporter"));
        var complete = lifecycle.overview(context, null, null);
        assertThat(requirement(complete, "LATER", "REQ-10").realization().state()).isEqualTo(RequirementRealization.State.COMPLETE);
        assertThat(requirement(complete, "INITIAL", "REQ-9").realization()).isEqualTo(originalNinth.realization());
        var partialHistory = lifecycle.overview(context, tenthPartial.checkpoint().commitId(), null);
        assertThat(requirement(partialHistory, "LATER", "REQ-10").realization().state()).isEqualTo(RequirementRealization.State.PARTIAL);
        assertThat(partialHistory.requirements()).hasSize(10);
        assertThat(partialHistory.mayEdit()).isFalse();

        var eleventh = create(context, tenth.projectId(), null, "REQ-11", "Present event review results to the operator");
        decide(context, eleventh.projectId(), eleventh.requirementId(), null, "COMPLETE", List.of("element:dashboard"));
        var twelfth = create(context, null, "FOLLOWUP", "REQ-12", "Retain a later project contribution without replacing the architecture");
        decide(context, twelfth.projectId(), twelfth.requirementId(), null, "PARTIAL", List.of("element:exporter"));
        var beforeRevision = editor.read(context, null);
        var twelve = lifecycle.overview(context, null, null);
        assertThat(twelve.projects()).extracting(ArchitectureLifecycleService.Project::projectKey)
                .containsExactlyInAnyOrder("INITIAL", "LATER", "FOLLOWUP");
        assertThat(twelve.requirements()).hasSize(12);
        assertThat(requirement(twelve, "INITIAL", "REQ-9").realization()).isEqualTo(originalNinth.realization());
        assertThat(requirement(twelve, "LATER", "REQ-10").realization().state()).isEqualTo(RequirementRealization.State.COMPLETE);
        assertThat(requirement(twelve, "FOLLOWUP", "REQ-12").realization().state()).isEqualTo(RequirementRealization.State.PARTIAL);

        String changedNinth = "  Preserve the ninth decision with extended retention and exact whitespace\n";
        var revisionRequest = change(context, ninth.projectId(), ninth.requirementId(), changedNinth, tenth.projectId());
        var changed = changes.save(context, revisionRequest);
        assertThat(changed.publication().state()).isEqualTo("CHECKPOINTED");
        var current = lifecycle.overview(context, null, null);
        assertThat(current.requirements()).hasSize(12);
        var currentNinth = requirement(current, "INITIAL", "REQ-9");
        assertThat(currentNinth.id()).isEqualTo(originalNinth.id());
        assertThat(currentNinth.requirementId()).isEqualTo(ninth.requirementId());
        assertThat(currentNinth.version()).isEqualTo(2);
        assertThat(currentNinth.versionId()).isNotEqualTo(ninthVersionId);
        assertThat(currentNinth.text()).isEqualTo(changedNinth);
        assertThat(currentNinth.contentHash()).isEqualTo(RequirementRealization.textHash(changedNinth)).isNotEqualTo(ninthHash);
        assertThat(currentNinth.realization().state()).isEqualTo(RequirementRealization.State.REVIEW_REQUIRED);
        assertThat(currentNinth.realization().reviewed()).isEqualTo(originalNinth.realization().reviewed());
        assertThat(currentNinth.contributors()).contains(new RequirementTrace.Contribution("INITIAL", 1, ninthHash),
                new RequirementTrace.Contribution("LATER", 2, currentNinth.contentHash()));
        for (int number = 1; number <= 8; number++)
            assertThat(requirement(current, "INITIAL", "REQ-" + number).realization().state()).isEqualTo(RequirementRealization.State.COMPLETE);
        assertThat(requirement(current, "LATER", "REQ-10").realization().state()).isEqualTo(RequirementRealization.State.COMPLETE);
        assertThat(requirement(current, "LATER", "REQ-11").realization().state()).isEqualTo(RequirementRealization.State.COMPLETE);
        assertThat(requirement(current, "FOLLOWUP", "REQ-12").realization().state()).isEqualTo(RequirementRealization.State.PARTIAL);
        List<RequirementVersionView> versions = projects.listRequirementVersions(ninth.projectId(), ninth.requirementId(), "alice", scope(context));
        assertThat(versions).extracting(RequirementVersionView::versionNumber).containsExactly(2, 1);
        assertThat(versions.getLast().id()).isEqualTo(ninthVersionId);
        assertThat(versions.getLast().text()).isEqualTo(originalNinth.text());
        assertThat(versions.getLast().contentHash()).isEqualTo(ninthHash);

        for (var historical : List.of(lifecycle.overview(context, beforeRevision.context().commit(), null),
                lifecycle.overview(context, null, beforeRevision.context().revision()))) {
            assertThat(historical.mayEdit()).isFalse();
            assertThat(historical.requirements()).hasSize(12);
            var oldNinth = requirement(historical, "INITIAL", "REQ-9");
            assertThat(oldNinth.version()).isEqualTo(1);
            assertThat(oldNinth.text()).isEqualTo(originalNinth.text());
            assertThat(oldNinth.contentHash()).isEqualTo(ninthHash);
            assertThat(oldNinth.realization().state()).isEqualTo(RequirementRealization.State.COMPLETE);
        }
        String currentSource = editor.read(context, null).dsl();
        assertThat(currentSource).startsWith("# Keep this architecture annotation through every portfolio contribution.\n")
                .contains("x-source-evidence: \"Original authored model\"");
        assertThat(ArchitectureSemanticPatch.index(currentSource)).containsKeys("element:recorder", "element:exporter", "element:dashboard",
                "relation:recorder DEPENDS_ON exporter", "requirement:INITIAL__REQ-9", "requirement:LATER__REQ-10");
        assertThat(git.resolveRepository(context).getDslAtCommit(tenthComplete.checkpoint().commitId()))
                .contains("requirement LATER__REQ-10").doesNotContain("requirement FOLLOWUP__REQ-12");
        var afterRevision = capture(context);
        var replay = changes.save(context, revisionRequest);
        assertThat(replay.publication().checkpoint().commitId()).isEqualTo(changed.publication().checkpoint().commitId());
        assertUnchanged(context, afterRevision);
    }
}
