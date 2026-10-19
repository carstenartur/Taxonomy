package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.PatternDetectionView;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Clob;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Full evidence and cleanup boundaries independent of driver-specific CLOB behavior. */
class PortfolioSnapshotEvidenceReaderTest {
    private static final String[] PAYLOADS = {
            """
            {"rawScores":{"BP":65},"tree":[{"code":"BP","nameEn":"Historical root",
             "descriptionEn":"Complete description","uuid":"historical-uuid","children":[]}],
             "reasons":{"BP":"Recorded reason"},"warnings":["Recorded warning"],"provider":"Historical provider",
             "status":"PARTIAL","errorMessage":"Recorded partial result","analysisDurationMillis":42,
             "analysisOperationId":"historical-operation","analysisProviderId":"MOCK"}
            """,
            "{\"businessText\":\"Original requirement\",\"totalAnchors\":2,\"totalGaps\":1,\"notes\":[\"Gap evidence\"]}",
            "{\"nodeCode\":\"BP\",\"patternCoverage\":0.75,\"notes\":[\"Pattern evidence\"]}",
            "{\"businessText\":\"Original requirement\",\"confidence\":0.6,\"reasoning\":[\"Reasoning\"],\"notes\":[\"Notes\"]}"
    };

    @Test void readsEveryFieldBeforeClosingReadersAndAllLocators() throws Exception {
        var f = new Fixture();
        var value = f.reader.read(7L, "snapshot", "scope").orElseThrow();
        Object[] actual = {value.analysis(), value.gapAnalysis(), value.patternDetection(), value.recommendation()};
        Class<?>[] types = {AnalysisResult.class, GapAnalysisView.class, PatternDetectionView.class, ArchitectureRecommendation.class};
        var json = JsonMapper.builder().build();
        for (int i = 0; i < actual.length; i++) {
            tools.jackson.databind.JsonNode actualFields = json.valueToTree(actual[i]);
            tools.jackson.databind.JsonNode originalFields = json.valueToTree(f.codec.read(PAYLOADS[i], types[i]));
            assertThat(actualFields).isEqualTo(originalFields);
            assertThat(f.inputs[i].closed).isEqualTo(1);
            assertThat(f.inputs[i].position).isEqualTo(PAYLOADS[i].length());
            verify(f.clobs[i]).free();
        }
        verify(f.query).addScalar("analysis_payload", StandardBasicTypes.CLOB);
        verify(f.query).addScalar("gap_payload", StandardBasicTypes.CLOB);
        verify(f.query).addScalar("pattern_payload", StandardBasicTypes.CLOB);
        verify(f.query).addScalar("recommendation_payload", StandardBasicTypes.CLOB);
        verify(f.query).setParameter("id", "snapshot");
        verify(f.query).setParameter("projectId", 7L);
        verify(f.query).setParameter("scopeKey", "scope");
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void failedPayloadParsingClosesItsReaderAndFreesEvenUnopenedLocators(int failed) throws Exception {
        var f = new Fixture();
        f.inputs[failed] = new Input("{");
        var readerClose = new IOException("reader close");
        var clobFree = new SQLException("clob free");
        f.inputs[failed].closeFailure = readerClose;
        when(f.clobs[failed].getCharacterStream()).thenReturn(f.inputs[failed]);
        doThrow(clobFree).when(f.clobs[3]).free();
        assertThatThrownBy(() -> f.reader.read(7L, "snapshot", "scope"))
                .isInstanceOfSatisfying(PortfolioException.class, failure -> {
                    assertThat(failure.getMessage()).contains("deserialize portfolio payload");
                    assertThat(failure.getSuppressed()).containsExactly(readerClose, clobFree);
                });
        for (int i = 0; i < 4; i++) {
            assertThat(f.inputs[i].closed).isEqualTo(i <= failed ? 1 : 0);
            if (i > failed) verify(f.clobs[i], never()).getCharacterStream();
            verify(f.clobs[i]).free();
        }
    }

    @Test void streamOpenFailureStillFreesEveryAcquiredLocator() throws Exception {
        var f = new Fixture();
        var opening = new SQLException("stream open");
        var freeing = new SQLException("clob free");
        when(f.clobs[0].getCharacterStream()).thenThrow(opening);
        doThrow(freeing).when(f.clobs[3]).free();
        assertThatThrownBy(() -> f.reader.read(7L, "snapshot", "scope"))
                .isInstanceOfSatisfying(PortfolioException.class, failure -> {
                    assertThat(failure.getCause()).isSameAs(opening);
                    assertThat(opening.getSuppressed()).containsExactly(freeing);
                });
        for (int i = 0; i < 4; i++) {
            assertThat(f.inputs[i].closed).isZero();
            verify(f.clobs[i]).free();
        }
    }

    @Test void successfulParseDoesNotHideReaderOrLocatorCleanupFailures() throws Exception {
        var f = new Fixture();
        var readerClose = new IOException("reader close");
        var clobFree = new SQLException("clob free");
        f.inputs[0].closeFailure = readerClose;
        doThrow(clobFree).when(f.clobs[3]).free();
        assertThatThrownBy(() -> f.reader.read(7L, "snapshot", "scope"))
                .isInstanceOfSatisfying(PortfolioException.class, failure -> {
                    assertThat(failure.getCause()).isSameAs(readerClose);
                    assertThat(readerClose.getSuppressed()).containsExactly(clobFree);
                });
        assertThat(f.inputs[0].closed).isEqualTo(1);
        for (Clob clob : f.clobs) verify(clob).free();
        for (int i = 1; i < 4; i++) verify(f.clobs[i], never()).getCharacterStream();
    }

    @Test void existingNullPayloadsDifferFromAnAbsentSnapshot() throws Exception {
        var f = new Fixture();
        when(f.query.getSingleResultOrNull()).thenReturn(new Object[] {null, null, null, null});
        var value = f.reader.read(7L, "snapshot", "scope").orElseThrow();
        assertThat(value.analysis()).isNull(); assertThat(value.gapAnalysis()).isNull();
        assertThat(value.patternDetection()).isNull(); assertThat(value.recommendation()).isNull();
        when(f.query.getSingleResultOrNull()).thenReturn(null);
        assertThat(f.reader.read(7L, "snapshot", "scope")).isEmpty();
        for (Clob clob : f.clobs) verifyNoInteractions(clob);
    }

    @Test void continuationAnalysisBindsTheCompleteOwnershipAndPreservesFullEvidence() throws Exception {
        var f = new Fixture();
        when(f.query.getSingleResultOrNull()).thenReturn(new Object[] {"snapshot", f.clobs[0]});
        var value = f.reader.readAnalysis(7L, "snapshot", "scope", "job", 11L, 12L).orElseThrow();
        assertThat(value.analysis().getReasons()).containsEntry("BP", "Recorded reason");
        assertThat(value.analysis().getTree().getFirst().getDescriptionEn()).isEqualTo("Complete description");
        verify(f.query).setParameter("id", "snapshot"); verify(f.query).setParameter("projectId", 7L);
        verify(f.query).setParameter("scopeKey", "scope"); verify(f.query).setParameter("jobId", "job");
        verify(f.query).setParameter("requirementId", 11L); verify(f.query).setParameter("requirementVersionId", 12L);
        verify(f.query).addScalar("analysis_payload", StandardBasicTypes.CLOB);
        assertThat(f.inputs[0].closed).isEqualTo(1); verify(f.clobs[0]).free();
        for (int i = 1; i < 4; i++) verifyNoInteractions(f.clobs[i]);
    }

    @Test void continuationNullAnalysisStillRetainsAnExistingRow() throws Exception {
        var f = new Fixture();
        when(f.query.getSingleResultOrNull()).thenReturn(new Object[] {"snapshot", null});
        assertThat(f.reader.readAnalysis(7L, "snapshot", "scope", "job", 11L, 12L).orElseThrow().analysis()).isNull();
        when(f.query.getSingleResultOrNull()).thenReturn(null);
        assertThat(f.reader.readAnalysis(7L, "snapshot", "scope", "job", 11L, 12L)).isEmpty();
        for (Clob clob : f.clobs) verifyNoInteractions(clob);
    }

    private static final class Fixture {
        final EntityManager entityManager = mock(EntityManager.class);
        final Session session = mock(Session.class);
        @SuppressWarnings("unchecked") final NativeQuery<Object[]> query = mock(NativeQuery.class, Answers.RETURNS_SELF);
        final Clob[] clobs = new Clob[4];
        final Input[] inputs = new Input[4];
        final PortfolioJsonCodec codec = new PortfolioJsonCodec(JsonMapper.builder().build());
        final PortfolioSnapshotEvidenceReader reader = new PortfolioSnapshotEvidenceReader(entityManager, codec);
        Fixture() throws SQLException {
            when(entityManager.unwrap(Session.class)).thenReturn(session);
            when(session.createNativeQuery(anyString(), eq(Object[].class))).thenReturn(query);
            for (int i = 0; i < 4; i++) {
                clobs[i] = mock(Clob.class); inputs[i] = new Input(PAYLOADS[i]);
                when(clobs[i].getCharacterStream()).thenReturn(inputs[i]);
            }
            when(query.getSingleResultOrNull()).thenReturn(clobs.clone());
        }
    }

    private static final class Input extends Reader {
        final StringReader delegate;
        int position, closed;
        IOException closeFailure;
        Input(String json) { delegate = new StringReader(json); }
        @Override public int read(char[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, Math.min(length, 3));
            if (count > 0) position += count;
            return count;
        }
        @Override public void close() throws IOException {
            closed++; delegate.close();
            if (closeFailure != null) throw closeFailure;
        }
    }
}
