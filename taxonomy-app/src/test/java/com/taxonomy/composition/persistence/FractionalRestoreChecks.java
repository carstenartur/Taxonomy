package com.taxonomy.composition.persistence;

import com.taxonomy.backup.restore.RestoreSchemaMigration;
import com.taxonomy.relations.model.RequirementCoverage;
import org.hsqldb.jdbc.JDBCDataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/** Real Flyway owners plus Hibernate validation; no Spring context or external database. */
final class FractionalRestoreChecks {
    static void migratedRestoreSchemaValidatesAndPersistsDecimals() throws Exception {
        var active = database(); var target = database();
        try (var connection = active.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create table source_marker(id integer)");
            statement.execute("insert into source_marker values(42)");
        }
        RestoreSchemaMigration.bootstrap(active, target, () -> {});
        var model = new ArrayList<Class<?>>();
        try (var input = FractionalRestoreChecks.class.getResourceAsStream("/db/restore/hsqldb/approved-business-model.txt")) {
            if (input == null) throw new AssertionError("Missing real registered model");
            for (String name : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList())
                if (!name.isBlank() && !name.startsWith("#")) model.add(Class.forName(name));
        }
        Long identity;
        try (var factory = RestoreSchemaMigration.openValidatedFactory(active, target, model, () -> {})) {
            var coverage = new RequirementCoverage("fixture", "Authored storage fixture", "fixture-node", .125, Instant.parse("2026-10-15T00:00:00Z"));
            factory.inTransaction(session -> session.persist(coverage));
            identity = coverage.getId();
        }
        // A new factory and connection must read the actual stored value, not a persistence-context cache.
        try (var factory = RestoreSchemaMigration.openValidatedFactory(active, target, model, () -> {});
             var session = factory.openSession()) {
            var reloaded = session.find(RequirementCoverage.class, identity);
            if (reloaded == null || reloaded.getScore() != .125) throw new AssertionError("Hibernate lost the fraction");
        }
        try (var connection = target.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("select count(*) from \"restore_business_schema_history\" where \"success\"=true")) {
            if (!rows.next() || rows.getInt(1) != 8) throw new AssertionError("Not all owned migrations executed");
        }
        try (var connection = active.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("select id from source_marker")) {
            if (!rows.next() || rows.getInt(1) != 42) throw new AssertionError("Active source was mutated");
        }
    }
    private static JDBCDataSource database() {
        var result = new JDBCDataSource(); result.setURL("jdbc:hsqldb:mem:fractional-restore-" + UUID.randomUUID()); result.setUser("sa"); return result;
    }
    public static void main(String[] arguments) throws Exception {
        migratedRestoreSchemaValidatesAndPersistsDecimals();
        System.out.println("FRACTIONAL_RESTORE_HIBERNATE_OK");
    }
}
