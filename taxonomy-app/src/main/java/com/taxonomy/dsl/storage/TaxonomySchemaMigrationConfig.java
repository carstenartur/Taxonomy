package com.taxonomy.dsl.storage;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Runs Taxonomy's application schema migrations after the independently
 * versioned JGit Core schema and before Hibernate validates the persistence unit.
 *
 * <p>The two schemas deliberately use separate Flyway history tables. Existing
 * Taxonomy installations predate the application migration stream, so an exact
 * legacy application shape is baselined at version 1 and advanced to the
 * portfolio schema. A fresh database is baselined at version 0 and receives the
 * complete application baseline followed by all later migrations.</p>
 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true")
public class TaxonomySchemaMigrationConfig {

    static final String HISTORY_TABLE = "taxonomy_schema_history";
    static final String POSTGRES_LOCATION =
            "classpath:db/migration/taxonomy/postgresql";

    private static final Set<String> LEGACY_MARKERS = Set.of(
            "app_user",
            "architecture_dsl_document",
            "taxonomy_node",
            "taxonomy_relation");

    /** Every table introduced by the portfolio migration and mapped by JPA. */
    private static final Set<String> REQUIRED_PORTFOLIO_TABLES = Set.of(
            "arch_project",
            "project_requirement",
            "project_req_version",
            "req_analysis_job",
            "req_analysis_item",
            "req_analysis_snapshot",
            "req_element_mapping",
            "req_relation_mapping",
            "solution_definition",
            "solution_taxonomy",
            "project_solution",
            "req_solution_link",
            "product_catalog",
            "product_taxonomy",
            "solution_product",
            "project_conflict");

    /**
     * The primary Boot strategy composes the released JGit migration stream with
     * Taxonomy's own application stream. The existing JGit strategy remains
     * available for focused unit tests, while application startup selects this
     * primary strategy.
     */
    @Bean
    @Primary
    public FlywayMigrationStrategy taxonomyFlywayMigrationStrategy(
            @Value("${taxonomy.jgit-storage.legacy-adoption:false}")
            boolean legacyAdoptionEnabled) {
        return flyway -> {
            JgitStorageSchemaMigrationConfig.migrateCoreSchema(
                    flyway, legacyAdoptionEnabled);
            migrateApplicationSchema(flyway.getConfiguration());
        };
    }

    static void migrateApplicationSchema(Configuration source) {
        DataSource dataSource = source.getDataSource();
        if (!isPostgreSql(dataSource)) {
            // Local HSQLDB continues to use Hibernate create/update. The
            // production contract currently targets PostgreSQL explicitly.
            return;
        }

        SchemaState state = SchemaState.inspect(dataSource);
        FluentConfiguration configuration = new FluentConfiguration(source.getClassLoader())
                .dataSource(dataSource)
                .locations(POSTGRES_LOCATION)
                .table(HISTORY_TABLE);

        if (!state.hasHistory()) {
            LegacyState legacyState = classifyLegacyState(state.tables());
            configuration
                    .baselineOnMigrate(true)
                    .baselineVersion(legacyState == LegacyState.FRESH ? "0" : "1")
                    .baselineDescription(legacyState == LegacyState.FRESH
                            ? "before Taxonomy application schema"
                            : "verified pre-portfolio Taxonomy schema");
        }

        configuration.load().migrate();
        requireCurrentSchema(SchemaState.inspect(dataSource));
    }

    private static LegacyState classifyLegacyState(Set<String> tables) {
        long present = LEGACY_MARKERS.stream().filter(tables::contains).count();
        if (present == 0) {
            return LegacyState.FRESH;
        }
        if (present == LEGACY_MARKERS.size()) {
            return LegacyState.EXISTING;
        }
        Set<String> missing = new LinkedHashSet<>(LEGACY_MARKERS);
        missing.removeAll(tables);
        throw new IllegalStateException(
                "Unsafe partial Taxonomy application schema; missing legacy markers "
                        + missing + ". Restore a complete backup before migration.");
    }

    private static void requireCurrentSchema(SchemaState state) {
        Set<String> required = new LinkedHashSet<>(LEGACY_MARKERS);
        required.addAll(REQUIRED_PORTFOLIO_TABLES);
        required.add(HISTORY_TABLE);
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(state.tables());
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Taxonomy application migration completed without required tables: "
                            + missing);
        }
    }

    private static boolean isPostgreSql(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName()
                    .toLowerCase(Locale.ROOT)
                    .contains("postgresql");
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Could not identify the database for Taxonomy schema migrations",
                    exception);
        }
    }

    private enum LegacyState {
        FRESH,
        EXISTING
    }

    private record SchemaState(Set<String> tables) {

        boolean hasHistory() {
            return tables.contains(HISTORY_TABLE);
        }

        static SchemaState inspect(DataSource dataSource) {
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metadata = connection.getMetaData();
                String schema = connection.getSchema();
                Set<String> tables = new LinkedHashSet<>();
                try (ResultSet resultSet = metadata.getTables(
                        connection.getCatalog(), schema, "%", new String[] {"TABLE"})) {
                    while (resultSet.next()) {
                        tables.add(resultSet.getString("TABLE_NAME")
                                .toLowerCase(Locale.ROOT));
                    }
                }
                return new SchemaState(Set.copyOf(tables));
            } catch (SQLException exception) {
                throw new IllegalStateException(
                        "Could not inspect the Taxonomy application schema",
                        exception);
            }
        }
    }
}
