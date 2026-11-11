package com.taxonomy.architecture.service;

import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.relations.service.RelationProjectionReadService.Snapshot;
import com.taxonomy.relations.service.RelationProjectionReadService.SnapshotRelation;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PatternSnapshotServiceTest {
    private static final String COMMIT = "a".repeat(40);
    private final RelationProjectionReadService projections = mock(RelationProjectionReadService.class);
    private final WorkspaceDslReadPort reads = mock(WorkspaceDslReadPort.class);
    private final ArchitecturePatternService service = new ArchitecturePatternService(new GraphSnapshotReadService(projections, reads));
    private static String nodes(String serviceType) {
        return "element A type Capability {\n title: \"Anchor\";\n}\n"
                + "element S type " + serviceType + " {\n title: \"Service\";\n}\n"
                + "element P type Process {\n title: \"Process\";\n}\n"
                + "element I type InformationProduct {\n title: \"Information\";\n}\n";
    }
    private static SnapshotRelation edge(String from, String to, RelationType type, String status) {
        return new SnapshotRelation(1L,from,to,type,status,.8,"authored fixture",COMMIT,"decision");
    }
    private static List<SnapshotRelation> chain() {
        return List.of(edge("A","S",RelationType.REALIZES,"accepted"),edge("S","P",RelationType.SUPPORTS,"accepted"),
                edge("P","I",RelationType.CONSUMES,"accepted"));
    }
    private WorkspaceDslReadPort.RepositoryRead selected(RepositoryContext context, String dsl, List<SnapshotRelation> edges) throws Exception {
        var read = mock(WorkspaceDslReadPort.RepositoryRead.class);
        when(projections.readSnapshot(context,COMMIT)).thenReturn(new Snapshot(context,COMMIT,edges));
        when(reads.openRead(context)).thenReturn(read);
        when(read.dslAtCommit(COMMIT)).thenReturn(Optional.of(dsl));
        when(read.verifyExpectedHead(COMMIT)).thenReturn(COMMIT);
        return read;
    }
    @Test void repositoriesAndBranchesUseOneFrozenProofEach() throws Exception {
        var first=RepositoryContext.centralRead("one","main","user");
        var second=RepositoryContext.centralRead("two","main","user");
        var third=RepositoryContext.centralRead("one","alternative","user");
        var thirdRead=selected(third,nodes("CoreService"),List.of());
        var firstRead=selected(first,nodes("CoreService"),chain());
        var secondRead=selected(second,nodes("CoreService"),List.of(chain().getFirst()));
        var full=service.detectForScores(first,COMMIT,Map.of("A",90d),50);
        var partial=service.detectForNode(second,COMMIT,"A");
        assertThat(full.getMatchedPatterns()).extracting("patternName").containsExactly("Full Stack");
        assertThat(full.getPatternCoverage()).isEqualTo(100d);
        assertThat(full.getEvidence().repositoryId()).isEqualTo("one");
        assertThat(full.getEvidence().evidenceAuthority()).isEqualTo("CANONICAL_SNAPSHOT");
        assertThat(full.getEvidence().templateVersion()).isEqualTo("1.0.0");
        assertThat(partial.getMatchedPatterns()).isEmpty();
        assertThat(partial.getIncompletePatterns()).singleElement().satisfies(p->assertThat(p.getPresentSteps()).containsExactly("A REALIZES S"));
        assertThat(partial.getEvidence().repositoryId()).isEqualTo("two");
        assertThat(service.detectForNode(third,COMMIT,"A").getMatchedPatterns()).isEmpty();
        verify(projections).readSnapshot(third,COMMIT);
        verify(thirdRead).verifyExpectedHead(COMMIT);
        verify(projections).readSnapshot(first,COMMIT);verify(projections).readSnapshot(second,COMMIT);
        verify(firstRead).dslAtCommit(COMMIT);verify(secondRead).dslAtCommit(COMMIT);
        verify(firstRead).verifyExpectedHead(COMMIT);verify(secondRead).verifyExpectedHead(COMMIT);
        verifyNoMoreInteractions(projections);
    }
    @Test void canonicalTypeOverridesIdentifierAndLiveCatalogueAssumptions() throws Exception {
        var context=RepositoryContext.centralRead("one","main","user");
        selected(context,nodes("Capability"),chain());
        var result=service.detectForNode(context,COMMIT,"A");
        assertThat(result.getMatchedPatterns()).isEmpty();
        assertThat(result.getIncompletePatterns()).singleElement().satisfies(p->assertThat(p.getPresentSteps()).isEmpty());
        assertThat(result.getPatternCoverage()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"proposed","rejected",""})
    void onlyExplicitAcceptedEdgesProvePatterns(String status) throws Exception {
        var context=RepositoryContext.centralRead("one","main","user");
        selected(context,nodes("CoreService"),List.of(edge("A","S",RelationType.REALIZES,status),chain().get(1),chain().get(2)));
        var result=service.detectForNode(context,COMMIT,"A");
        assertThat(result.getMatchedPatterns()).isEmpty();
        assertThat(result.getIncompletePatterns()).singleElement().satisfies(p->assertThat(p.getPresentSteps()).isEmpty());
    }
    @Test void unresolvedAcceptedEndpointDowngradesCanonicalEvidence() throws Exception {
        var context=RepositoryContext.centralRead("one","main","user");
        selected(context,nodes("CoreService"),List.of(edge("A","missing",RelationType.REALIZES,"accepted")));
        var result=service.detectForNode(context,COMMIT,"A");
        assertThat(result.getMatchedPatterns()).isEmpty();
        assertThat(result.getEvidence().completeness()).isEqualTo("INCOMPLETE_CANONICAL_EVIDENCE");
    }
    @Test void aMissingMiddleCannotReuseThePreviousFrontierForLaterSteps() throws Exception {
        var context=RepositoryContext.centralRead("one","main","user");
        selected(context,nodes("CoreService"),List.of(chain().getFirst(),edge("S","I",RelationType.CONSUMES,"accepted")));
        var pattern=service.detectForNode(context,COMMIT,"A").getIncompletePatterns().getFirst();
        assertThat(pattern.getPresentSteps()).containsExactly("A REALIZES S");
        assertThat(pattern.getMissingSteps()).hasSize(2);
        assertThat(pattern.getCompleteness()).isCloseTo(100d/3,within(.001));
    }
    @Test void noEligibleAnchorsIsUnassessedAndStillVerifiesTheSelectedHead() throws Exception {
        var context=RepositoryContext.centralRead("one","main","user");
        var read=selected(context,nodes("CoreService"),chain());
        for(var scores:List.of(Map.<String,Double>of(),Map.of("A",49d),Map.of("A",Double.NaN),Map.of("I",90d))) {
            var result=service.detectForScores(context,COMMIT,scores,50);
            assertThat(result.getPatternCoverage()).isNull();
            assertThat(result.getEvidence().eligibleAnchors()).isZero();
            assertThat(result.getEvidence().completeness()).isEqualTo("NOT_EVALUATED_NO_ANCHORS");
        }
        verify(read,times(4)).verifyExpectedHead(COMMIT);
    }
    @ParameterizedTest @ValueSource(strings={"STALE","NOT_BUILT"})
    void unavailableProjectionNeverBecomesEmptySuccess(String state) {
        var context=RepositoryContext.centralRead("one","main","user");
        when(projections.readSnapshot(context,COMMIT)).thenThrow(new IllegalStateException(state));
        assertThatThrownBy(()->service.detectForScores(context,COMMIT,Map.of(),50)).isInstanceOf(IllegalStateException.class).hasMessage(state);
        verifyNoInteractions(reads);
    }
    @Test void changedHeadRejectsTheCompletedAnswer() throws Exception {
        var context=RepositoryContext.centralRead("one","main","user");
        var read=selected(context,nodes("CoreService"),chain());
        when(read.verifyExpectedHead(COMMIT)).thenThrow(new java.io.IOException("head moved"));
        assertThatThrownBy(()->service.detectForNode(context,COMMIT,"A")).isInstanceOf(IllegalStateException.class).hasMessageContaining("head");
    }
}
