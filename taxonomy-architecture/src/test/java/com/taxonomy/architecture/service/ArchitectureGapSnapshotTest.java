package com.taxonomy.architecture.service;

import com.taxonomy.catalog.repository.*;
import com.taxonomy.dto.*;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Authored deterministic canonical model fixture; no provider call. */
class ArchitectureGapSnapshotTest {
    private final String commit = "a".repeat(40);
    private final RepositoryContext context = RepositoryContext.centralRead("repo", "main", "user");
    private final RelationProjectionReadService projections = mock(RelationProjectionReadService.class);
    private final WorkspaceDslReadPort dsl = mock(WorkspaceDslReadPort.class);
    private final WorkspaceDslReadPort.RepositoryRead read = mock(WorkspaceDslReadPort.RepositoryRead.class);
    private final TaxonomyRelationRepository legacy = mock(TaxonomyRelationRepository.class);
    private final ArchitectureGapService service = new ArchitectureGapService(projections, dsl);
    private void fixture(String status) throws Exception {
        when(dsl.openRead(context)).thenReturn(read);
        when(read.dslAtCommit(commit)).thenReturn(Optional.of("""
                element service type CoreService {
                  title: "Service";
                }
                element role type BusinessRole {
                  title: "Role";
                }
                """));
        var edge = new RelationProjectionReadService.SnapshotRelation(1L, "service", "role", RelationType.SUPPORTS,
                status, 0.99, "fixture", commit, "fixture-event");
        when(projections.readSnapshot(context, commit)).thenReturn(new RelationProjectionReadService.Snapshot(context, commit, List.of(edge)));
    }
    @Test void exactCanonicalMembershipAndAcceptedRelationsAreEvidence() throws Exception {
        fixture("accepted");
        var result = service.analyze(context, commit, "service-support", "1.0.0", EvidenceCompleteness.COMPLETE, "Fixture");
        assertThat(result.getJudgments()).singleElement().extracting(ObligationJudgment::status).isEqualTo(ObligationJudgment.Status.SATISFIED);
        assertThat(result.getCommitId()).isEqualTo(commit);
        assertThat(result.getRepositoryId()).isEqualTo("repo");
        assertThat(result.getEvidenceAuthority()).isEqualTo("CANONICAL_SNAPSHOT");
        verify(read).verifyExpectedHead(commit); verifyNoInteractions(legacy);
    }
    @Test void highConfidenceProposedRelationDoesNotSatisfyObligation() throws Exception {
        fixture("proposed");
        assertThat(service.analyze(context, commit, "service-support", "1.0.0", EvidenceCompleteness.COMPLETE, "Fixture").getJudgments())
                .singleElement().extracting(ObligationJudgment::status).isEqualTo(ObligationJudgment.Status.VIOLATED);
    }
    @Test void incompleteEvidenceNeverBecomesAnAbsenceFinding() throws Exception {
        fixture("proposed");
        assertThat(service.analyze(context, commit, "service-support", "1.0.0", EvidenceCompleteness.INCOMPLETE, "Fixture").getJudgments())
                .singleElement().extracting(ObligationJudgment::status).isEqualTo(ObligationJudgment.Status.INCONCLUSIVE);
    }
    @Test void headMovementFailsClosed() throws Exception {
        fixture("accepted");
        doThrow(new java.io.IOException("head moved")).when(read).verifyExpectedHead(commit);
        assertThatThrownBy(() -> service.analyze(context, commit, "service-support", "1.0.0", EvidenceCompleteness.COMPLETE, "Fixture"))
                .isInstanceOf(java.io.IOException.class).hasMessage("head moved");
    }
    @Test void unresolvedTargetDowngradesRequestedCompleteEvidence() throws Exception {
        fixture("accepted");
        when(read.dslAtCommit(commit)).thenReturn(Optional.of("""
                element service type CoreService {
                  title: "Service";
                }
                """));
        var result = service.analyze(context, commit, "service-support", "1.0.0", EvidenceCompleteness.COMPLETE, "Fixture");
        assertThat(result.getEvidenceCompleteness()).isEqualTo(EvidenceCompleteness.INCOMPLETE);
        assertThat(result.getJudgments()).singleElement().extracting(ObligationJudgment::status).isEqualTo(ObligationJudgment.Status.INCONCLUSIVE);
    }
    @Test void absentApqcProvenanceIsNotNegativeCoverage() throws Exception {
        fixture("accepted");
        var result = service.analyzeApqcCoverage(context, commit);
        assertThat(result.state()).isEqualTo("INCONCLUSIVE");
        assertThat(result.linkedPercent()).isNull();
        assertThat(result.observedElements()).isZero();
        verifyNoInteractions(legacy);
    }
    @Test void explicitApqcProvenanceCountsOnlyAcceptedScopedLinks() throws Exception {
        fixture("accepted");
        when(read.dslAtCommit(commit)).thenReturn(Optional.of("""
                element service type CoreService {
                  title: "Service";
                  x-source-framework: "apqc";
                  x-apqc-level: "Process";
                }
                element role type BusinessRole {
                  title: "Role";
                }
                """));
        var result = service.analyzeApqcCoverage(context, commit);
        assertThat(result.state()).isEqualTo("OBSERVED");
        assertThat(result.observedElements()).isEqualTo(1);
        assertThat(result.linkedElements()).isEqualTo(1);
        assertThat(result.observedByLevel()).containsEntry("Process", 1);
    }
    @Test void unknownProfileFailsBeforeAnyEvidenceRetrieval() {
        assertThatThrownBy(() -> service.analyze(context, commit, "missing", "1.0.0", EvidenceCompleteness.COMPLETE, "Fixture"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(projections, dsl, legacy);
    }
}
