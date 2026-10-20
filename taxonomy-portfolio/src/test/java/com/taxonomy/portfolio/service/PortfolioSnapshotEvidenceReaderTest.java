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
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Answers;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Full evidence and cleanup boundaries independent of driver-specific CLOB behavior. */
class PortfolioSnapshotEvidenceReaderTest {
    private static final int RANGE_CHARACTERS = 262_144;
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

    @ParameterizedTest @ValueSource(strings = {"", "{}", "  \t\r\n"})
    void smallAndEmptyClobsKeepTheirStandardStreamWithoutRequestingRanges(String source) throws Exception {
        var f = new Fixture(); f.inputs[0] = new Input(source);
        when(f.clobs[0].length()).thenReturn((long) source.length());
        when(f.clobs[0].getCharacterStream()).thenReturn(f.inputs[0]);
        when(f.query.getSingleResultOrNull()).thenReturn(new Object[]{"snapshot", f.clobs[0]});
        var value = f.reader.readAnalysis(7L, "snapshot", "scope", "job", 11L, 12L).orElseThrow();
        if (source.isBlank()) assertThat(value.analysis()).isNull(); else assertThat(value.analysis()).isNotNull();
        verify(f.clobs[0], never()).getCharacterStream(anyLong(), anyLong());
        assertThat(f.inputs[0].closed).isEqualTo(1); verify(f.clobs[0]).free();
        for (int i = 1; i < 4; i++) verifyNoInteractions(f.clobs[i]);
    }

    enum RangePath { FULL_EVIDENCE, CONTINUATION_ANALYSIS, SELECTION }

    @ParameterizedTest @EnumSource(RangePath.class)
    void boundedRangesKeepEveryCodeUnitAndCloseEachSliceBeforeTheNext(RangePath path) throws Exception {
        var f = new RangeFixture(path);
        f.assertParity(f.read());
        assertThat(f.ranges).hasSizeGreaterThanOrEqualTo(3);
        long position = 1;
        for (var range : f.ranges) {
            assertThat(range.position).isEqualTo(position);
            assertThat(range.length).isBetween(1L, (long) RANGE_CHARACTERS);
            assertThat(range.closed).isEqualTo(1);
            assertThat(range.eofObserved).isTrue();
            position += range.length;
        }
        assertThat(position).isEqualTo(f.source.length() + 1L);
        verify(f.clob, times(1)).length();
        verify(f.clob, never()).getCharacterStream();
        f.verifyFreed();
    }

    @ParameterizedTest @EnumSource(RangePath.class)
    void unsupportedFirstRangeAloneCanFallBackToTheUnchangedStandardStream(RangePath path) throws Exception {
        var f = new RangeFixture(path);
        f.openFailure = new SQLFeatureNotSupportedException("Ranges unavailable"); f.failedOpen = 0;
        f.assertParity(f.read());
        verify(f.clob).getCharacterStream();
        assertThat(f.openAttempts).isEqualTo(1);
        assertThat(f.ranges).isEmpty();
        assertThat(f.defaultClosed).isEqualTo(1);
        f.verifyFreed();
    }

    @ParameterizedTest @EnumSource(RangePath.class)
    void ordinaryFirstSqlFailureAndLaterUnsupportedRangesNeverRestartThePayload(RangePath path) throws Exception {
        for (int failed : List.of(0, 1)) {
            var f = new RangeFixture(path); f.failedOpen = failed;
            f.openFailure = failed == 0 ? new SQLException("ordinary open failure")
                    : new SQLFeatureNotSupportedException("later unsupported range");
            assertThatThrownBy(f::read).isInstanceOf(PortfolioException.class)
                    .hasRootCauseMessage(f.openFailure.getMessage());
            verify(f.clob, never()).getCharacterStream();
            assertThat(f.openAttempts).isEqualTo(failed + 1);
            assertThat(f.ranges).allSatisfy(range -> assertThat(range.closed).isEqualTo(1));
            f.verifyFreed();
        }
    }

    @ParameterizedTest @EnumSource(RangePath.class)
    void truncatedOversizedAndFailingRangeEndsCannotPublishAnAcceptedDto(RangePath path) throws Exception {
        for (String fault : List.of("premature", "excess", "read", "eof", "close")) {
            var f = new RangeFixture(path); f.fault = fault;
            assertThatThrownBy(f::read).as("%s range fault on %s", fault, path).isInstanceOf(PortfolioException.class);
            verify(f.clob, never()).getCharacterStream();
            assertThat(f.ranges).isNotEmpty().allSatisfy(range -> assertThat(range.closed).isEqualTo(1));
            f.verifyFreed();
        }
    }

    @ParameterizedTest @EnumSource(RangePath.class)
    void aFinalRangeEofFailureIsNotHiddenByACompleteJsonRoot(RangePath path) throws Exception {
        var f = new RangeFixture(path); f.fault = "final-eof";
        assertThatThrownBy(f::read).isInstanceOf(PortfolioException.class).hasRootCauseMessage("final range EOF");
        assertThat(f.ranges).hasSizeGreaterThanOrEqualTo(3)
                .allSatisfy(range -> assertThat(range.closed).isEqualTo(1));
        f.verifyFreed();
    }

