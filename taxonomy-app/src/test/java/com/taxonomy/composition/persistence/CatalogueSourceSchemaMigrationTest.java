package com.taxonomy.composition.persistence;

import com.taxonomy.catalog.provenance.*;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CatalogueSourceSchemaMigrationTest {
    @Test void migratesBeforeHibernateValidationAndCanRunAgain() throws Exception {
        var database = database(); CatalogueSourceSchemaMigration.migrate(database); assertTables(database);
        try (var factory = new Configuration().setProperty("hibernate.connection.url", database.getUrl())
                .setProperty("hibernate.connection.username", "sa").setProperty("hibernate.hbm2ddl.auto", "validate")
                .addAnnotatedClass(CatalogueSourceBlob.class).addAnnotatedClass(CatalogueSourceRevision.class)
                .addAnnotatedClass(CatalogueSourceState.class).buildSessionFactory()) {
            CatalogueSourceSchemaMigration.migrate(database);
            assertThat(factory.isOpen()).isTrue();
            assertThat(new JdbcTemplate(database).queryForObject("select count(*) from catalogue_source_revision", Long.class)).isZero();
        }
    }

    @Test void migrationPreservesLegacyCatalogueWithoutFabricatingInputEvidence() throws Exception {
        var database = database(); var jdbc = new JdbcTemplate(database);
        jdbc.execute("create table taxonomy_node(id bigint primary key, name_en varchar(100))");
        jdbc.update("insert into taxonomy_node values(1, 'legacy catalogue')");
        CatalogueSourceSchemaMigration.migrate(database); assertTables(database);
        assertThat(jdbc.queryForObject("select name_en from taxonomy_node where id=1", String.class)).isEqualTo("legacy catalogue");
        for (String table : List.of("catalogue_source_blob", "catalogue_source_revision", "catalogue_source_state"))
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).isZero();
    }

    @Test void schemaRejectsDanglingInputAndCurrentReferences() throws Exception {
        var database = database(); CatalogueSourceSchemaMigration.migrate(database); assertTables(database);
        var jdbc = new JdbcTemplate(database); String revision = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update("insert into catalogue_source_state(id,current_revision,row_version) values('base-catalogue',?,0)", revision))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into catalogue_source_revision(id,created_at,workbook_sha256,workbook_use,overlay_use,relations_use) values(?,? ,?,'APPLIED','NOT_USED','NOT_USED')",
                revision, "2026-10-01T00:00:00Z", "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void schemaRejectsImpossibleInputAvailabilityAndOutOfBudgetLength() throws Exception {
        var database = database(); CatalogueSourceSchemaMigration.migrate(database); assertTables(database);
        var jdbc = new JdbcTemplate(database);
        assertThatThrownBy(() -> jdbc.update("insert into catalogue_source_revision(id,created_at,workbook_use,overlay_use,relations_use) values(?,?,'APPLIED','NOT_USED','NOT_USED')",
                UUID.randomUUID().toString(), "2026-10-01T00:00:00Z")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into catalogue_source_blob(sha256,byte_length,payload) values(?,?,?)",
                "a".repeat(64), CatalogueSourceBytes.MAX_BYTES + 1L, new byte[] { 1 })).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static JDBCDataSource database() {
        var database = new JDBCDataSource(); database.setUrl("jdbc:hsqldb:mem:catalogue-migration-" + UUID.randomUUID()); database.setUser("sa"); return database;
    }
    private static void assertTables(JDBCDataSource database) throws Exception {
        var names = new HashSet<String>();
        try (var connection = database.getConnection(); var tables = connection.getMetaData().getTables(null, null, "%", new String[] { "TABLE" })) {
            while (tables.next()) names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        assertThat(names).contains("catalogue_source_blob", "catalogue_source_revision", "catalogue_source_state");
    }
}
