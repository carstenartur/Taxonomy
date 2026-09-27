package com.taxonomy.portfolio.reformulation;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.PortablePortfolioGitService;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.reformulation.DecisionQuestion;
import com.taxonomy.reformulation.ValidationReport;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "llm.mock=true")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@WithMockUser(username = "architect", roles = "ARCHITECT")
class ReformulationPositiveReviewGuardTest extends ReformulationWorkflowFixture {
    @Autowired ReformulationAdoptionService adoption;
    @Autowired PortablePortfolioGitService git;
    @Autowired ReformulationEvidenceCodec evidenceCodec;
    @Autowired ReformulationPortableEvidenceRepository importedEvidence;
    @Autowired ReformulationAdoptionRepository adoptionRows;

    private void adopt(boolean conflict) throws Exception {
        if (conflict) questionTransform = questions -> questions.stream().map(q -> q.id().equals("channel")
                ? new DecisionQuestion(q.id(), q.key(), q.wording(), q.discoveries(), q.affectedStatementIds(),
                        q.answerSchema(), q.prerequisites(), q.dependentQuestionIds(), q.consequences(), DecisionQuestion.State.CONFLICT)
                : q).toList();
        var offer = seed();
        var preview = adoption.preview(project.id(), requirement.id(), offer.id(), offer.currentRevision().number(), "architect", context);
        adoption.adopt(project.id(), requirement.id(), offer.id(), offer.currentRevision().number(),
                new ReformulationAdoptionDtos.ConfirmRequest(UUID.randomUUID().toString(), preview.content().id(),
                        preview.hash(), true, true, "Explicit adoption"), "architect", context);
    }

    private String requirementUrl(long projectId, long requirementId) {
        return "/api/projects/" + projectId + "/requirements/" + requirementId;
    }

