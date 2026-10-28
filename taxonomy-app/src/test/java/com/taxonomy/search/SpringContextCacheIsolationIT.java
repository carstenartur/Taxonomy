package com.taxonomy.search;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.cache.DefaultContextCache;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the integration-test JVM's actual cache policy, not a hand-configured cache. */
class SpringContextCacheIsolationIT {

    @Test
    void closesOldDatabasePoolBeforeTheNextContextCanRebuildItsSchema() {
        // Do not pass a cache size here: this must detect missing Failsafe configuration.
        var cache = new DefaultContextCache();
        var firstKey = key("first");
        var secondKey = key("second");
        String url = "jdbc:hsqldb:mem:context-isolation-" + UUID.randomUUID() + ";shutdown=true";
        try {
            var first = (GenericApplicationContext) cache.put(firstKey, ignored -> databaseContext(url));
            var firstPool = first.getBean(HikariDataSource.class);
            assertThat(first.isActive()).isTrue();
            assertThat(firstPool.isClosed()).isFalse();
            cache.registerContextUsage(firstKey, getClass());
            cache.unregisterContextUsage(firstKey, getClass());

            var second = (GenericApplicationContext) cache.put(secondKey, ignored -> {
                // A paused, still-cached context fails these checks. Its live resources
                // must not overlap with the next context's schema/bootstrap operations.
                assertThat(first.isActive()).as("previous context closed before loading its replacement").isFalse();
                assertThat(firstPool.isClosed()).as("previous JDBC pool disposed before schema recreation").isTrue();
                return databaseContext(url);
            });
            assertThat(second.isActive()).isTrue();
            assertThat(second.getBean(HikariDataSource.class).isClosed()).isFalse();
        } finally {
            cache.remove(secondKey, HierarchyMode.CURRENT_LEVEL);
            cache.remove(firstKey, HierarchyMode.CURRENT_LEVEL);
        }
    }

    private static MergedContextConfiguration key(String profile) {
        return new MergedContextConfiguration(SpringContextCacheIsolationIT.class,
                null, null, new String[] {profile}, new AnnotationConfigContextLoader());
    }

    private static GenericApplicationContext databaseContext(String url) {
        var context = new GenericApplicationContext();
        context.registerBean("dataSource", HikariDataSource.class, () -> {
            var pool = new HikariDataSource();
            pool.setJdbcUrl(url);
            pool.setUsername("sa");
            pool.setPassword("");
            pool.setMinimumIdle(1);
            pool.setMaximumPoolSize(1);
            return pool;
        }, definition -> definition.setDestroyMethodName("close"));
        try {
            context.refresh();
            try (var connection = context.getBean(HikariDataSource.class).getConnection();
                 var statement = connection.createStatement()) {
                // The same table can be created by both contexts only after the last
                // connection to the first isolated in-memory database has been closed.
                statement.execute("create table context_isolation_probe (id integer primary key)");
                statement.executeUpdate("insert into context_isolation_probe values (1)");
                try (var result = statement.executeQuery("select count(*) from context_isolation_probe")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
            }
            return context;
        } catch (SQLException failure) {
            context.close();
            throw new IllegalStateException("Isolated test database could not be initialized", failure);
        } catch (RuntimeException | Error failure) {
            context.close();
            throw failure;
        }
    }
}
