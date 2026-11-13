package com.taxonomy.composition.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryIndexRecoverySchemaTest {
    @Test
    void restoreCreatesARecoverableOperationAndAnIndependentDecisionJournal() throws Exception {
        var db = new DriverManagerDataSource("jdbc:hsqldb:mem:history-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
        var migration = new ClassPathResource("db/restore/hsqldb/V29__history_index_recovery.sql");
        assertThat(migration.exists()).as("retained history maintenance must survive restore").isTrue();
        new ResourceDatabasePopulator(migration).execute(db);
        var config = new org.hibernate.cfg.Configuration().setProperty("hibernate.hbm2ddl.auto", "validate")
                .setProperty("hibernate.search.enabled", "false")
                .addAnnotatedClass(com.taxonomy.versioning.service.HistoryIndexOperationRow.class)
                .addAnnotatedClass(com.taxonomy.versioning.service.HistoryIndexDecisionRow.class)
                .addAnnotatedClass(com.taxonomy.versioning.service.HistoryIndexPurgeTarget.class);
        config.getProperties().put("hibernate.connection.datasource", db);
        try (var factory = config.buildSessionFactory()) {
            assertThat(factory.getMetamodel().getEntities()).hasSize(3);
        }
        var projection = new JdbcTemplate(db);
        projection.execute("create table architecture_commit_index (id bigint primary key, repository_id varchar(255), workspace_id varchar(255))");
        com.taxonomy.versioning.service.HistoryIndexDatabaseContract.purgeTargets(db);
        com.taxonomy.versioning.service.HistoryIndexDatabaseContract.independentWorkers(db);
        var sql = new JdbcTemplate(db);
        assertThat(sql.queryForObject("select count(*) from history_index_operation", Long.class)).isZero();
        assertThat(sql.queryForObject("select count(*) from history_index_decision", Long.class)).isZero();
    }
}
