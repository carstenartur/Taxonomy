package com.taxonomy.shared.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Spring Boot entry point for portable schema-contract migrations.
 *
 * <p>The JDBC implementations remain focused so repository, workspace,
 * hypothesis-session and database-specific character-set rules can be tested
 * without a complete application context on every supported database.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "taxonomy.schema-migration.enabled",
        havingValue = "true", matchIfMissing = true)
public class SchemaContractMigration implements ApplicationRunner {

    private final OracleHypothesisSessionColumnMigrator oracleSessionMigrator;
    private final LegacyScopeIdentityNormalizer identityNormalizer;
    private final SchemaContractMigrator migrator;

    public SchemaContractMigration(DataSource dataSource) {
        this.oracleSessionMigrator =
                new OracleHypothesisSessionColumnMigrator(dataSource);
        this.identityNormalizer = new LegacyScopeIdentityNormalizer(dataSource);
        this.migrator = new SchemaContractMigrator(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    /** Runs the complete idempotent contract migration. */
    public void migrate() {
        oracleSessionMigrator.migrate();
        identityNormalizer.normalize();
        migrator.migrate();
    }
}
