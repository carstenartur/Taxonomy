package com.taxonomy.relations.service;

import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static com.taxonomy.relations.service.HypothesisSourceReadService.State.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HypothesisSourceReadServiceTest {
    private static final String HEAD = "a".repeat(40);
    private final RepositoryContext scope = RepositoryContext.workspace("repo", "mine", "draft", "reader");
    private final WorkspaceDslReadPort reads = mock();
    private final WorkspaceDslReadPort.RepositoryRead git = mock();
    private final HypothesisService hypotheses = mock();
    private final HypothesisSourceReadService service = new HypothesisSourceReadService(reads, hypotheses);

    @BeforeEach void selectedScope() throws IOException {
        when(reads.openRead(scope)).thenReturn(git);
        when(git.currentHead()).thenReturn(HEAD);
    }

    @Test void legacySessionResolvesOnlyOneVisibleCandidateAndUsesTheImmutableStatus() throws IOException {
        String dsl = """
                relation A RELATED_TO B {
                 status: provisional;
                 provenance: "analysis-session:session";
                }
                """;
        when(git.dslAtCommit(HEAD)).thenReturn(Optional.of(dsl));
        var row = row(11L, "session");
        when(hypotheses.findAll(scope)).thenReturn(List.of(row, row(12L, "other")));
        var ready = service.read(scope, HEAD, null);
        assertThat(ready.state()).isEqualTo(READY);
        assertThat(ready.rows()).containsExactly(new HypothesisSourceReadService.Item(11L, "A", RelationType.RELATED_TO,
                "B", HypothesisStatus.PROVISIONAL, null));
        verify(git).currentHead();
        verify(git).dslAtCommit(HEAD);
        when(hypotheses.findAll(scope)).thenReturn(List.of(row, row(12L, "session")));
        var ambiguous = service.read(scope, HEAD, HypothesisStatus.REJECTED);
        assertThat(ambiguous.state()).isEqualTo(STALE);
        assertThat(ambiguous.problem()).isEqualTo(HypothesisSourceReadService.Problem.BINDING_AMBIGUOUS);
        assertThat(ambiguous.rows()).isEmpty();
        assertThat(ambiguous.entityTag()).isNull();
        when(hypotheses.findAll(scope)).thenReturn(List.of(row(19L, "session")));
        assertThat(service.read(scope, HEAD, null).entityTag()).isNotEqualTo(ready.entityTag());
    }

    @Test void missingLegacySessionCannotMatchAStoredStringSpellingOfNull() throws IOException {
        when(git.dslAtCommit(HEAD)).thenReturn(Optional.of("""
                relation A RELATED_TO B {
                 status: proposed;
                 provenance: "analysis-session:null";
                }
                """));
        when(hypotheses.findAll(scope)).thenReturn(List.of(row(8L, null)));
        assertThat(service.read(scope, HEAD, null).state()).isEqualTo(MISSING);
    }

    @Test void missingRevisionAndReadFailureCannotMasqueradeAsAnEmptyRevision() throws IOException {
        when(git.currentHead()).thenReturn(null);
        assertThat(service.read(scope, null, null).state()).isEqualTo(MISSING);
        when(git.currentHead()).thenReturn(HEAD);
        assertThat(service.read(scope, "b".repeat(40), null).state()).isEqualTo(STALE);
        verifyNoInteractions(hypotheses);
        when(git.dslAtCommit(HEAD)).thenReturn(Optional.empty(), Optional.of(""), Optional.of("""
                # ordinary relations do not require hypothesis bindings
                relation A RELATED_TO B {
                 status: accepted;
                }
                """)).thenThrow(new IOException("private storage detail"));
        assertThat(service.read(scope, HEAD, null).state()).isEqualTo(MISSING);
        assertThat(service.read(scope, HEAD, null).state()).isEqualTo(READY);
        assertThat(service.read(scope, HEAD, null).rows()).isEmpty();
        assertThat(service.read(scope, HEAD, null).state()).isEqualTo(FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown text", "}", "relation A RELATED_TO B {", "relation A RELATED_TO B {\nstatus: provisional;\nstatus: rejected;\n}",
            "relation A RELATED_TO B {\nstatus: provisional;\nx-hypothesis-id: 1;\nconfidence: NaN;\n}",
            "relation A RELATED_TO B {\nstatus: provisional;\nx-hypothesis-id: 1;\nconfidence: -1;\n}",
            "relation A RELATED_TO B {\nstatus: provisional;\nx-hypothesis-id: 1;\nconfidence: 2;\n}",
            "relation A RELATED_TO B {\nx-hypothesis-id: 1;\n}", "relation A {\nstatus: provisional;\n}",
            "relation A RELATED_TO B {\nstatus: accepted;\n}\nrelation A RELATED_TO B {\nstatus: rejected;\n}"})
    void incompleteAndContradictorySourcesNeverReportReady(String dsl) throws IOException {
        when(git.dslAtCommit(HEAD)).thenReturn(Optional.of(dsl));
        when(hypotheses.findAll(scope)).thenReturn(List.of(row(1L, "session")));
        assertThat(service.read(scope, HEAD, null).state()).isEqualTo(FAILED);
    }

    private static RelationHypothesis row(long id, String session) {
        var row = new RelationHypothesis();
        row.setId(id);
        row.setSourceNodeId("A");
        row.setTargetNodeId("B");
        row.setRelationType(RelationType.RELATED_TO);
        row.setStatus(HypothesisStatus.REJECTED);
        row.setAnalysisSessionId(session);
        return row;
    }
}
