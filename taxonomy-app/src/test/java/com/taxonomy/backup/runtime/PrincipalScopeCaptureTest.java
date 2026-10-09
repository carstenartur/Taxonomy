package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PrincipalScopeCapture;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrincipalScopeCaptureTest {
    private static final PortableRows.Query QUERY = new PortableRows.Query("select scope_key from owners order by id", List.of());
    private static final PortableRows.Mapper<PrincipalScopeCapture.Scope> MAPPER = row -> new PrincipalScopeCapture.Scope(row.getString("scope_key"));

    @Test void collectionIsImmutableAndCountsUniqueReferencesAcrossAllQueries() throws Exception {
        var f = new Fixture(); f.add(1, "a"); f.add(2, "a"); f.add(3, null); f.add(4, "b");
        var capture = new PrincipalScopeCapture(new PortableRows(f.database), 2);
        var scopes = capture.capture(List.of(QUERY, QUERY), MAPPER, () -> { }); assertThat(scopes).containsExactlyInAnyOrder("a", "b");
        assertThatThrownBy(scopes::clear).isInstanceOf(UnsupportedOperationException.class);
        f.add(5, "c"); assertFailure(() -> capture.capture(List.of(QUERY), MAPPER, () -> { }));
        assertThatThrownBy(() -> new PrincipalScopeCapture(new PortableRows(f.database), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrincipalScopeCapture(new PortableRows(f.database), 100_001)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", "  ", "PRIVATE\nSCOPE"})
    void malformedSourceScopeCannotBecomeAReference(String scope) throws Exception {
        var f = new Fixture(); f.add(1, scope); assertFailure(() -> f.capture().capture(List.of(QUERY), MAPPER, () -> { }));
    }

    @Test void longAndUnmappedSourceValuesStayOutOfDiagnostics() throws Exception {
        var f = new Fixture(); f.add(1, "PRIVATE".repeat(40)); assertFailure(() -> f.capture().capture(List.of(QUERY), MAPPER, () -> { }));
        f.jdbc.update("update owners set scope_key='valid'");
        assertFailure(() -> f.capture().capture(List.of(QUERY), row -> { throw new IllegalArgumentException("PRIVATE mapper"); }, () -> { }));
    }

    @Test void emptySelectionsCheckCancellationWithoutQueryingAnyTable() throws Exception {
        var database = mock(DataSource.class); var capture = new PrincipalScopeCapture(new PortableRows(database));
        assertThat(capture.capture(List.of(), MAPPER, () -> { })).isEmpty();
        assertFailure(() -> capture.capture(List.of(), MAPPER, () -> { throw new IOException("PRIVATE fence"); }));
        verifyNoInteractions(database);
    }

    @Test void cancelledRowDiscoverySurvivesFailingJdbcCleanupWithoutPrivateDiagnostics() throws Exception {
        var f = new Fixture(); f.add(1, "owner"); var querying = new AtomicBoolean(); var database = mock(DataSource.class);
        when(database.getConnection()).thenAnswer(call -> {
            Connection connection = f.database.getConnection();
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("rollback")) throw new SQLException("PRIVATE rollback");
                try {
                    Object result = method.invoke(connection, args);
                    if (method.getName().equals("prepareStatement")) querying.set(true);
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        });
        var capture = new PrincipalScopeCapture(new PortableRows(database));
        try {
            assertThatThrownBy(() -> capture.capture(List.of(QUERY), MAPPER, () -> { if (querying.get()) throw new InterruptedIOException("PRIVATE cancel"); }))
                    .isInstanceOf(InterruptedIOException.class).hasMessageNotContaining("PRIVATE").hasNoCause().hasNoSuppressedExceptions();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void anExistingInterruptRemainsSet() throws Exception {
        var capture = new PrincipalScopeCapture(new PortableRows(mock(DataSource.class)));
        try {
            Thread.currentThread().interrupt(); assertThatThrownBy(() -> capture.capture(List.of(), MAPPER, () -> { })).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(IOException.class).hasMessageNotContaining("PRIVATE").hasNoCause().hasNoSuppressedExceptions();
    }
    private static final class Fixture {
        final JDBCDataSource database = new JDBCDataSource(); final JdbcTemplate jdbc;
        Fixture() {
            database.setUrl("jdbc:hsqldb:mem:principal-capture-" + UUID.randomUUID()); database.setUser("SA"); jdbc = new JdbcTemplate(database);
            jdbc.execute("create table owners(id integer,scope_key varchar(1024))");
        }
        void add(int id, String scope) { jdbc.update("insert into owners values (?,?)", id, scope); }
        PrincipalScopeCapture capture() { return new PrincipalScopeCapture(new PortableRows(database)); }
    }
}
