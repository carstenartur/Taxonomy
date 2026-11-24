package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.exchange.backup.PortableJsonPayloads;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
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

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void readsEveryFieldBeforeClosingReadersAndAllLocators(boolean packed) throws Exception {
        var f = new Fixture();
        String analysisStored = packed ? packedAnalysis(PAYLOADS[0]) : PAYLOADS[0];
        f.inputs[0] = new Input(analysisStored);
        when(f.clobs[0].getCharacterStream()).thenReturn(f.inputs[0]);
        var value = f.reader.read(7L, "snapshot", "scope").orElseThrow();
        Object[] actual = {value.analysis(), value.gapAnalysis(), value.patternDetection(), value.recommendation()};
        Class<?>[] types = {AnalysisResult.class, GapAnalysisView.class, PatternDetectionView.class, ArchitectureRecommendation.class};
        var json = JsonMapper.builder().build();
        for (int i = 0; i < actual.length; i++) {
            tools.jackson.databind.JsonNode actualFields = json.valueToTree(actual[i]);
            tools.jackson.databind.JsonNode originalFields = json.valueToTree(f.codec.read(PAYLOADS[i], types[i]));
            assertThat(actualFields).isEqualTo(originalFields);
            assertThat(f.inputs[i].closed).isEqualTo(1);
            assertThat(f.inputs[i].position).isEqualTo(i == 0 ? analysisStored.length() : PAYLOADS[i].length());
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

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void continuationAnalysisBindsTheCompleteOwnershipAndPreservesFullEvidence(boolean packed) throws Exception {
        var f = new Fixture();
        if (packed) {
            f.inputs[0] = new Input(packedAnalysis(PAYLOADS[0]));
            when(f.clobs[0].getCharacterStream()).thenReturn(f.inputs[0]);
        }
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

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aMatchingContinuationConsumesItsOwnLocatorWithoutBindingAgain(boolean packed) throws Exception {
        var f = new ContinuationFixture();
        String stored = packed ? packedAnalysis(PAYLOADS[0]) : PAYLOADS[0];
        var cold = f.read("snapshot", stored, 1);
        verify(f.codec).read(any(Reader.class), eq(AnalysisResult.class));
        clearInvocations(f.codec);
        var warm = f.read("snapshot", stored, 1);
        assertThat(warm.inputs()).isSameAs(cold.inputs());
        assertThat(warm.inputs().status()).isEqualTo("PARTIAL");
        assertThat(warm.inputs().warnings()).containsExactly("Recorded warning");
        verifyNoInteractions(f.codec);
    }

    @Test void aPackedMismatchFreesTheComparisonBeforeBindingTheNewLocator() throws Exception {
        var f = new ContinuationFixture();
        String initial = packedAnalysis(PAYLOADS[0]);
        String changed = packedAnalysis(PAYLOADS[0].replace("Recorded warning", "Different warning"));
        f.read("snapshot", initial, 1);
        clearInvocations(f.codec);
        var comparison = new Input(changed);
        var validation = new Input(changed);
        Clob compared = f.clob(comparison), validated = f.clob(validation);
        when(f.query.getSingleResultOrNull()).thenReturn(new Object[] {"snapshot", compared},
                new Object[] {"snapshot", validated});
        clearInvocations(f.query);
        var current = f.reader.readContinuation(7L, "snapshot", "scope", "job", 11L, 12L).orElseThrow();
        assertThat(current.inputs().warnings()).containsExactly("Different warning");
        var order = inOrder(f.query, compared, validated, f.codec);
        order.verify(f.query).getSingleResultOrNull();
        order.verify(compared).free();
        order.verify(f.query).getSingleResultOrNull();
        order.verify(f.codec).read(any(Reader.class), eq(AnalysisResult.class));
        order.verify(validated).free();
        assertThat(comparison.position).isEqualTo(changed.length());
        assertThat(validation.position).isEqualTo(changed.length());
        assertThat(comparison.closed).isEqualTo(1);
        assertThat(validation.closed).isEqualTo(1);
        clearInvocations(f.codec);
        assertThat(f.read("snapshot", changed, 1).inputs()).isSameAs(current.inputs());
        verifyNoInteractions(f.codec);
    }

    @Test void equalLengthPackedCorruptionNeverReturnsTheWarmPolicy() throws Exception {
        var f = new ContinuationFixture();
        String stored = packedAnalysis(PAYLOADS[0]);
        String corrupt = corruptFrame(stored);
        var initial = f.read("snapshot", stored, 1);
        var comparison = new Input(corrupt);
        var validation = new Input(corrupt);
        Clob compared = f.clob(comparison), validated = f.clob(validation);
        when(f.query.getSingleResultOrNull()).thenReturn(new Object[] {"snapshot", compared},
                new Object[] {"snapshot", validated});
        assertThatThrownBy(() -> f.reader.readContinuation(7L, "snapshot", "scope", "job", 11L, 12L))
                .isInstanceOf(PortfolioException.class).hasRootCauseInstanceOf(IOException.class);
        for (Input input : List.of(comparison, validation)) {
            assertThat(input.position).isEqualTo(stored.length());
            assertThat(input.closed).isEqualTo(1);
        }
        verify(compared).free();
        verify(validated).free();
        assertThat(f.read("snapshot", stored, 1).inputs()).isSameAs(initial.inputs());
    }

    static String packedAnalysis(String source) throws IOException {
        String packed = PortableJsonPayloads.pack(source + " ".repeat(65_536), 24_000_000);
        assertThat(packed).startsWith("taxonomy-json-payload:v1:");
        return packed;
    }

    static String corruptFrame(String frame) {
        int base64 = frame.lastIndexOf(':') + 1;
        char changed = frame.charAt(base64) == 'A' ? 'B' : 'A';
        return frame.substring(0, base64) + changed + frame.substring(base64 + 1);
    }

    @ParameterizedTest @ValueSource(strings = {"", " \t\r\n", "null"})
    void anExistingContinuationWithoutAnalysisRemainsDistinctFromAnAbsentRow(String source) throws Exception {
        var f = new ContinuationFixture();
        assertThat(f.read("snapshot", new Input(source)).inputs()).isNull();
        when(f.query.getSingleResultOrNull()).thenReturn(new Object[] {"snapshot", null});
        assertThat(f.reader.readContinuation(7L, "snapshot", "scope", "job", 11L, 12L)
                .orElseThrow().inputs()).isNull();
        when(f.query.getSingleResultOrNull()).thenReturn(null);
        assertThat(f.reader.readContinuation(7L, "snapshot", "scope", "job", 11L, 12L)).isEmpty();
    }

    @Test void validationPublishesItsOwnFingerprintAfterContentChangesBetweenPasses() throws Exception {
        var f = new ContinuationFixture();
        f.read("snapshot", new Input("{\"status\":\"PARTIAL\"}"));
        var comparison = new Input("{\"status\":\"ERROR\"}");
        var validation = new Input("{\"status\":\"SUCCESS\"}");
        Clob compared = f.clob(comparison), validated = f.clob(validation);
        when(f.query.getSingleResultOrNull()).thenReturn(new Object[] {"snapshot", compared},
                new Object[] {"snapshot", validated});
        var changed = f.reader.readContinuation(7L, "snapshot", "scope", "job", 11L, 12L).orElseThrow();
        assertThat(changed.inputs().status()).isEqualTo("SUCCESS");
        var order = inOrder(f.query, compared, validated);
        order.verify(compared).free();
        order.verify(f.query).getSingleResultOrNull();
        order.verify(validated).free();
        assertThat(f.read("snapshot", new Input("{\"status\":\"SUCCESS\"}")).inputs()).isSameAs(changed.inputs());
        verify(f.codec, times(2)).read(any(Reader.class), eq(AnalysisResult.class));
    }

    @Test void fingerprintDistinguishesStoredCodeUnitsLostByUtf8Replacement() throws Exception {
        var f = new ContinuationFixture();
        var ascii = f.read("snapshot", new Input("{\"warnings\":[\"?\"]}"));
        String surrogate = String.valueOf((char) 0xD800);
        var different = f.read("snapshot", new Input("{\"warnings\":[\"" + surrogate + "\"]}"));
        assertThat(ascii.inputs().warnings()).containsExactly("?");
        assertThat(different.inputs().warnings()).containsExactly(surrogate);
        verify(f.codec, times(2)).read(any(Reader.class), eq(AnalysisResult.class));
    }

    @ParameterizedTest @EnumSource(ContinuationFailure.class)
    void warmContinuationSurfacesLateFailuresAndReleasesItsOwnLocator(ContinuationFailure step) throws Exception {
        var f = new ContinuationFixture();
        var good = f.read("snapshot", new Input(PAYLOADS[0]));
        var input = new Input(PAYLOADS[0]);
        Clob clob = f.clob(input);
        when(f.query.getSingleResultOrNull()).thenReturn(new Object[] {"snapshot", clob});
        Exception failure = step.fail(input, clob);
        assertThatThrownBy(() -> f.reader.readContinuation(7L, "snapshot", "scope", "job", 11L, 12L))
                .isInstanceOf(PortfolioException.class).hasRootCauseExactlyInstanceOf(failure.getClass())
                .hasRootCauseMessage(failure.getMessage());
        assertThat(input.position).isEqualTo(PAYLOADS[0].length());
        assertThat(input.closed).isEqualTo(1);
        verify(clob).free();
        assertThat(f.read("snapshot", new Input(PAYLOADS[0])).inputs()).isSameAs(good.inputs());
        verify(f.codec).read(any(Reader.class), eq(AnalysisResult.class));
    }

    @ParameterizedTest @EnumSource(ContinuationFailure.class)
    void aFailedColdContinuationNeverPublishesItsInputs(ContinuationFailure step) throws Exception {
        var f = new ContinuationFixture();
        var input = new Input(PAYLOADS[0]);
        Clob clob = f.clob(input);
        when(f.query.getSingleResultOrNull()).thenReturn(new Object[] {"snapshot", clob});
        Exception failure = step.fail(input, clob);
        assertThatThrownBy(() -> f.reader.readContinuation(7L, "snapshot", "scope", "job", 11L, 12L))
                .isInstanceOf(PortfolioException.class).hasStackTraceContaining(failure.getMessage());
        assertThat(input.closed).isEqualTo(1);
        verify(clob).free();
        assertThat(f.read("snapshot", new Input(PAYLOADS[0])).inputs().status()).isEqualTo("PARTIAL");
        verify(f.codec, times(2)).read(any(Reader.class), eq(AnalysisResult.class));
    }

    @Test void identicalRowsFromSeparateSessionFactoriesRequireTheirOwnValidation() throws Exception {
        var f = new ContinuationFixture();
        var first = f.read("snapshot", new Input(PAYLOADS[0]));
        when(f.session.getSessionFactory()).thenReturn(mock(SessionFactory.class));
        var other = f.read("snapshot", new Input(PAYLOADS[0]));
        assertThat(other.inputs()).isEqualTo(first.inputs()).isNotSameAs(first.inputs());
        assertThat(f.read("snapshot", new Input(PAYLOADS[0])).inputs()).isSameAs(other.inputs());
        verify(f.codec, times(2)).read(any(Reader.class), eq(AnalysisResult.class));
    }

    @Test void seventeenthIdentityEvictsTheLeastRecentlyUsedPolicyInputs() throws Exception {
        var f = new ContinuationFixture();
        var first = f.read("1", new Input(PAYLOADS[0]));
        var second = f.read("2", new Input(PAYLOADS[0]));
        for (int i = 3; i <= 16; i++) f.read(Integer.toString(i), new Input(PAYLOADS[0]));
        assertThat(f.read("1", new Input(PAYLOADS[0])).inputs()).isSameAs(first.inputs());
        f.read("17", new Input(PAYLOADS[0]));
        assertThat(f.read("1", new Input(PAYLOADS[0])).inputs()).isSameAs(first.inputs());
        assertThat(f.read("2", new Input(PAYLOADS[0])).inputs()).isEqualTo(second.inputs()).isNotSameAs(second.inputs());
    }

    @Test void retainedStringsCountTowardAggregateBudgetAndOversizedInputsAreNeverMemoized() throws Exception {
        var f = new ContinuationFixture();
        String large = "{\"warnings\":[\"" + "水".repeat(550_000) + "\"]}";
        var first = f.read("first", new Input(large));
        f.read("second", new Input(large));
        assertThat(f.read("first", new Input(large)).inputs()).isEqualTo(first.inputs()).isNotSameAs(first.inputs());
        String oversized = "{\"warnings\":[\"" + "水".repeat(1_050_000) + "\"]}";
        var complete = f.read("oversized", new Input(oversized));
        assertThat(complete.inputs().warnings()).singleElement().asString().hasSize(1_050_000);
        assertThat(f.read("oversized", new Input(oversized)).inputs()).isEqualTo(complete.inputs()).isNotSameAs(complete.inputs());
        verify(f.codec, times(5)).read(any(Reader.class), eq(AnalysisResult.class));
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

    private enum ContinuationFailure {
        READ, CLOSE, FREE;

        Exception fail(Input input, Clob clob) throws SQLException {
            return switch (this) {
                case READ -> input.eofFailure = new IOException("late continuation read");
                case CLOSE -> input.closeFailure = new IOException("continuation reader close");
                case FREE -> {
                    var exception = new SQLException("continuation locator free");
                    doThrow(exception).when(clob).free();
                    yield exception;
                }
            };
        }
    }

    private static final class ContinuationFixture {
        final Session session = mock(Session.class);
        @SuppressWarnings("unchecked") final NativeQuery<Object[]> query = mock(NativeQuery.class, Answers.RETURNS_SELF);
        final PortfolioJsonCodec codec = spy(new PortfolioJsonCodec(JsonMapper.builder().build()));
        final PortfolioSnapshotEvidenceReader reader;

        ContinuationFixture() {
            var entityManager = mock(EntityManager.class);
            when(entityManager.unwrap(Session.class)).thenReturn(session);
            when(session.getSessionFactory()).thenReturn(mock(SessionFactory.class));
            when(session.createNativeQuery(anyString(), eq(Object[].class))).thenReturn(query);
            reader = new PortfolioSnapshotEvidenceReader(entityManager, codec);
        }

        Clob clob(Input input) throws SQLException {
            Clob clob = mock(Clob.class);
            when(clob.length()).thenReturn((long) input.length);
            when(clob.getCharacterStream()).thenReturn(input);
            when(clob.getCharacterStream(anyLong(), anyLong())).thenThrow(new SQLFeatureNotSupportedException());
            return clob;
        }

        PortfolioSnapshotEvidenceReader.ContinuationEvidence read(String id, String stored, int reads) throws SQLException {
            return read(id, new Input(stored), reads);
        }

        PortfolioSnapshotEvidenceReader.ContinuationEvidence read(String id, Input input) throws SQLException {
            return read(id, input, null);
        }

        private PortfolioSnapshotEvidenceReader.ContinuationEvidence read(String id, Input input, Integer reads)
                throws SQLException {
            Clob clob = clob(input);
            // Any replacement triggers a second SELECT with its own locator and reader.
            var replacement = new Input(input.source);
            Clob second = clob(replacement);
            when(query.getSingleResultOrNull()).thenReturn(new Object[] {id, clob}, new Object[] {id, second});
            var evidence = reader.readContinuation(7L, id, "scope", "job", 11L, 12L).orElseThrow();
            assertThat(input.position).isEqualTo(input.length);
            assertThat(input.closed).isEqualTo(1);
            verify(clob).free();
            if (reads != null) assertThat(replacement.closed).isEqualTo(reads - 1);
            if (replacement.closed == 1) {
                assertThat(replacement.position).isEqualTo(replacement.length);
                verify(second).free();
            }
            return evidence;
        }
    }

    private static final class Input extends Reader {
        final String source;
        final int length;
        final StringReader delegate;
        int position, closed;
        IOException eofFailure, closeFailure;
        Input(String json) { source = json; length = json.length(); delegate = new StringReader(json); }
        @Override public int read(char[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, source.length() > RANGE_CHARACTERS ? length : Math.min(length, 3));
            if (count == -1 && eofFailure != null) throw eofFailure;
            if (count > 0) position += count;
            return count;
        }
        @Override public void close() throws IOException {
            closed++; delegate.close();
            if (closeFailure != null) throw closeFailure;
        }
    }
}
