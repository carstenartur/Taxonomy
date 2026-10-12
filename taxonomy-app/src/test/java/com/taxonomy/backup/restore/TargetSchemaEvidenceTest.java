package com.taxonomy.backup.restore;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class TargetSchemaEvidenceTest {
    @Test void actualEmptyMigratedTargetDiffersFromPartlyPopulatedOrChangedSchema() throws Exception {
        var database = database(); var jdbc = new JdbcTemplate(database);
        jdbc.execute("create table restore_example(id integer primary key, evidence varchar(20))");
        var empty = TargetSchemaEvidence.inspect(database, () -> {});
        assertThat(empty.populated()).isFalse();
        assertThat(empty.tableNames()).containsExactly("RESTORE_EXAMPLE");
        assertThat(TargetSchemaEvidence.inspect(database, () -> {})).isEqualTo(empty);
        jdbc.update("insert into restore_example values (1, 'private target row')");
        var occupied = TargetSchemaEvidence.inspect(database, () -> {});
        assertThat(occupied.populated()).isTrue(); assertThat(occupied.schemaSha256()).isNotEqualTo(empty.schemaSha256());
        assertThat(occupied.toString()).doesNotContain("private target row", "jdbc:");
        jdbc.update("delete from restore_example");
        jdbc.execute("alter table restore_example add column new_contract integer");
        var changed = TargetSchemaEvidence.inspect(database, () -> {});
        assertThat(changed.populated()).isFalse(); assertThat(changed.schemaSha256()).isNotEqualTo(empty.schemaSha256());
    }
    @Test void cancellationIsCheckedDuringActualTableDiscovery() {
        var database = database();
        assertThatThrownBy(() -> TargetSchemaEvidence.inspect(database, () -> { throw new java.io.InterruptedIOException("private cancellation"); }))
                .isInstanceOf(java.io.InterruptedIOException.class).hasMessage("Restore target schema inspection was cancelled");
        try { assertThat(Thread.currentThread().isInterrupted()).isTrue(); } finally { Thread.interrupted(); }
    }
    @Test void changedCheckConstraintInvalidatesTheSameEmptyTableAndColumns() throws Exception {
        var database = database(); var jdbc = new JdbcTemplate(database);
        jdbc.execute("create table restore_example(id integer primary key)");
        var empty = TargetSchemaEvidence.inspect(database, () -> {});
        jdbc.execute("alter table restore_example add constraint positive_restored_id check(id > 0)");
        var changed = TargetSchemaEvidence.inspect(database, () -> {});
        assertThat(changed.populated()).isFalse();
        assertThat(changed.schemaSha256()).isNotEqualTo(empty.schemaSha256());
    }
    private static JDBCDataSource database() {
        var database = new JDBCDataSource(); database.setURL("jdbc:hsqldb:mem:target-schema-" + UUID.randomUUID()); database.setUser("sa"); return database;
    }
}
