package com.taxonomy.dsl.storage;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.schema.LegacyCoreSchemaAdoption;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;

/**
 * Runs the versioned JGit Core schema before Hibernate initializes its persistence unit.
 *
 * <p>Fresh installation and history establishment are automatic only after the existing
 * schema has been classified. Adoption of Taxonomy's pre-library schema is fail-closed
 * and requires an explicit operator flag after backup and read-only preflight.</p>
 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true")
public class JgitStorageSchemaMigrationConfig {

    private static final Logger log =
            LoggerFactory.getLogger(JgitStorageSchemaMigrationConfig.class);

    private static final int REQUIRED_REF_NAME_LENGTH = 1024;
    private static final int LEGACY_TAXONOMY_REF_NAME_LENGTH = 255;

    private static final Set<String> LEGACY_PACK_COLUMNS = Set.of(
            "ID",
            "REPOSITORY_NAME",
            "PACK_NAME",
            "PACK_EXTENSION",
            "DATA",
            "FILE_SIZE",
            "CREATED_AT");

    private static final Set<String> CURRENT_PACK_COLUMNS = Set.of(
            "ID",
            "REPOSITORY_NAME",
            "PACK_NAME",
            "PACK_EXTENSION",
            "DATA",
            "FILE_SIZE",
            "COMMITTED",
            "CREATED_AT",
            "COMMITTED_AT");

    private static final Set<String> REFLOG_COLUMNS = Set.of(
            "ID",
            "VERSION",
            "REPOSITORY_NAME",
            "REF_NAME",
            "OLD_ID",
            "NEW_ID",
            "WHO_NAME",
            "WHO_EMAIL",
            "WHO_WHEN",
            "MESSAGE");

    /** Restrict the Boot-managed Flyway instance to the released Core migration stream. */
    @Bean
    public FlywayConfigurationCustomizer jgitStorageFlywayConfigurationCustomizer() {
        return configuration -> {
            DatabaseFamily family = DatabaseFamily.detect(configuration.getDataSource());
            configureNormalMigration(configuration, family);
        };
    }

    /**
     * Classify the existing schema and select the only safe migration path.
     *
     * @param legacyAdoptionEnabled explicit one-time operator opt-in for the pre-library schema
     */
    @Bean
    public FlywayMigrationStrategy jgitStorageFlywayMigrationStrategy(
            @Value("${taxonomy.jgit-storage.legacy-adoption:false}")
            boolean legacyAdoptionEnabled) {
        return flyway -> migrateCoreSchema(flyway, legacyAdoptionEnabled);
    }

    static void migrateCoreSchema(Flyway flyway, boolean legacyAdoptionEnabled) {
        Configuration configuration = flyway.getConfiguration();
        DataSource dataSource = configuration.getDataSource();
        DatabaseFamily family = DatabaseFamily.detect(dataSource);
        SchemaSnapshot schema = SchemaSnapshot.inspect(dataSource);

        if (schema.hasTable(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)) {
            requireCurrentCoreShape(schema);
            log.info("Migrating managed JGit Core schema for {}", family.displayName());
            flyway.migrate();
            requireCurrentCoreShape(SchemaSnapshot.inspect(dataSource));
            return;
        }

        if (schema.hasTable(CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE)) {
            throw unsafeSchema(
                    "The legacy-adoption history exists without the normal Core history. "
                            + "Restore the pre-migration backup or complete the documented "
                            + "history-establishment step before startup.");
        }

        boolean hasPacks = schema.hasTable("git_packs");
        boolean hasReflog = schema.hasTable("git_reflog");
        if (!hasPacks && !hasReflog) {
            if (schema.tables().isEmpty()) {
                log.info("Installing JGit Core schema into empty {} database", family.displayName());
                flyway.migrate();
            } else {
                log.info(
                        "Installing JGit Core schema into shared {} schema with pre-migration baseline",
                        family.displayName());
                migrateNormalWithBaseline(
                        configuration,
                        family,
                        CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION,
                        CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION);
                flyway.migrate();
            }
            requireCurrentCoreShape(SchemaSnapshot.inspect(dataSource));
            return;
        }

        if (hasPacks != hasReflog) {
            throw unsafeSchema(
                    "Only one of git_packs and git_reflog exists. Refusing to repair a partial "
                            + "JGit storage schema automatically.");
        }

        requireExactColumns("git_reflog", schema.reflogColumns(), REFLOG_COLUMNS);

        if (schema.packColumns().equals(LEGACY_PACK_COLUMNS)) {
            migrateLegacyTaxonomySchema(
                    configuration, family, dataSource, schema, legacyAdoptionEnabled);
            flyway.migrate();
            requireCurrentCoreShape(SchemaSnapshot.inspect(dataSource));
            return;
        }

        if (schema.packColumns().equals(CURRENT_PACK_COLUMNS)) {
            requireSafePackRows(dataSource, false);
            requireReflogRefNameCapacity(schema);
            log.info(
                    "Establishing Flyway history for an exact unversioned JGit Core schema on {}",
                    family.displayName());
            migrateNormalWithBaseline(
                    configuration,
                    family,
                    CoreSchemaMigrations.LEGACY_SCHEMA_VERSION,
                    CoreSchemaMigrations.LEGACY_BASELINE_DESCRIPTION);
            flyway.migrate();
            requireCurrentCoreShape(SchemaSnapshot.inspect(dataSource));
            return;
        }

        throw unsafeSchema(
                "git_packs is neither the exact pre-library Taxonomy shape nor the exact released "
                        + "Core shape. Existing columns: " + schema.packColumns());
    }

    private static void migrateLegacyTaxonomySchema(
            Configuration configuration,
            DatabaseFamily family,
            DataSource dataSource,
            SchemaSnapshot schema,
            boolean legacyAdoptionEnabled) {
        if (!legacyAdoptionEnabled) {
            throw unsafeSchema(
                    "Detected the pre-library Taxonomy git_packs schema. Stop all writers, take a "
                            + "restorable backup, verify pack BLOB checksums, then restart once with "
                            + "TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION=true.");
        }

        LegacyCoreSchemaAdoption.LegacySchemaReport report = requireSafePackRows(dataSource, true);
        widenLegacyReflogRefName(dataSource, family, schema);
        log.warn(
                "Adopting {} pre-library JGit pack rows on {}; the operator explicitly enabled "
                        + "the one-time migration",
                report.packRows(),
                family.displayName());

        newMigrationConfiguration(configuration)
                .locations(family.legacyAdoptionLocation())
                .table(CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
                .baselineDescription("before pre-library core adoption")
                .load()
                .migrate();

        migrateNormalWithBaseline(
                configuration,
                family,
                CoreSchemaMigrations.CURRENT_SCHEMA_VERSION,
                "adopted pre-library core schema");
    }

    private static void widenLegacyReflogRefName(
            DataSource dataSource, DatabaseFamily family, SchemaSnapshot schema) {
        int currentLength = schema.reflogRefNameLength();
        if (currentLength >= REQUIRED_REF_NAME_LENGTH) {
            return;
        }
        if (currentLength != LEGACY_TAXONOMY_REF_NAME_LENGTH) {
            throw unsafeSchema(
                    "git_reflog.ref_name has length " + currentLength
                            + "; only the exact legacy Taxonomy length "
                            + LEGACY_TAXONOMY_REF_NAME_LENGTH + " can be widened automatically.");
        }

        log.warn(
                "Widening legacy git_reflog.ref_name from {} to {} characters on {}",
                currentLength,
                REQUIRED_REF_NAME_LENGTH,
                family.displayName());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(family.widenRefNameSql());
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Could not widen legacy git_reflog.ref_name safely", exception);
        }

        requireReflogRefNameCapacity(SchemaSnapshot.inspect(dataSource));
    }

    private static LegacyCoreSchemaAdoption.LegacySchemaReport requireSafePackRows(
            DataSource dataSource, boolean requireAdoption) {
        try (Connection connection = dataSource.getConnection()) {
            LegacyCoreSchemaAdoption.LegacySchemaReport report =
                    LegacyCoreSchemaAdoption.requireSafeToAdopt(connection);
            if (report.requiresAdoption() != requireAdoption) {
                throw unsafeSchema(requireAdoption
                        ? "The schema changed while the legacy-adoption preflight was running."
                        : "The unversioned Core schema unexpectedly requires legacy adoption.");
            }
            return report;
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Could not open the database for JGit Core schema preflight", exception);
        }
    }

    private static void migrateNormalWithBaseline(
            Configuration source,
            DatabaseFamily family,
            String baselineVersion,
            String baselineDescription) {
        newMigrationConfiguration(source)
                .locations(family.normalLocation())
                .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion(baselineVersion)
                .baselineDescription(baselineDescription)
                .load()
                .migrate();
    }

    private static FluentConfiguration newMigrationConfiguration(Configuration source) {
        return new FluentConfiguration(source.getClassLoader())
                .dataSource(source.getDataSource());
    }

    private static void configureNormalMigration(
            FluentConfiguration configuration, DatabaseFamily family) {
        configuration
                .locations(family.normalLocation())
                .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);
    }

    private static void requireCurrentCoreShape(SchemaSnapshot schema) {
        if (!schema.hasTable("git_packs") || !schema.hasTable("git_reflog")) {
            throw unsafeSchema(
                    "The Core Flyway history exists but one or both owned tables are missing.");
        }
        requireExactColumns("git_packs", schema.packColumns(), CURRENT_PACK_COLUMNS);
        requireExactColumns("git_reflog", schema.reflogColumns(), REFLOG_COLUMNS);
        requireReflogRefNameCapacity(schema);
    }

    private static void requireReflogRefNameCapacity(SchemaSnapshot schema) {
        if (schema.reflogRefNameLength() < REQUIRED_REF_NAME_LENGTH) {
            throw unsafeSchema(
                    "git_reflog.ref_name supports only " + schema.reflogRefNameLength()
                            + " characters; the released Core contract requires at least "
                            + REQUIRED_REF_NAME_LENGTH + ".");
        }
    }

    private static void requireExactColumns(
            String table, Set<String> actual, Set<String> expected) {
        if (!actual.equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            Set<String> unexpected = new LinkedHashSet<>(actual);
            unexpected.removeAll(expected);
            throw unsafeSchema(
                    table + " does not match the supported schema; missing=" + missing
                            + ", unexpected=" + unexpected);
        }
    }

    private static IllegalStateException unsafeSchema(String message) {
        return new IllegalStateException("Unsafe JGit Core schema state: " + message);
    }

    enum DatabaseFamily {
        HSQLDB(
                "HSQLDB",
                CoreSchemaMigrations.HSQLDB_LOCATION,
                CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION,
                "alter table git_reflog alter column ref_name "
                        + "set data type varchar(1024)"),
        POSTGRESQL(
                "PostgreSQL",
                CoreSchemaMigrations.POSTGRESQL_LOCATION,
                CoreSchemaMigrations.POSTGRESQL_LEGACY_ADOPTION_LOCATION,
                "alter table git_reflog alter column ref_name type varchar(1024)");

        private final String displayName;
        private final String normalLocation;
        private final String legacyAdoptionLocation;
        private final String widenRefNameSql;

        DatabaseFamily(
                String displayName,
                String normalLocation,
                String legacyAdoptionLocation,
                String widenRefNameSql) {
            this.displayName = displayName;
            this.normalLocation = normalLocation;
            this.legacyAdoptionLocation = legacyAdoptionLocation;
            this.widenRefNameSql = widenRefNameSql;
        }

        String displayName() {
            return displayName;
        }

        String normalLocation() {
            return normalLocation;
        }

        String legacyAdoptionLocation() {
            return legacyAdoptionLocation;
        }

        String widenRefNameSql() {
            return widenRefNameSql;
        }

        static DatabaseFamily detect(DataSource dataSource) {
            try (Connection connection = dataSource.getConnection()) {
                String product = connection.getMetaData().getDatabaseProductName()
                        .toLowerCase(Locale.ROOT);
                if (product.contains("hsql")) {
                    return HSQLDB;
                }
                if (product.contains("postgresql")) {
                    return POSTGRESQL;
                }
                throw new IllegalStateException(
                        "JGit Core Flyway migrations are supported only for HSQLDB and PostgreSQL; "
                                + "detected " + connection.getMetaData().getDatabaseProductName());
            } catch (SQLException exception) {
                throw new IllegalStateException(
                        "Could not identify the database for JGit Core migrations", exception);
            }
        }
    }

    record SchemaSnapshot(
            Map<String, String> tables,
            Set<String> packColumns,
            Set<String> reflogColumns,
            int reflogRefNameLength) {

        boolean hasTable(String table) {
            return tables.containsKey(normalize(table));
        }

        static SchemaSnapshot inspect(DataSource dataSource) {
            try (Connection connection = dataSource.getConnection()) {
                String schema = connection.getSchema();
                DatabaseMetaData metadata = connection.getMetaData();
                Map<String, String> tables = readTables(metadata, schema);
                Set<String> packColumns = readColumns(metadata, schema, tables.get("GIT_PACKS"));
                Set<String> reflogColumns =
                        readColumns(metadata, schema, tables.get("GIT_REFLOG"));
                int refNameLength = readColumnSize(
                        metadata, schema, tables.get("GIT_REFLOG"), "REF_NAME");
                return new SchemaSnapshot(
                        Collections.unmodifiableMap(tables),
                        Collections.unmodifiableSet(packColumns),
                        Collections.unmodifiableSet(reflogColumns),
                        refNameLength);
            } catch (SQLException exception) {
                throw new IllegalStateException(
                        "Could not inspect the existing JGit Core schema", exception);
            }
        }

        private static Map<String, String> readTables(
                DatabaseMetaData metadata, String schema) throws SQLException {
            Map<String, String> tables = new LinkedHashMap<>();
            try (ResultSet resultSet =
                         metadata.getTables(null, schema, "%", new String[] {"TABLE"})) {
                while (resultSet.next()) {
                    String table = resultSet.getString("TABLE_NAME");
                    tables.put(normalize(table), table);
                }
            }
            return tables;
        }

        private static Set<String> readColumns(
                DatabaseMetaData metadata, String schema, String table) throws SQLException {
            if (table == null) {
                return new LinkedHashSet<>();
            }
            Set<String> columns = new LinkedHashSet<>();
            try (ResultSet resultSet = metadata.getColumns(null, schema, table, "%")) {
                while (resultSet.next()) {
                    columns.add(normalize(resultSet.getString("COLUMN_NAME")));
                }
            }
            return columns;
        }

        private static int readColumnSize(
                DatabaseMetaData metadata, String schema, String table, String column)
                throws SQLException {
            if (table == null) {
                return -1;
            }
            try (ResultSet resultSet = metadata.getColumns(null, schema, table, "%")) {
                while (resultSet.next()) {
                    if (normalize(resultSet.getString("COLUMN_NAME")).equals(column)) {
                        return resultSet.getInt("COLUMN_SIZE");
                    }
                }
            }
            return -1;
        }

        private static String normalize(String identifier) {
            return JgitStorageSchemaMigrationConfig.normalize(identifier);
        }
    }

    private static String normalize(String identifier) {
        return identifier.toUpperCase(Locale.ROOT);
    }
}
