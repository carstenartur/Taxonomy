package com.taxonomy.backup.restore;

import com.taxonomy.backup.BackupRepositoryKey;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import java.io.IOException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RestoreGenerationContractTest {
    @Test void actualIsolatedFactoryAndExplicitAllocatedStorageAreRequired() throws Exception {
        var active = database(); var target = database();
        var sourceKey = new BackupRepositoryKey("source-repo", "source-workspace");
        var allocation = new RestoreRepositoryAllocation(new BackupRepositoryKey("target-repo", "target-workspace"), "allocated-physical-storage");
        try (var factory = factory(target)) {
            var generation = RestoreTargetGeneration.verified(UUID.randomUUID(), active, target, factory, Map.of(sourceKey, allocation), () -> {});
            generation.requireUnchanged(() -> {});
            assertThat(generation.sessionFactory()).isSameAs(factory);
            assertThat(generation.allocations()).containsExactlyEntriesOf(Map.of(sourceKey, allocation));
            assertThatThrownBy(() -> generation.allocations().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
        try (var activeFactory = factory(active)) {
            assertThatThrownBy(() -> RestoreTargetGeneration.verified(UUID.randomUUID(), active, target, activeFactory, Map.of(sourceKey, allocation), () -> {}))
                    .isInstanceOf(IOException.class).hasMessageContaining("factory");
        }
    }
    @Test void changedDataSourceBindingCannotReuseTheValidatedGeneration() throws Exception {
        var active = database(); var target = database(); var mutable = new DelegatingDataSource(target);
        try (var factory = factory(mutable)) {
            var generation = RestoreTargetGeneration.verified(UUID.randomUUID(), active, mutable, factory, Map.of(), () -> {});
            mutable.setTargetDataSource(active);
            assertThatThrownBy(() -> generation.requireUnchanged(() -> {})).isInstanceOf(IOException.class).hasMessageContaining("changed");
        }
    }
    @Test void storageNamesAreExplicitBoundedAndCannotBeArchivePaths() {
        var key = new BackupRepositoryKey("target-repo", null);
        for (String name : new String[]{"", "../archive", "/absolute", "CON", "spaces are not storage", "a".repeat(256)})
            assertThatThrownBy(() -> new RestoreRepositoryAllocation(key, name)).isInstanceOf(IllegalArgumentException.class);
    }
    private static JDBCDataSource database() {
        var database = new JDBCDataSource(); database.setURL("jdbc:hsqldb:mem:restore-generation-" + UUID.randomUUID()); database.setUser("sa"); return database;
    }
    private static org.hibernate.SessionFactory factory(javax.sql.DataSource database) {
        var configuration = new org.hibernate.cfg.Configuration();
        configuration.getProperties().put("hibernate.connection.datasource", database);
        configuration.setProperty("hibernate.hbm2ddl.auto", "none");
        return configuration.buildSessionFactory();
    }
}
