package com.taxonomy.portfolio.service;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.Test;
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

/** Failure boundaries that cannot be induced safely through the real SQL selection fixture. */
class PortfolioSnapshotSelectionReaderTest {
    @Test
    void finishesSelectionBeforeClosingTheReaderAndFreeingTheClob() throws Exception {
        var f = new Fixture("{\"scores\":{\"A\":40},\"warnings\":[\"retained\"]}");
        var selection = f.reader.read(7L, "snapshot", "scope").orElseThrow();
        assertThat(selection.id()).isEqualTo("snapshot");
        assertThat(selection.status()).isEqualTo(com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus.PARTIAL);
        assertThat(selection.durationMs()).isEqualTo(123);
        assertThat(selection.scores()).containsEntry("A", 40d);
        assertThat(selection.warningCount()).isEqualTo(1);
        assertThat(selection.elementMappingCount()).isEqualTo(2);
        assertThat(selection.relationMappingCount()).isEqualTo(3);
        assertThat(f.input.closeCount).isEqualTo(1);
        assertThat(f.input.position).isEqualTo(f.input.length);
        verify(f.clob).free();
    }

    @Test
    void failedDeserializationKeepsBothCleanupFailuresSuppressed() throws Exception {
        var f = new Fixture("{\"scores\":{\"A\":40},\"warnings\":[");
        var readerClose = new IOException("reader close");
        var clobFree = new SQLException("clob free");
        f.input.closeFailure = readerClose;
        doThrow(clobFree).when(f.clob).free();
        assertThatThrownBy(() -> f.reader.read(7L, "snapshot", "scope"))
                .isInstanceOfSatisfying(PortfolioException.class, failure -> {
                    assertThat(failure.getMessage()).contains("deserialize portfolio selection");
                    assertThat(failure.getSuppressed()).containsExactly(readerClose, clobFree);
                    assertThat(failure.getCause()).isNotSameAs(readerClose).isNotSameAs(clobFree);
                });
        assertThat(f.input.closeCount).isEqualTo(1);
        verify(f.clob).free();
    }

    @Test
    void failureToOpenAClobStreamStillFreesItsLocator() throws Exception {
        var f = new Fixture("{}");
        var opening = new SQLException("stream open");
        var freeing = new SQLException("clob free");
        when(f.clob.getCharacterStream()).thenThrow(opening);
        doThrow(freeing).when(f.clob).free();
        assertThatThrownBy(() -> f.reader.read(7L, "snapshot", "scope"))
                .isInstanceOfSatisfying(PortfolioException.class, failure -> {
                    assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.ANALYSIS_FAILED);
                    assertThat(failure.getCause()).isSameAs(opening);
                    assertThat(opening.getSuppressed()).containsExactly(freeing);
                });
        assertThat(f.input.closeCount).isZero();
        verify(f.clob).free();
    }

    @Test
    void successfulParsingDoesNotHideReaderAndLocatorCleanupFailures() throws Exception {
        var f = new Fixture("{\"scores\":{\"A\":40}}");
        var readerClose = new IOException("reader close");
        var clobFree = new SQLException("clob free");
        f.input.closeFailure = readerClose;
        doThrow(clobFree).when(f.clob).free();
        assertThatThrownBy(() -> f.reader.read(7L, "snapshot", "scope"))
                .isInstanceOfSatisfying(PortfolioException.class, failure -> {
                    assertThat(failure.getCause()).isSameAs(readerClose);
                    assertThat(readerClose.getSuppressed()).containsExactly(clobFree);
                });
        assertThat(f.input.closeCount).isEqualTo(1);
        verify(f.clob).free();
    }

    @Test
    void anAbsentAnalysisRetainsTheSnapshotIdentityAndCurrentCounts() throws Exception {
        var f = new Fixture("{}");
        f.row[3] = null;
        var selection = f.reader.read(7L, "snapshot", "scope").orElseThrow();
        assertThat(selection.id()).isEqualTo("snapshot");
        assertThat(selection.scores()).isEmpty();
        assertThat(selection.warningCount()).isZero();
        assertThat(selection.elementMappingCount()).isEqualTo(2);
        assertThat(selection.relationMappingCount()).isEqualTo(3);
        verifyNoInteractions(f.clob);
    }

    @Test
    void aMissingScopedRowDoesNotAcquireAnyPayloadResource() throws Exception {
        var f = new Fixture("{}");
        when(f.query.getSingleResultOrNull()).thenReturn(null);
        assertThat(f.reader.read(7L, "snapshot", "scope")).isEmpty();
        verifyNoInteractions(f.clob);
    }

    private static final class Fixture {
        final EntityManager entityManager = mock(EntityManager.class);
        final Session session = mock(Session.class);
        @SuppressWarnings("unchecked")
        final NativeQuery<Object[]> query = mock(NativeQuery.class, Answers.RETURNS_SELF);
        final Clob clob = mock(Clob.class);
        final Input input;
        final Object[] row = {"snapshot", "PARTIAL", 123L, clob, 2L, 3L};
        final PortfolioSnapshotSelectionReader reader = new PortfolioSnapshotSelectionReader(
                entityManager, new PortfolioJsonCodec(JsonMapper.builder().build()));
        Fixture(String json) throws SQLException {
            input = new Input(json);
            when(entityManager.unwrap(Session.class)).thenReturn(session);
            when(session.createNativeQuery(anyString(), eq(Object[].class))).thenReturn(query);
            when(query.getSingleResultOrNull()).thenReturn(row);
            when(clob.getCharacterStream()).thenReturn(input);
        }
    }

    private static final class Input extends Reader {
        private final StringReader delegate;
        private int closeCount;
        private final int length;
        private int position;
        private IOException closeFailure;
        Input(String json) { delegate = new StringReader(json); length = json.length(); }
        @Override public int read(char[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, Math.min(length, 3));
            if (count > 0) position += count;
            return count;
        }
        @Override public void close() throws IOException {
            closeCount++;
            delegate.close();
            if (closeFailure != null) throw closeFailure;
        }
    }
}