    /** Standard JDBC-only contract; no HSQL identification or driver-specific production API. */
    private static final class RangeFixture {
        final RangePath path;
        final EntityManager entityManager = mock(EntityManager.class);
        final Session session = mock(Session.class);
        @SuppressWarnings("unchecked") final NativeQuery<Object[]> query = mock(NativeQuery.class, Answers.RETURNS_SELF);
        final Clob clob = mock(Clob.class);
        final Clob[] others = {mock(Clob.class), mock(Clob.class), mock(Clob.class)};
        final PortfolioJsonCodec codec = new PortfolioJsonCodec(JsonMapper.builder().build());
        final List<Slice> ranges = new ArrayList<>();
        final String source;
        int active, openAttempts, defaultClosed, failedOpen = -1;
        SQLException openFailure;
        String fault;

        RangeFixture(RangePath path) throws Exception {
            this.path = path;
            String prefix = "{\"rawScores\":{\"BP\":12.5},\"reasons\":{\"BP\":\"";
            // The surrogate pair straddles the exact first range boundary; lone surrogates
            // also remain literal UTF-16 units. The final warning forces the whole envelope.
            source = prefix + "x".repeat(RANGE_CHARACTERS - prefix.length() - 1) + "🌊水位\uD800X\uDC00"
                    + "z".repeat(RANGE_CHARACTERS + 37) + "\"},\"warnings\":[\"retained\"],\"tree\":[]}";
            when(entityManager.unwrap(Session.class)).thenReturn(session);
            when(session.createNativeQuery(anyString(), eq(Object[].class))).thenReturn(query);
            Object[] row = switch (path) {
                case FULL_EVIDENCE -> new Object[]{clob, others[0], others[1], others[2]};
                case CONTINUATION_ANALYSIS -> new Object[]{"snapshot", clob};
                case SELECTION -> new Object[]{"snapshot", "PARTIAL", 123L, clob, 2L, 3L};
            };
            when(query.getSingleResultOrNull()).thenReturn(row);
            when(clob.length()).thenReturn((long) source.length());
            when(clob.getCharacterStream()).thenAnswer(ignored -> new StringReader(source) {
                @Override public void close() { defaultClosed++; super.close(); }
            });
            when(clob.getCharacterStream(anyLong(), anyLong())).thenAnswer(invocation -> {
                assertThat(active).as("Close the prior range before opening its successor").isZero();
                int attempt = openAttempts++;
                if (attempt == failedOpen) throw openFailure;
                var slice = new Slice(invocation.getArgument(0), invocation.getArgument(1), attempt);
                ranges.add(slice); active++; return slice;
            });
            for (Clob other : others) when(other.getCharacterStream()).thenAnswer(ignored -> new StringReader("{}"));
        }

        Object read() {
            return switch (path) {
                case FULL_EVIDENCE -> new PortfolioSnapshotEvidenceReader(entityManager, codec)
                        .read(7L, "snapshot", "scope").orElseThrow().analysis();
                case CONTINUATION_ANALYSIS -> new PortfolioSnapshotEvidenceReader(entityManager, codec)
                        .readAnalysis(7L, "snapshot", "scope", "job", 11L, 12L).orElseThrow().analysis();
                case SELECTION -> new PortfolioSnapshotSelectionReader(entityManager, codec)
                        .read(7L, "snapshot", "scope").orElseThrow();
            };
        }

        void assertParity(Object actual) {
            var expected = codec.read(source, AnalysisResult.class);
            if (path == RangePath.SELECTION) {
                var selection = (com.taxonomy.portfolio.dto.SnapshotSelection) actual;
                assertThat(selection.scores()).isEqualTo(expected.getScores());
                assertThat(selection.warningCount()).isEqualTo(expected.getWarnings().size());
            } else {
                var json = JsonMapper.builder().build();
                tools.jackson.databind.JsonNode restored = json.valueToTree(actual), original = json.valueToTree(expected);
                assertThat(restored).isEqualTo(original);
            }
        }

        void verifyFreed() throws Exception {
            verify(clob).free();
            for (Clob other : others) {
                if (path == RangePath.FULL_EVIDENCE) verify(other).free(); else verifyNoInteractions(other);
            }
            assertThat(active).isZero();
        }

        final class Slice extends Reader {
            final long position, length;
            final int index;
            int offset, closed;
            boolean eofObserved;
            Slice(long position, long length, int index) { this.position = position; this.length = length; this.index = index; }
            @Override public int read(char[] buffer, int start, int count) throws IOException {
                if (count == 0) return 0;
                if (index == 0 && "read".equals(fault)) throw new IOException("range read");
                long end = length - (index == 0 && "premature".equals(fault) ? 1 : 0);
                if (offset == end) {
                    eofObserved = true;
                    if (index == 0 && "eof".equals(fault)) throw new IOException("range EOF");
                    if (position + length - 1 == source.length() && "final-eof".equals(fault))
                        throw new IOException("final range EOF");
                    if (index == 0 && "excess".equals(fault)) { buffer[start] = 'x'; return 1; }
                    return -1;
                }
                int actual = (int) Math.min(Math.min(count, 7), end - offset);
                source.getChars((int) position - 1 + offset, (int) position - 1 + offset + actual, buffer, start);
                offset += actual; return actual;
            }
            @Override public void close() throws IOException {
                closed++; active--;
                if (index == 0 && "close".equals(fault)) throw new IOException("range close");
            }
        }
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