    private void positive(long projectId, long requirementId, int expectedStatus) throws Exception {
        mvc.perform(patch(requirementUrl(projectId, requirementId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Reviewed title\",\"status\":\"APPROVED\",\"reviewStatus\":\"CONFIRMED\"}"))
                .andExpect(expectedStatus == 409 ? status().isConflict() : status().isOk());
    }

    private void patchMetadata(long projectId, long requirementId, String body, int expectedStatus) throws Exception {
        mvc.perform(patch(requirementUrl(projectId, requirementId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(expectedStatus == 409 ? status().isConflict() : status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = RequirementStatus.class, names = {"APPROVED", "IMPLEMENTING", "SATISFIED"})
    void blockingLocalEvidencePreventsEachPositiveRequirementStateWithoutReviewChange(RequirementStatus state) throws Exception {
        adopt(true);
        patchMetadata(project.id(), requirement.id(), "{\"status\":\"" + state + "\"}", 409);
        assertThat(projects.getRequirement(project.id(), requirement.id(), "architect", context).status()).isEqualTo(RequirementStatus.DRAFT);
    }

    @Test void blockingLocalEvidencePreventsConfirmedReviewWithoutStatusChange() throws Exception {
        adopt(true);
        patchMetadata(project.id(), requirement.id(), "{\"reviewStatus\":\"CONFIRMED\"}", 409);
        assertThat(projects.getRequirement(project.id(), requirement.id(), "architect", context).reviewStatus()).isEqualTo(ReviewStatus.PROPOSED);
    }

    @Test void mismatchedLocalPreviewCannotAuthorizePositiveReview() throws Exception {
        adopt(false);
        var current = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        var receipt = adoptionRows.findByRequirementIdAndTargetVersionIdAndScopeKey(
                requirement.id(), current.currentVersionId(), PortfolioScope.key("architect", context)).getFirst();
        var altered = (tools.jackson.databind.node.ObjectNode) json.readTree(receipt.getPreview().getPayload());
        altered.put("finalText", "A different adopted target");
        String payload = json.writeValueAsString(altered);
        jdbc.update("update reformulation_adoption_preview set preview_payload = ?, content_hash = ? where id = ?",
                payload, StableIdentityHash.sha256(payload), receipt.getPreviewId());
        positive(project.id(), requirement.id(), 409);
    }

    @Test void deferredQuestionAloneIsNonblocking() throws Exception {
        questionTransform = questions -> questions.stream().map(q -> q.id().equals("channel")
                ? new DecisionQuestion(q.id(), q.key(), q.wording(), q.discoveries(), q.affectedStatementIds(),
                        q.answerSchema(), q.prerequisites(), q.dependentQuestionIds(), q.consequences(), DecisionQuestion.State.DEFERRED)
                : q).toList();
        adopt(false);
        patchMetadata(project.id(), requirement.id(), "{\"reviewStatus\":\"CONFIRMED\"}", 200);
    }

    @Test void adoptedConflictFindingAloneBlocksPositiveReview() throws Exception {
        documentTransform = doc -> new com.taxonomy.reformulation.ReformulationDocument(doc.text(), doc.sections(), doc.statements(),
                doc.questions(), new ValidationReport(List.of(new ValidationReport.Finding(ValidationReport.Kind.CONFLICT,
                        "BOUNDARY_CONFLICT", "Needs expert resolution", List.of(), List.of()))), doc.nodeResults());
        adopt(false);
        patchMetadata(project.id(), requirement.id(), "{\"status\":\"APPROVED\"}", 409);
    }

    @Test void structurallyInvalidPortableEvidenceBlocksPositiveReview() throws Exception {
        adopt(false);
        String dsl = git.exportPortfolio("architect", context);
        var parsed = new TaxDslParser().parse(dsl, "review-evidence.taxdsl");
        var original = parsed.blocksOfKind(ReformulationEvidenceCodec.BLOCK_KIND).getFirst();
        var source = json.readValue(original.property("payload"), ReformulationEvidenceCodec.Payload.class);
        var old = source.revision();
        var invalid = new ReformulationDtos.Revision(old.number(), old.predecessor(), old.text(), old.sections(), old.statements(),
                old.questions(), old.answers(), new ValidationReport(List.of(new ValidationReport.Finding(
                    ValidationReport.Kind.STRUCTURAL_LOSS, "SOURCE_LOSS", "Source was lost", List.of(), List.of()))),
                old.actor(), old.createdAt(), old.rationale(), old.impact(), old.variantOrigin());
        var amended = new ReformulationEvidenceCodec.Payload(source.projectKey(), source.requirementKey(), source.sourceVersionNumber(),
                source.previousActiveVersionNumber(), source.targetVersionNumber(), source.analysisSnapshotId(), source.originalText(),
                source.finalText(), source.targetContentHash(), source.proposalRevision(), source.actor(), source.rationale(), invalid);
        String payload = json.writeValueAsString(amended);
        String hash = StableIdentityHash.sha256(payload);
        var blocks = parsed.getBlocks().stream().map(block -> {
            if (block != original) return block;
            var properties = block.getProperties().stream().map(property -> {
                if (property.key().equals("payload")) return new PropertyAst("payload", payload, property.sourceLocation());
                if (property.key().equals("evidenceHash")) return new PropertyAst("evidenceHash", hash, property.sourceLocation());
                return property;
            }).toList();
            return new BlockAst(block.getKind(), List.of("P", "R", Integer.toString(source.targetVersionNumber()), hash), properties,
                    block.getChildren(), block.getExtensions(), block.getSourceLocation());
        }).toList();
        String historical = new TaxDslSerializer().serialize(new DocumentAst(parsed.getMeta(), blocks));
        assertThat(evidenceCodec.validateForMaterialization(historical)).hasSize(1);
        var workspace = workspaces.createWorkspace("architect", "Structural evidence " + UUID.randomUUID(), "Review guard");
        workspace = workspaces.provisionWorkspaceRepository("architect", workspace.getWorkspaceId());
        WorkspaceContext target = new WorkspaceContext("architect", workspace.getWorkspaceId(), workspace.getCurrentBranch(), workspace.getSourceRepositoryId());
        git.materialize(historical, "architect", target);
        var importedProject = projects.listProjects("architect", target).getFirst();
        var importedRequirement = projects.listRequirements(importedProject.id(), "architect", target).getFirst();
        select(target);
        positive(importedProject.id(), importedRequirement.id(), 409);
    }

    @Test void sameBusinessKeysInDifferentWorkspaceHaveNoAdoptionEvidence() throws Exception {
        adopt(true);
        var workspace = workspaces.createWorkspace("architect", "Independent keys " + UUID.randomUUID(), "Scope guard");
        workspace = workspaces.provisionWorkspaceRepository("architect", workspace.getWorkspaceId());
        WorkspaceContext target = new WorkspaceContext("architect", workspace.getWorkspaceId(), workspace.getCurrentBranch(), workspace.getSourceRepositoryId());
        var separateProject = projects.createProject(new CreateProjectRequest("P", "Separate", "No adoption", ProjectStatus.ACTIVE,
                null, null, null, null), "architect", target);
        var separateRequirement = projects.createRequirement(separateProject.id(),
                new CreateRequirementRequest("R", "Separate", "Unrelated text", RequirementStatus.DRAFT, 50,
                        Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, "architect", "Source", null),
                "architect", target);
        assertThat(PortfolioScope.key("architect", target)).isNotEqualTo(PortfolioScope.key("architect", context));
        select(target);
        positive(separateProject.id(), separateRequirement.id(), 200);
    }

    @Test void currentLocalConflictCannotBecomePositiveAndFailedPatchIsAtomic() throws Exception {
        adopt(true);
        var before = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        positive(project.id(), requirement.id(), 409);
        var after = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        assertThat(after).isEqualTo(before);
    }

    @Test void openQuestionsAreNonblocking() throws Exception {
        adopt(false);
        positive(project.id(), requirement.id(), 200);
        assertThat(projects.getRequirement(project.id(), requirement.id(), "architect", context).status()).isEqualTo(RequirementStatus.APPROVED);
        assertThat(projects.getRequirement(project.id(), requirement.id(), "architect", context).reviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
    }

    @Test void newerCurrentVersionIsIndependentOfOldConflict() throws Exception {
        adopt(true);
        projects.addRequirementVersion(project.id(), requirement.id(), new CreateRequirementVersionRequest("A new independent source", "Next", null), "architect", context);
        positive(project.id(), requirement.id(), 200);
    }

    @Test void localConflictDoesNotBlockAnotherRequirement() throws Exception {
        adopt(true);
        var other = createRequirement("INDEPENDENT");
        positive(project.id(), other.id(), 200);
        assertThat(projects.getRequirement(project.id(), other.id(), "architect", context).reviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
    }

    @Test void importedCurrentConflictBlocksOnlyMatchingTargetScope() throws Exception {
        adopt(true);
        String dsl = git.exportPortfolio("architect", context);
        var workspace = workspaces.createWorkspace("architect", "Imported guard " + UUID.randomUUID(), "Review guard");
        workspace = workspaces.provisionWorkspaceRepository("architect", workspace.getWorkspaceId());
        WorkspaceContext target = new WorkspaceContext("architect", workspace.getWorkspaceId(), workspace.getCurrentBranch(), workspace.getSourceRepositoryId());
        git.materialize(dsl, "architect", target);
        var importedProject = projects.listProjects("architect", target).getFirst();
        var importedRequirement = projects.listRequirements(importedProject.id(), "architect", target).getFirst();
        select(target);
        var before = projects.getRequirement(importedProject.id(), importedRequirement.id(), "architect", target);
        positive(importedProject.id(), importedRequirement.id(), 409);
        assertThat(projects.getRequirement(importedProject.id(), importedRequirement.id(), "architect", target)).isEqualTo(before);
    }

    @Test void importedConflictRetainsPortableKeysButMatchesExistingDifferentCaseBusinessIdentity() throws Exception {
        adopt(true);
        var parsed = new TaxDslParser().parse(git.exportPortfolio("architect", context), "case-fold-source.taxdsl");
        var originalEvidence = parsed.blocksOfKind(ReformulationEvidenceCodec.BLOCK_KIND).getFirst();
        var source = json.readValue(originalEvidence.property("payload"), ReformulationEvidenceCodec.Payload.class);
        var amended = new ReformulationEvidenceCodec.Payload("p", "r", source.sourceVersionNumber(),
                source.previousActiveVersionNumber(), source.targetVersionNumber(), source.analysisSnapshotId(), source.originalText(),
                source.finalText(), source.targetContentHash(), source.proposalRevision(), source.actor(), source.rationale(), source.revision());
        String lowerPayload = json.writeValueAsString(amended);
        String lowerHash = StableIdentityHash.sha256(lowerPayload);
        var lowercaseBlocks = parsed.getBlocks().stream().map(block -> {
            String kind = block.getKind();
            if (!List.of("project", "projectRequirement", "requirementVersion", ReformulationEvidenceCodec.BLOCK_KIND).contains(kind)) return block;
            var header = new java.util.ArrayList<>(block.getHeaderTokens());
            header.set(0, "p");
            if (!kind.equals("project")) header.set(1, "r");
            if (kind.equals(ReformulationEvidenceCodec.BLOCK_KIND)) header.set(3, lowerHash);
            var properties = kind.equals(ReformulationEvidenceCodec.BLOCK_KIND)
                    ? block.getProperties().stream().map(property -> {
                        if (property.key().equals("payload")) return new PropertyAst("payload", lowerPayload, property.sourceLocation());
                        if (property.key().equals("evidenceHash")) return new PropertyAst("evidenceHash", lowerHash, property.sourceLocation());
                        return property;
                    }).toList() : block.getProperties();
            return new BlockAst(kind, header, properties, block.getChildren(), block.getExtensions(), block.getSourceLocation());
        }).toList();
        String dsl = new TaxDslSerializer().serialize(new DocumentAst(parsed.getMeta(), lowercaseBlocks));
        var sourceBlock = new TaxDslParser().parse(dsl, "case-fold-import.taxdsl")
                .blocksOfKind(ReformulationEvidenceCodec.BLOCK_KIND).getFirst();
        assertThat(evidenceCodec.validateForMaterialization(dsl)).hasSize(1);
        var workspace = workspaces.createWorkspace("architect", "Case folded evidence " + UUID.randomUUID(), "Review guard");
        workspace = workspaces.provisionWorkspaceRepository("architect", workspace.getWorkspaceId());
        WorkspaceContext target = new WorkspaceContext("architect", workspace.getWorkspaceId(), workspace.getCurrentBranch(), workspace.getSourceRepositoryId());
        var lowerProject = projects.createProject(new CreateProjectRequest("P", "Existing project", "Same identity", ProjectStatus.ACTIVE,
                null, null, null, null), "architect", target);
        var lowerRequirement = projects.createRequirement(lowerProject.id(), new CreateRequirementRequest("R", "Existing requirement",
                ORIGINAL, RequirementStatus.DRAFT, 50, Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED,
                "architect", "Original", null), "architect", target);
        git.materialize(dsl, "architect", target);
        var current = projects.getRequirement(lowerProject.id(), lowerRequirement.id(), "architect", target);
        assertThat(projects.getProject(lowerProject.id(), "architect", target).projectKey()).isEqualTo("P");
        assertThat(current.requirementKey()).isEqualTo("R");
        assertThat(current.currentVersion().versionNumber()).isEqualTo(2);
        var stored = importedEvidence.findByScopeKeyOrderByProjectKeyAscRequirementKeyAscTargetVersionNumberAscEvidenceHashAsc(
                PortfolioScope.key("architect", target));
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getProjectKey()).isEqualTo("p");
        assertThat(stored.getFirst().getRequirementKey()).isEqualTo("r");
        assertThat(stored.getFirst().getPayload()).isEqualTo(sourceBlock.property("payload"));
        assertThat(stored.getFirst().getEvidenceHash()).isEqualTo(sourceBlock.property("evidenceHash"));
        select(target);
        positive(lowerProject.id(), lowerRequirement.id(), 409);
        assertThat(projects.getRequirement(lowerProject.id(), lowerRequirement.id(), "architect", target).status()).isNotEqualTo(RequirementStatus.APPROVED);
    }
}
