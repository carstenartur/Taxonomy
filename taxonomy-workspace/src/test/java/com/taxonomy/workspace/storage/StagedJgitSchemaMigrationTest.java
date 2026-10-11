package com.taxonomy.workspace.storage;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StagedJgitSchemaMigrationTest {
    @Test void validatedStagedFactoryCanWriteAndReadOriginalGitObjectsThroughThePublicOwner() throws Exception {
        var stage = database();
        StagedJgitSchemaMigration.migrate(stage, () -> {});
        var configuration = new org.hibernate.cfg.Configuration();
        configuration.getProperties().put("hibernate.connection.datasource", stage);
        configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        configuration.setProperty("hibernate.search.enabled", "false");
        StagedJgitSchemaMigration.configureModel(configuration);
        try (var factory = configuration.buildSessionFactory();
             var repository = new DslGitRepository(new io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory(factory), "staged-explicit-repository")) {
            String original = repository.commitDsl("restored", "ORIGINAL BYTES", "restore-fixture", "original commit");
            assertThat(repository.getDslAtCommit(original)).isEqualTo("ORIGINAL BYTES");
            assertThat(repository.getGitRepository().exactRef("refs/heads/restored").getObjectId().name()).isEqualTo(original);
        }
        assertThat(count(stage, "git_packs")).isGreaterThan(0);
    }
    @Test void versionedBootstrapTouchesOnlyExplicitStageAndDoesNotOpenAnyRepository() throws Exception {
        var active = database(); var stage = database();
        try (var c = active.getConnection(); var statement = c.createStatement()) {
            statement.execute("create table active_marker(id integer)"); statement.execute("insert into active_marker values (42)");
        }
        StagedJgitSchemaMigration.migrate(stage, () -> {});
        for (String table : List.of("git_packs", "git_reflog", "git_repository_lifecycle", "git_repository_lock"))
            assertThat(count(stage, table)).as(table + " is schema only, with no repository creation").isZero();
        long versions = count(stage, "\"" + io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations.SCHEMA_HISTORY_TABLE + "\"");
        StagedJgitSchemaMigration.migrate(stage, () -> {});
        assertThat(count(stage, "\"" + io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations.SCHEMA_HISTORY_TABLE + "\"")).isEqualTo(versions);
        assertThat(count(active, "active_marker")).isEqualTo(1);
        try (var c = active.getConnection(); var tables = c.getMetaData().getTables(c.getCatalog(), c.getSchema(), "GIT_%", new String[]{"TABLE"})) {
            assertThat(tables.next()).isFalse();
        }
    }
    @Test void partialLegacyTargetAndCancellationFailBeforeRepositoryCreation() throws Exception {
        var partial = database();
        try (var c = partial.getConnection(); var statement = c.createStatement()) { statement.execute("create table git_packs(id integer)"); }
        assertThatThrownBy(() -> StagedJgitSchemaMigration.migrate(partial, () -> {})).isInstanceOf(IOException.class)
                .hasMessage("Staged Git schema migration failed");
        var cancelled = database();
        try {
            assertThatThrownBy(() -> StagedJgitSchemaMigration.migrate(cancelled, () -> { throw new java.io.InterruptedIOException("private source"); }))
                    .isInstanceOf(java.io.InterruptedIOException.class).hasMessage("Staged Git schema migration was cancelled");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        try (var c = cancelled.getConnection(); var tables = c.getMetaData().getTables(c.getCatalog(), c.getSchema(), "%", new String[]{"TABLE"})) {
            assertThat(tables.next()).isFalse();
        }
    }
    private static long count(javax.sql.DataSource data, String table) throws Exception {
        try (var c = data.getConnection(); var result = c.createStatement().executeQuery("select count(*) from " + table)) { result.next(); return result.getLong(1); }
    }
    private static JDBCDataSource database() {
        var database = new JDBCDataSource(); database.setURL("jdbc:hsqldb:mem:staged-jgit-" + UUID.randomUUID()); database.setUser("sa"); return database;
    }
}
