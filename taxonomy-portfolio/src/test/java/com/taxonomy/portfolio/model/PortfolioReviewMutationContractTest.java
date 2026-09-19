package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Omitted optional review fields must not erase prior human decisions. */
class PortfolioReviewMutationContractTest {
    private static final Instant CREATED=Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant UPDATED=CREATED.plusSeconds(60);

    @Test void productCoverageKeepsItsOwnerAndAnOmittedReviewDecision() {
        var owner=mock(ProductCatalogEntry.class);
        var coverage=new ProductTaxonomyCoverage(owner,"CP-1",50,"first",null,"alice",CREATED);
        assertThat(coverage.getReviewStatus()).isEqualTo(ReviewStatus.PROPOSED);
        coverage.update(75,"reviewed",ReviewStatus.CONFIRMED,"bob",UPDATED);
        coverage.update(80,"new evidence",null,"carol",UPDATED.plusSeconds(1));
        assertThat(coverage.getProduct()).isSameAs(owner);assertThat(coverage.getNodeCode()).isEqualTo("CP-1");
        assertThat(coverage.getCoveragePercent()).isEqualTo(80);assertThat(coverage.getEvidence()).isEqualTo("new evidence");
        assertThat(coverage.getReviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
        assertThat(coverage.getUpdatedBy()).isEqualTo("carol");assertThat(coverage.getUpdatedAt()).isEqualTo(UPDATED.plusSeconds(1));
        assertThat(coverage.getId()).isNull();assertThat(coverage.getRowVersion()).isZero();
        assertThat(new ProductTaxonomyCoverage(owner,"CP-2",30,"e",ReviewStatus.CONFIRMED,"bob",CREATED).getReviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
    }
    @Test void solutionCoverageKeepsItsOwnerAndAnOmittedReviewDecision() {
        var owner=mock(SolutionDefinition.class);
        var coverage=new SolutionTaxonomyCoverage(owner,"CP-1",50,"first",null,"alice",CREATED);
        assertThat(coverage.getReviewStatus()).isEqualTo(ReviewStatus.PROPOSED);
        coverage.update(75,"reviewed",ReviewStatus.CONFIRMED,"bob",UPDATED);
        coverage.update(80,"new evidence",null,"carol",UPDATED.plusSeconds(1));
        assertThat(coverage.getSolution()).isSameAs(owner);assertThat(coverage.getNodeCode()).isEqualTo("CP-1");
        assertThat(coverage.getCoveragePercent()).isEqualTo(80);assertThat(coverage.getEvidence()).isEqualTo("new evidence");
        assertThat(coverage.getReviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
        assertThat(coverage.getUpdatedBy()).isEqualTo("carol");assertThat(coverage.getUpdatedAt()).isEqualTo(UPDATED.plusSeconds(1));
        assertThat(coverage.getId()).isNull();assertThat(coverage.getRowVersion()).isZero();
        assertThat(new SolutionTaxonomyCoverage(owner,"CP-2",30,"e",ReviewStatus.CONFIRMED,"bob",CREATED).getReviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
    }
    @Test void candidateUpdatePreservesReviewAndSelectionWhenOmitted() {
        var solution=mock(ProjectSolution.class);var product=mock(ProductCatalogEntry.class);
        var selected=ProductSelectionStatus.values()[1];
        var candidate=new SolutionProductCandidate(solution,product,40,"exclude","strength","weak","open",0.4,null,null,"alice",CREATED);
        assertThat(candidate.getReviewStatus()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(candidate.getSelectionStatus()).isEqualTo(ProductSelectionStatus.CANDIDATE);
        candidate.update(80,"new exclusion","new strength","new weakness","new evidence",0.8,ReviewStatus.CONFIRMED,selected,"bob",UPDATED);
        candidate.update(90,"last exclusion","last strength","last weakness","last evidence",0.9,null,null,"carol",UPDATED.plusSeconds(1));
        assertThat(candidate.getProjectSolution()).isSameAs(solution);assertThat(candidate.getProduct()).isSameAs(product);
        assertThat(candidate.getCoveragePercent()).isEqualTo(90);assertThat(candidate.getConfidence()).isEqualTo(0.9);
        assertThat(candidate.getHardExclusions()).isEqualTo("last exclusion");assertThat(candidate.getStrengths()).isEqualTo("last strength");
        assertThat(candidate.getWeaknesses()).isEqualTo("last weakness");assertThat(candidate.getOpenEvidence()).isEqualTo("last evidence");
        assertThat(candidate.getReviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);assertThat(candidate.getSelectionStatus()).isEqualTo(selected);
        assertThat(candidate.getUpdatedBy()).isEqualTo("carol");assertThat(candidate.getUpdatedAt()).isEqualTo(UPDATED.plusSeconds(1));
        assertThat(candidate.getId()).isNull();assertThat(candidate.getRowVersion()).isZero();
        var explicit=new SolutionProductCandidate(solution,product,80,null,null,null,null,0.8,ReviewStatus.CONFIRMED,selected,"bob",CREATED);
        assertThat(explicit.getReviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);assertThat(explicit.getSelectionStatus()).isEqualTo(selected);
    }
    @Test void projectSolutionPartialUpdateRetainsUnspecifiedDecisions() {
        var project=mock(ArchitectureProject.class);var solution=mock(SolutionDefinition.class);
        var status=ProjectSolutionStatus.values()[1];var action=ActionStatus.values()[1];
        var assigned=new ProjectSolution(project,solution,null,null,1,"initial","alice",CREATED);
        assertThat(assigned.getStatus()).isEqualTo(ProjectSolutionStatus.PROPOSED);assertThat(assigned.getActionStatus()).isEqualTo(ActionStatus.UNDECIDED);
        assigned.update(status,action,7,"reviewed",UPDATED);assigned.update(null,null,null,null,UPDATED.plusSeconds(1));
        assertThat(assigned.getStatus()).isEqualTo(status);assertThat(assigned.getActionStatus()).isEqualTo(action);
        assertThat(assigned.getPriority()).isEqualTo(7);assertThat(assigned.getRationale()).isEqualTo("reviewed");
        assertThat(assigned.getProject()).isSameAs(project);assertThat(assigned.getSolution()).isSameAs(solution);
        assertThat(assigned.getCreatedBy()).isEqualTo("alice");assertThat(assigned.getCreatedAt()).isEqualTo(CREATED);
        assertThat(assigned.getUpdatedAt()).isEqualTo(UPDATED.plusSeconds(1));
        var explicit=new ProjectSolution(project,solution,status,action,2,"explicit","bob",CREATED);
        assertThat(explicit.getStatus()).isEqualTo(status);assertThat(explicit.getActionStatus()).isEqualTo(action);
    }
    @Test void requirementLinkRetainsRoleAndReviewWhileReplacingEvidence() {
        var solution=mock(ProjectSolution.class);var requirement=mock(ProjectRequirement.class);
        var role=RequirementSolutionRole.values()[1];
        var link=new RequirementSolutionLink(solution,requirement,"s1",40,null,null,"initial","alice",CREATED);
        assertThat(link.getRole()).isEqualTo(RequirementSolutionRole.USES);assertThat(link.getReviewStatus()).isEqualTo(ReviewStatus.PROPOSED);
        link.update("s2",80,role,ReviewStatus.CONFIRMED,"reviewed","bob",UPDATED);
        link.update("s3",90,null,null,"later","carol",UPDATED.plusSeconds(1));
        assertThat(link.getRole()).isEqualTo(role);assertThat(link.getReviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
        assertThat(link.getSnapshotId()).isEqualTo("s3");assertThat(link.getCoveragePercent()).isEqualTo(90);
        assertThat(link.getEvidence()).isEqualTo("later");assertThat(link.getUpdatedBy()).isEqualTo("carol");
        assertThat(link.getUpdatedAt()).isEqualTo(UPDATED.plusSeconds(1));assertThat(link.getProjectSolution()).isSameAs(solution);
        assertThat(link.getRequirement()).isSameAs(requirement);
        var explicit=new RequirementSolutionLink(solution,requirement,"s",20,role,ReviewStatus.CONFIRMED,"e","alice",CREATED);
        assertThat(explicit.getRole()).isEqualTo(role);assertThat(explicit.getReviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
    }
    @Test void conflictReviewCanUpdateRationaleWithoutResettingStatus() {
        var conflict=new ProjectConflict(mock(ArchitectureProject.class),mock(ProjectRequirement.class),mock(ProjectRequirement.class),
                ConflictType.values()[0],"fingerprint","title","evidence",0.7,CREATED);
        var status=ConflictStatus.values()[1];conflict.review(status,"reviewed","alice",UPDATED);
        conflict.review(null,"clarified","bob",UPDATED.plusSeconds(1));
        assertThat(conflict.getStatus()).isEqualTo(status);assertThat(conflict.getResolutionNote()).isEqualTo("clarified");
        assertThat(conflict.getReviewedBy()).isEqualTo("bob");assertThat(conflict.getReviewedAt()).isEqualTo(UPDATED.plusSeconds(1));
        assertThat(conflict.getDetectedAt()).isEqualTo(CREATED);assertThat(conflict.getFingerprint()).isEqualTo("fingerprint");
    }
}
