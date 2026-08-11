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
 * <p>Each focused JDBC migrator owns one invariant family so repository,
 * workspace, hypothesis-session, proposal-review and commit-index rules can be
 * tested without a complete application context on every supported database.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "taxonomy.schema-migration.enabled",
        havingValue = "true", matchIfMissing = true)
public class SchemaContractMigration implements ApplicationRunner {

    private final OracleHypothesisSessionColumnMigrator oracleSessionMigrator;
    private final LegacyScopeIdentityNormalizer identityNormalizer;
    private final SchemaContractMigrator relationMigrator;
    private final ProposalReviewAuthoritySchemaMigrator proposalReviewMigrator;
    private final CommitIndexProjectionResetMigrator commitIndexResetMigrator;
    private final CommitIndexSchemaMigrator commitIndexMigrator;

    public SchemaContractMigration(DataSource dataSource) {
        this.oracleSessionMigrator =
                new OracleHypothesisSessionColumnMigrator(dataSource);
        this.identityNormalizer = new LegacyScopeIdentityNormalizer(dataSource);
        this.relationMigrator = new SchemaContractMigrator(dataSource);
        this.proposalReviewMigrator =
                new ProposalReviewAuthoritySchemaMigrator(dataSource);
        this.commitIndexResetMigrator =
                new CommitIndexProjectionResetMigrator(dataSource);
        this.commitIndexMigrator = new CommitIndexSchemaMigrator(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    /** Runs all idempotent portable contract migrations. */
    public void migrate() {
        oracleSessionMigrator.migrate();
        identityNormalizer.normalize();
        relationMigrator.migrate();
        proposalReviewMigrator.migrate();
        commitIndexResetMigrator.resetIfTargetContractIsIncomplete();
        commitIndexMigrator.migrate();
    }
}
