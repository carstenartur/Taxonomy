package com.taxonomy.dsl.storage;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.schema.LegacyCoreSchemaAdoption;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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

    private static final int REQUIRED_PACK_EXTENSION_LENGTH = 32;
    private static final int LEGACY_TAXONOMY_PACK_EXTENSION_LENGTH = 255;
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
            requireCurrentCoreColumnLengths(schema);
            requireCurrentCoreIndexes(schema);
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
        requireLegacyCoreIndexes(schema);
        LegacyColumnMigrationPlan columnPlan =
                requireSafeLegacyColumnMigration(dataSource, schema);
        applyLegacyColumnMigration(dataSource, family, columnPlan);
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

    private static LegacyColumnMigrationPlan requireSafeLegacyColumnMigration(
            DataSource dataSource, SchemaSnapshot schema) {
        boolean narrowPackExtension;
        if (schema.packExtensionLength() == REQUIRED_PACK_EXTENSION_LENGTH) {
            narrowPackExtension = false;
        } else if (schema.packExtensionLength()
                == LEGACY_TAXONOMY_PACK_EXTENSION_LENGTH) {
            long oversizedValues = countOversizedPackExtensions(dataSource);
            if (oversizedValues > 0) {
                throw unsafeSchema(
                        "git_packs contains " + oversizedValues
                                + " pack_extension values longer than "
                                + REQUIRED_PACK_EXTENSION_LENGTH
                                + " characters; resolve them explicitly before adoption.");
            }
            narrowPackExtension = true;
        } else {
            throw unsafeSchema(
                    "git_packs.pack_extension has length " + schema.packExtensionLength()
                            + "; only the released Core length "
                            + REQUIRED_PACK_EXTENSION_LENGTH
                            + " or exact legacy Taxonomy length "
                            + LEGACY_TAXONOMY_PACK_EXTENSION_LENGTH
                            + " is supported.");
        }

        boolean widenRefName;
        if (schema.reflogRefNameLength() >= REQUIRED_REF_NAME_LENGTH) {
            widenRefName = false;
        } else if (schema.reflogRefNameLength()
                == LEGACY_TAXONOMY_REF_NAME_LENGTH) {
            widenRefName = true;
        } else {
            throw unsafeSchema(
                    "git_reflog.ref_name has length " + schema.reflogRefNameLength()
                            + "; only the released Core capacity or exact legacy Taxonomy length "
                            + LEGACY_TAXONOMY_REF_NAME_LENGTH
                            + " is supported.");
        }

        return new LegacyColumnMigrationPlan(narrowPackExtension, widenRefName);
    }

    private static long countOversizedPackExtensions(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select count(*) from git_packs where "
                             + "character_length(pack_extension) > "
                             + REQUIRED_PACK_EXTENSION_LENGTH)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Could not validate legacy pack_extension values", exception);
        }
    }

    private static void applyLegacyColumnMigration(
            DataSource dataSource,
            DatabaseFamily family,
            LegacyColumnMigrationPlan plan) {
        if (!plan.requiresDdl()) {
            return;
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                if (plan.narrowPackExtension()) {
                    log.warn(
                            "Narrowing legacy git_packs.pack_extension from {} to {} characters on {}",
                            LEGACY_TAXONOMY_PACK_EXTENSION_LENGTH,
                            REQUIRED_PACK_EXTENSION_LENGTH,
                            family.displayName());
                    statement.execute(family.narrowPackExtensionSql());
                }
                if (plan.widenRefName()) {
                    log.warn(
                            "Widening legacy git_reflog.ref_name from {} to {} characters on {}",
                            LEGACY_TAXONOMY_REF_NAME_LENGTH,
                            REQUIRED_REF_NAME_LENGTH,
                            family.displayName());
                    statement.execute(family.widenRefNameSql());
                }
                connection.commit();
            } catch (SQLException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Could not normalize legacy JGit Core column lengths safely", exception);
        }

        requireCurrentCoreColumnLengths(SchemaSnapshot.inspect(dataSource));
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
        requireCurrentCoreColumnLengths(schema);
        requireCurrentCoreIndexes(schema);
    }

    private static void requireCurrentCoreColumnLengths(SchemaSnapshot schema) {
        if (schema.packExtensionLength() != REQUIRED_PACK_EXTENSION_LENGTH) {
            throw unsafeSchema(
                    "git_packs.pack_extension has length " + schema.packExtensionLength()
                            + "; the released Core contract requires exactly "
                            + REQUIRED_PACK_EXTENSION_LENGTH + ".");
        }
        if (schema.reflogRefNameLength() < REQUIRED_REF_NAME_LENGTH) {
            throw unsafeSchema(
                    "git_reflog.ref_name supports only " + schema.reflogRefNameLength()
                            + " characters; the released Core contract requires at least "
                            + REQUIRED_REF_NAME_LENGTH + ".");
        }
    }

    private static void requireLegacyCoreIndexes(SchemaSnapshot schema) {
        requireIndex(
                "git_packs",
                schema.packIndexes(),
                false,
                List.of("REPOSITORY_NAME"));
        requireIndex(
                "git_packs",
                schema.packIndexes(),
                false,
                List.of("REPOSITORY_NAME", "PACK_NAME"));
        requireIndex(
                "git_reflog",
                schema.reflogIndexes(),
                false,
                List.of("REPOSITORY_NAME"));
        requireIndex(
                "git_reflog",
                schema.reflogIndexes(),
                false,
                List.of("REPOSITORY_NAME", "REF_NAME"));
    }

    private static void requireCurrentCoreIndexes(SchemaSnapshot schema) {
        requireLegacyCoreIndexes(schema);
        requireIndex(
                "git_packs",
                schema.packIndexes(),
                false,
                List.of("REPOSITORY_NAME", "COMMITTED"));
        requireIndex(
                "git_packs",
                schema.packIndexes(),
                true,
                List.of("REPOSITORY_NAME", "PACK_NAME", "PACK_EXTENSION"));
    }

    private static void requireIndex(
            String table,
            Map<String, IndexSignature> indexes,
            boolean unique,
            List<String> columns) {
        IndexSignature required = new IndexSignature(unique, columns);
        if (indexes.containsValue(required)) {
            return;
        }
        throw unsafeSchema(
                table + " is missing the required "
                        + (unique ? "unique " : "")
                        + "index on " + columns + "; actual indexes=" + indexes);
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

    private record LegacyColumnMigrationPlan(
            boolean narrowPackExtension,
            boolean widenRefName) {

        boolean requiresDdl() {
            return narrowPackExtension || widenRefName;
        }
    }

    private record IndexSignature(boolean unique, List<String> columns) {

        private IndexSignature {
            columns = List.copyOf(columns);
        }
    }

    enum DatabaseFamily {
        HSQLDB(
                "HSQLDB",
                CoreSchemaMigrations.HSQLDB_LOCATION,
                CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION,
                "alter table git_packs alter column pack_extension "
                        + "set data type varchar(32)",
                "alter table git_reflog alter column ref_name "
                        + "set data type varchar(1024)"),
        POSTGRESQL(
                "PostgreSQL",
                CoreSchemaMigrations.POSTGRESQL_LOCATION,
                CoreSchemaMigrations.POSTGRESQL_LEGACY_ADOPTION_LOCATION,
                "alter table git_packs alter column pack_extension type varchar(32)",
                "alter table git_reflog alter column ref_name type varchar(1024)");

        private final String displayName;
        private final String normalLocation;
        private final String legacyAdoptionLocation;
        private final String narrowPackExtensionSql;
        private final String widenRefNameSql;

        DatabaseFamily(
                String displayName,
                String normalLocation,
                String legacyAdoptionLocation,
                String narrowPackExtensionSql,
                String widenRefNameSql) {
            this.displayName = displayName;
            this.normalLocation = normalLocation;
            this.legacyAdoptionLocation = legacyAdoptionLocation;
            this.narrowPackExtensionSql = narrowPackExtensionSql;
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

        String narrowPackExtensionSql() {
            return narrowPackExtensionSql;
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
            int packExtensionLength,
            int reflogRefNameLength,
            Map<String, IndexSignature> packIndexes,
            Map<String, IndexSignature> reflogIndexes) {

        boolean hasTable(String table) {
            return tables.containsKey(normalize(table));
        }

        static SchemaSnapshot inspect(DataSource dataSource) {
            try (Connection connection = dataSource.getConnection()) {
                String schema = connection.getSchema();
                DatabaseMetaData metadata = connection.getMetaData();
                Map<String, String> tables = readTables(metadata, schema);
                String packTable = tables.get("GIT_PACKS");
                String reflogTable = tables.get("GIT_REFLOG");
                Set<String> packColumns = readColumns(metadata, schema, packTable);
                Set<String> reflogColumns = readColumns(metadata, schema, reflogTable);
                int packExtensionLength = readColumnSize(
                        metadata, schema, packTable, "PACK_EXTENSION");
                int refNameLength = readColumnSize(
                        metadata, schema, reflogTable, "REF_NAME");
                Map<String, IndexSignature> packIndexes =
                        readIndexes(metadata, schema, packTable);
                Map<String, IndexSignature> reflogIndexes =
                        readIndexes(metadata, schema, reflogTable);
                return new SchemaSnapshot(
                        Collections.unmodifiableMap(tables),
                        Collections.unmodifiableSet(packColumns),
                        Collections.unmodifiableSet(reflogColumns),
                        packExtensionLength,
                        refNameLength,
                        Collections.unmodifiableMap(packIndexes),
                        Collections.unmodifiableMap(reflogIndexes));
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

        private static Map<String, IndexSignature> readIndexes(
                DatabaseMetaData metadata, String schema, String table) throws SQLException {
            if (table == null) {
                return new LinkedHashMap<>();
            }

            Map<String, Boolean> uniqueness = new LinkedHashMap<>();
            Map<String, Map<Integer, String>> columnsByIndex = new LinkedHashMap<>();
            try (ResultSet resultSet =
                         metadata.getIndexInfo(null, schema, table, false, false)) {
                while (resultSet.next()) {
                    String indexName = resultSet.getString("INDEX_NAME");
                    String columnName = resultSet.getString("COLUMN_NAME");
                    int position = resultSet.getInt("ORDINAL_POSITION");
                    if (indexName == null || columnName == null || position <= 0) {
                        continue;
                    }
                    String normalizedName = normalize(indexName);
                    uniqueness.putIfAbsent(
                            normalizedName, !resultSet.getBoolean("NON_UNIQUE"));
                    columnsByIndex
                            .computeIfAbsent(normalizedName, ignored -> new TreeMap<>())
                            .put(position, normalize(columnName));
                }
            }

            Map<String, IndexSignature> indexes = new LinkedHashMap<>();
            for (Map.Entry<String, Map<Integer, String>> entry :
                    columnsByIndex.entrySet()) {
                List<String> columns = new ArrayList<>(entry.getValue().values());
                indexes.put(
                        entry.getKey(),
                        new IndexSignature(
                                Boolean.TRUE.equals(uniqueness.get(entry.getKey())),
                                columns));
            }
            return indexes;
        }

        private static String normalize(String identifier) {
            return JgitStorageSchemaMigrationConfig.normalize(identifier);
        }
    }

    private static String normalize(String identifier) {
        return identifier.toUpperCase(Locale.ROOT);
    }
}
