package com.taxonomy.backup;

import com.taxonomy.exchange.backup.PortableRows;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import java.io.InterruptedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

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
    private record Item(int id) { }
}
