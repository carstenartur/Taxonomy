package com.taxonomy.architecture.service;

import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.relations.service.RelationProjectionReadService.Snapshot;
import com.taxonomy.relations.service.RelationProjectionReadService.SnapshotRelation;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Authored graph fixtures, independent of catalogue/model inference. */
class GraphSnapshotQueryTest {
    private static final String COMMIT = "a".repeat(40);
    private final RelationProjectionReadService relations = mock(RelationProjectionReadService.class);
    private final WorkspaceDslReadPort reads = mock(WorkspaceDslReadPort.class);
    private final WorkspaceDslReadPort.RepositoryRead read = mock(WorkspaceDslReadPort.RepositoryRead.class);
    private final ArchitectureGraphQueryServiceImpl service = new ArchitectureGraphQueryServiceImpl(new GraphSnapshotReadService(relations, reads));

    private RepositoryContext context(String branch) throws Exception {
        var context = RepositoryContext.centralRead("fixture", branch, "alice");
        when(reads.openRead(context)).thenReturn(read);
        when(read.verifyExpectedHead(COMMIT)).thenReturn(COMMIT);
        when(read.dslAtCommit(COMMIT)).thenReturn(Optional.of(""));
        return context;
    }
    private SnapshotRelation edge(long id, String from, String to) {
        return new SnapshotRelation(id, from, to, RelationType.SUPPORTS, "accepted", .9,
                "authored fixture", COMMIT, "decision-" + id);
    }
    @Test void branchesAndEvidenceRemainSeparate() throws Exception {
        var main = context("main"); var alternative = context("alternative");
        when(relations.readSnapshot(main, COMMIT)).thenReturn(new Snapshot(main, COMMIT, List.of(edge(1,"A","B"))));
        when(relations.readSnapshot(alternative, COMMIT)).thenReturn(new Snapshot(alternative, COMMIT, List.of(edge(2,"A","C"))));
        var first = service.findDownstream(main, COMMIT, "A", 2);
        var second = service.findDownstream(alternative, COMMIT, "A", 2);
        assertThat(first.getNeighbors()).extracting("nodeCode").containsExactly("B");
        assertThat(second.getNeighbors()).extracting("nodeCode").containsExactly("C");
        assertThat(first.getEvidence().commitId()).isEqualTo(COMMIT);
        assertThat(first.getTraversedRelationships().getFirst().getEvidence().causationId()).isEqualTo("decision-1");
        verify(relations).readSnapshot(main, COMMIT);
    }
    @Test void hopAndScoreBoundsAndScopedEmptyAreExplicit() throws Exception {
        var context = context("main");
        when(relations.readSnapshot(context, COMMIT)).thenReturn(new Snapshot(context, COMMIT,
                List.of(edge(1,"A","B"),edge(2,"B","C"),edge(3,"C","D"),edge(4,"D","E"))));
        var bounded = service.findDownstream(context, COMMIT, "A", 1);
        assertThat(bounded.getNeighbors()).extracting("nodeCode").containsExactly("B");
        assertThat(bounded.getEvidence().effectiveMaxHops()).isEqualTo(1);
        var scoreBounded = service.findDownstream(context, COMMIT, "A", 5);
        assertThat(scoreBounded.getNeighbors()).extracting("nodeCode").containsExactly("B", "C", "D");
        var empty = service.findDownstream(context, COMMIT, "E", 99);
        assertThat(empty.getNeighbors()).isEmpty();
        assertThat(empty.getEvidence().effectiveMaxHops()).isEqualTo(5);
        assertThat(empty.getEvidence().absenceScope()).contains("selected");
        assertThat(empty.getEvidence().minimumRelevance()).isEqualTo(.2);
    }
    @Test void sameHopAnchorUpdatesCannotSmuggleAnExtraHop() throws Exception {
        var context = context("main");
        when(relations.readSnapshot(context, COMMIT)).thenReturn(new Snapshot(context, COMMIT,
                List.of(edge(1,"A","B"),edge(2,"B","C"))));
        var result = service.findImpactForRequirement(context, COMMIT, Map.of("A",90d,"B",50d), "fixture", 1);
        assertThat(result.getImpactedElements()).filteredOn(element -> element.getNodeCode().equals("C"))
                .singleElement().satisfies(element -> assertThat(element.getRelevance()).isEqualTo(.4));
    }

    @Test void absentScoresAreNotACompletedImpactFinding() throws Exception {
        var context = context("main");
        when(relations.readSnapshot(context, COMMIT)).thenReturn(new Snapshot(context, COMMIT, List.of()));
        var result = service.findImpactForRequirement(context, COMMIT, Map.of(), "fixture", 2);
        assertThat(result.getEvidence().completeness()).isEqualTo("NOT_EVALUATED_NO_ANCHORS");
    }

    @Test void selectedLabelsAndMembershipDoNotReadTheLiveCatalogue() throws Exception {
        var context = context("main");
        when(relations.readSnapshot(context, COMMIT)).thenReturn(new Snapshot(context, COMMIT, List.of(edge(1,"A","B"))));
        when(read.dslAtCommit(COMMIT)).thenReturn(Optional.of("""
                element B type Capability {
                  title: "Exact committed label";
                }
                """));
        var result = service.findDownstream(context, COMMIT, "A", 1);
        assertThat(result.getNeighbors()).singleElement().satisfies(element -> {
            assertThat(element.getTitle()).isEqualTo("Exact committed label");
            assertThat(element.getNodeEvidence().presentInSelectedModel()).isTrue();
            assertThat(element.getNodeEvidence().commitId()).isEqualTo(COMMIT);
        });
    }

    @Test void movingHeadFailsBeforeAnswer() throws Exception {
        var context = context("main");
        when(relations.readSnapshot(context, COMMIT)).thenReturn(new Snapshot(context, COMMIT, List.of()));
        when(read.verifyExpectedHead(COMMIT)).thenThrow(new com.taxonomy.workspace.service.BranchHeadConflictException("main", COMMIT, "b".repeat(40), "head moved"));
        assertThatThrownBy(() -> service.findDownstream(context, COMMIT, "A", 2)).isInstanceOf(IllegalStateException.class);
    }
}
