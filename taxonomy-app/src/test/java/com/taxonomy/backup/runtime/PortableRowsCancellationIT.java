package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.exchange.backup.PortableRows;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortableRowsCancellationIT {
    @ParameterizedTest @CsvSource({"visit,false", "visit,true", "write,false", "write,true",
            "batches,false", "batches,true", "dependencies,false", "dependencies,true"})
    void cancellationIsCheckedBeforeTheDriverCanConsumeTheInterrupt(String operation, boolean duringConnection) throws Exception {
        var database = new JDBCDataSource(); database.setUrl("jdbc:hsqldb:mem:cancel-rows-" + UUID.randomUUID()); database.setUser("sa");
        try (var setup = database.getConnection(); var ddl = setup.createStatement()) { ddl.execute("create table sample(id integer)"); }
        var opened = new ArrayList<Connection>();
        var source = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var connection = database.getConnection(); opened.add(connection);
                if (duringConnection) Thread.currentThread().interrupt();
                return connection;
            }
            @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        };
        var rows = new PortableRows(source);
        var query = new PortableRows.Query("select id from sample", List.of());
        var sink = new CurrentStateExportIT.Contents();
        try {
            if (!duringConnection) Thread.currentThread().interrupt();
            assertThatThrownBy(() -> {
                switch (operation) {
                    case "visit" -> rows.visit(query, r -> new Item(r.getInt("id")), ignored -> { });
                    case "write" -> rows.write(sink, "test", "items", BackupProfile.CURRENT_STATE, query, r -> new Item(r.getInt("id")));
                    case "batches" -> rows.writeBatches(sink, "test", "items", BackupProfile.CURRENT_STATE, List.of(query), r -> new Item(r.getInt("id")));
                    case "dependencies" -> rows.requireEmpty(query);
                    default -> throw new AssertionError(operation);
                }
            }).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(opened).hasSize(duringConnection ? 1 : 0);
            for (var connection : opened) assertThat(connection.isClosed()).isTrue();
        } finally {
            Thread.interrupted();
            try (var cleanup = database.getConnection(); var ddl = cleanup.createStatement()) { ddl.execute("shutdown"); }
        }
    }
    @Test
    void oidTextFreesItsLocatorWhenOpeningTheReaderFails() throws Exception {
        var locator = mock(Clob.class);
        var row = oidTextRow(locator);
        var openFailure = new SQLException("Could not open test locator");
        var freeFailure = new SQLException("Could not free test locator");
        when(locator.getCharacterStream()).thenThrow(openFailure);
        doThrow(freeFailure).when(locator).free();

        assertThatThrownBy(() -> PortableRows.textWithOidLocator(row, "payload", 10))
                .isInstanceOf(IOException.class).hasCause(openFailure)
                .satisfies(error -> assertThat(error.getSuppressed()).singleElement()
                        .satisfies(cleanup -> assertThat(cleanup).isInstanceOf(IOException.class).hasCause(freeFailure)));
        var order = inOrder(locator);
        order.verify(locator).getCharacterStream();
        order.verify(locator).free();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void oidTextRetainsReaderAndLocatorFailuresDuringBoundedAndCancelledReads(boolean cancelling) throws Exception {
        var locator = mock(Clob.class);
        var row = oidTextRow(locator);
        var input = spy(new FilterReader(new StringReader("oversized")) { });
        var closeFailure = new IOException("Could not close test reader");
        var freeFailure = new SQLException("Could not free test locator");
        when(locator.getCharacterStream()).thenAnswer(invocation -> {
            if (cancelling) Thread.currentThread().interrupt();
            return input;
        });
        doThrow(closeFailure).when(input).close();
        doThrow(freeFailure).when(locator).free();

        try {
            var thrown = catchThrowable(() -> PortableRows.textWithOidLocator(row, "payload", 3));
            assertThat(thrown).isInstanceOf(IOException.class);
            if (cancelling) {
                assertThat(thrown).isInstanceOf(InterruptedIOException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                verify(input, never()).read(any(char[].class));
            } else {
                assertThat(thrown).hasMessageContaining("limit");
            }
            assertThat(thrown.getSuppressed()).hasSize(2);
            assertThat(thrown.getSuppressed()[0]).isSameAs(closeFailure);
            assertThat(thrown.getSuppressed()[1]).hasCause(freeFailure);
            var order = inOrder(input, locator);
            order.verify(input).close();
            order.verify(locator).free();
        } finally {
            Thread.interrupted();
        }
    }

    private static ResultSet oidTextRow(Clob locator) throws SQLException {
        var row = mock(ResultSet.class);
        var metadata = mock(ResultSetMetaData.class);
        when(row.getMetaData()).thenReturn(metadata);
        when(row.findColumn("payload")).thenReturn(1);
        when(metadata.getColumnTypeName(1)).thenReturn("oid");
        when(row.getClob("payload")).thenReturn(locator);
        return row;
    }

    private record Item(int id) { }
}
