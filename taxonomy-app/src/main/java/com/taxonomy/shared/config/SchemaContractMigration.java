package com.taxonomy.shared.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Spring Boot entry point for the portable schema-contract migration.
 *
 * <p>The JDBC implementation lives in {@link SchemaContractMigrator} so the
 * repository, workspace and hypothesis-session rules can be tested without a
 * complete application context on every supported database.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "taxonomy.schema-migration.enabled",
        havingValue = "true", matchIfMissing = true)
public class SchemaContractMigration implements ApplicationRunner {

    private final SchemaContractMigrator migrator;

    public SchemaContractMigration(DataSource dataSource) {
        this.migrator = new SchemaContractMigrator(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    /** Runs the complete idempotent contract migration. */
    public void migrate() {
        migrator.migrate();
    }
}
