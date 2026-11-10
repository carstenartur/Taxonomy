package com.taxonomy.architecture.service;

import com.taxonomy.dto.*;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.relations.service.RelationProjectionReadService.Snapshot;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GraphDslEvidenceTest {
    private final String commit = "a".repeat(40);
    private final RepositoryContext context = RepositoryContext.centralRead("fixture", "main", "alice");
    private final WorkspaceDslReadPort reads = mock(WorkspaceDslReadPort.class);
    private final WorkspaceDslReadPort.RepositoryRead read = mock(WorkspaceDslReadPort.RepositoryRead.class);

    @Test void apqcComesFromExactSelectedCommit() throws Exception {
        when(reads.openRead(context)).thenReturn(read);
        when(read.dslAtCommit(commit)).thenReturn(Optional.of("""
                element BP type Process {
                  title: "Authored APQC fixture";
                  x-source-framework: "apqc";
                  x-apqc-level: "Category";
                }
                """));
        var relations = mock(RelationProjectionReadService.class);
        when(relations.readSnapshot(context, commit)).thenReturn(new Snapshot(context, commit, List.of()));
        var result = new ApqcHierarchyService(new GraphSnapshotReadService(relations, reads)).buildHierarchy(context, commit);
        assertThat(result.nodes()).hasSize(1);
        assertThat(result.evidence().commitId()).isEqualTo(commit);
        verify(read).dslAtCommit(commit);
        verify(read).verifyExpectedHead(commit);
    }

    @Test void correlationUsesCommitMappingsAndMissingEvidenceStaysInconclusive() throws Exception {
        when(reads.openRead(context)).thenReturn(read);
        var graph = mock(ArchitectureGraphQueryService.class);
        var base = new ChangeImpactView();
        var element = new ImpactElement(); element.setNodeCode("CP"); element.setRelevance(.8);
        base.setDirectlyAffected(List.of(element));
        base.setEvidence(ArchitectureGraphQueryServiceImpl.evidence(new Snapshot(context,commit,List.of()),2,2,"fixture"));
        when(graph.findFailureImpact(any(GraphSnapshotReadService.SelectedGraphSnapshot.class),eq("BP"),eq(2))).thenReturn(base);
        var relations = mock(RelationProjectionReadService.class);
        when(relations.readSnapshot(context,commit)).thenReturn(new Snapshot(context,commit,List.of()));
        when(read.dslAtCommit(commit)).thenReturn(Optional.of("""
                requirement REQ-FIXTURE {
                  title: "Authored requirement fixture";
                }
                mapping REQ-FIXTURE -> CP {
                  source: "authored fixture";
                }
                """));
        var service = new EnrichedImpactService(graph, new GraphSnapshotReadService(relations, reads));
        var mapped = service.findEnrichedFailureImpact(context,commit,"BP",2);
        assertThat(mapped.getAffectedRequirements()).containsExactly("REQ-FIXTURE");
        assertThat(mapped.getRiskScore()).isEqualTo(.8);
        assertThat(mapped.getCorrelationCompleteness()).isEqualTo("RECORDED_MAPPINGS_ONLY");
        assertThat(mapped.getUncorrelatedElementIds()).isEmpty();
        when(read.dslAtCommit(commit)).thenReturn(Optional.of(""));
        var missing = service.findEnrichedFailureImpact(context,commit,"BP",2);
        assertThat(missing.getRiskScore()).isNull();
        assertThat(missing.getCorrelationCompleteness()).isEqualTo("INCONCLUSIVE");
        assertThat(missing.getUncorrelatedElementIds()).containsExactly("CP");

        when(read.dslAtCommit(commit)).thenReturn(Optional.of("""
                requirement REQ-UNRELATED {
                  title: "Unrelated requirement";
                }
                mapping REQ-UNRELATED -> unrelated {
                  source: "authored fixture";
                }
                """));
        var unrelated = service.findEnrichedFailureImpact(context,commit,"BP",2);
        assertThat(unrelated.getRiskScore()).as("An unrelated mapping cannot establish zero risk for affected CP").isNull();
        assertThat(unrelated.getCorrelationCompleteness()).isEqualTo("INCONCLUSIVE");
        assertThat(unrelated.getUncorrelatedElementIds()).containsExactly("CP");
    }

    @Test void partialAffectedElementCorrelationCannotBeReportedAsCompleteRisk() throws Exception {
        when(reads.openRead(context)).thenReturn(read);
        var graph = mock(ArchitectureGraphQueryService.class);
        var base = new ChangeImpactView();
        var mapped = new ImpactElement(); mapped.setNodeCode("CP-mapped"); mapped.setRelevance(.8);
        var unknown = new ImpactElement(); unknown.setNodeCode("CP-unknown"); unknown.setRelevance(.7);
        base.setDirectlyAffected(List.of(mapped, unknown));
        base.setEvidence(ArchitectureGraphQueryServiceImpl.evidence(new Snapshot(context,commit,List.of()),2,2,"fixture"));
        when(graph.findFailureImpact(any(GraphSnapshotReadService.SelectedGraphSnapshot.class),eq("BP"),eq(2))).thenReturn(base);
        var relations = mock(RelationProjectionReadService.class);
        when(relations.readSnapshot(context,commit)).thenReturn(new Snapshot(context,commit,List.of()));
        when(read.dslAtCommit(commit)).thenReturn(Optional.of("""
                requirement REQ-FIXTURE {
                  title: "Authored requirement";
                }
                mapping REQ-FIXTURE -> CP-mapped {
                  source: "authored fixture";
                }
                """));
        var result = new EnrichedImpactService(graph,new GraphSnapshotReadService(relations,reads))
                .findEnrichedFailureImpact(context,commit,"BP",2);
        assertThat(result.getAffectedRequirements()).containsExactly("REQ-FIXTURE");
        assertThat(result.getCorrelationCompleteness()).isEqualTo("PARTIAL_RECORDED_MAPPINGS");
        assertThat(result.getUncorrelatedElementIds()).containsExactly("CP-unknown");
        assertThat(result.getRiskScore()).isNull();

        when(read.dslAtCommit(commit)).thenReturn(Optional.of("""
                requirement REQ-FIXTURE {
                  title: "Authored requirement";
                }
                mapping REQ-FIXTURE -> CP-mapped {
                  source: "authored fixture";
                }
                mapping REQ-FIXTURE -> CP-unknown {
                  source: "authored fixture";
                }
                """));
        var fullyMapped = new EnrichedImpactService(graph,new GraphSnapshotReadService(relations,reads))
                .findEnrichedFailureImpact(context,commit,"BP",2);
        assertThat(fullyMapped.getCorrelationCompleteness()).isEqualTo("RECORDED_MAPPINGS_ONLY");
        assertThat(fullyMapped.getUncorrelatedElementIds()).isEmpty();
        assertThat(fullyMapped.getRiskScore()).isEqualTo(1.5);
    }
}
