package com.taxonomytest.search;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.cfg.Configuration;
import org.hibernate.search.engine.backend.document.IndexFieldReference;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.pojo.bridge.binding.TypeBindingContext;
import org.hibernate.search.mapper.pojo.bridge.mapping.annotation.TypeBinderRef;
import org.hibernate.search.mapper.pojo.bridge.mapping.programmatic.TypeBinder;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.TypeBinding;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Real pool, database and mass-indexer regression. Only the slow embedding work
 * is replaced with a latch; no model download or external LLM call is made.
 * The command-line entry point runs the same scenario as the JUnit wrapper.
 */
public final class EmbeddingConnectionBudgetScenario {
    private static final int ROWS = 4096;
    private static volatile Gate gate;

    private EmbeddingConnectionBudgetScenario() { }

    public static void verify(String profile) throws Exception {
        Path directory = Files.createTempDirectory("embedding-pool-budget-");
        try {
            var sources = new MutablePropertySources();
            Map<String, Object> overrides = profile.equals("hsqldb-file")
                    ? Map.of("TAXONOMY_HSQLDB_FILE_PATH", directory.resolve("database").toString())
                    : Map.of("TAXONOMY_DATASOURCE_URL", "jdbc:hsqldb:mem:embedding-budget-" + UUID.randomUUID() + ";shutdown=true");
            sources.addLast(new MapPropertySource("isolated-database", overrides));
            sources.addLast(new PropertiesPropertySource("profile", properties("application-" + profile + ".properties")));
            sources.addLast(new PropertiesPropertySource("application", properties("application.properties")));
            var settings = new PropertySourcesPropertyResolver(sources);
            int loaders = settings.getRequiredProperty("embedding.index.loader-threads", Integer.class);
            int batch = settings.getRequiredProperty("embedding.index.batch-size", Integer.class);

            try (var pool = new HikariDataSource()) {
                pool.setJdbcUrl(settings.getRequiredProperty("spring.datasource.url"));
                pool.setUsername(settings.getRequiredProperty("spring.datasource.username"));
                pool.setPassword(settings.getProperty("spring.datasource.password", ""));
                pool.setMinimumIdle(settings.getRequiredProperty("spring.datasource.hikari.minimum-idle", Integer.class));
                pool.setMaximumPoolSize(settings.getRequiredProperty("spring.datasource.hikari.maximum-pool-size", Integer.class));
                // Bound a failing regression; the application's 30-second deadline is unchanged.
                pool.setConnectionTimeout(1000);
                pool.setPoolName("embedding-budget-" + profile);
                var configuration = new Configuration().addAnnotatedClass(IndexRow.class)
                        .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                        .setProperty("hibernate.search.backend.type", "lucene")
                        .setProperty("hibernate.search.backend.directory.type", "local-heap")
                        .setProperty("hibernate.search.backend.lucene_version", "9.12.3")
                        .setProperty("hibernate.search.indexing.plan.synchronization.strategy", "sync");
                configuration.getProperties().put("hibernate.connection.datasource", pool);
                try (var factory = configuration.buildSessionFactory()) {
                    try (var manager = factory.createEntityManager()) {
                        manager.getTransaction().begin();
                        for (long id = 1; id <= ROWS; id++) manager.persist(new IndexRow(id));
                        manager.getTransaction().commit();
                    }
                    try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                        statement.execute("create table foreground_probe (id integer primary key)");
                    }
                    Gate active = new Gate(loaders);
                    gate = active;
                    CompletableFuture<?> rebuilding = null;
                    try {
                        rebuilding = Search.mapping(factory).scope(IndexRow.class).massIndexer()
                                .typesToIndexInParallel(1).threadsToLoadObjects(loaders)
                                .batchSizeToLoadObjects(batch).start().toCompletableFuture();
                        if (!active.entered.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("Mass-indexer workers did not reach the inference boundary");
                        }
                        if (pool.getHikariPoolMXBean().getActiveConnections() < loaders) {
                            throw new AssertionError("The regression must hold real indexing connections");
                        }
                        // A foreground transaction, a nested operation and a separate storage
                        // transaction must work while the indexer retains its own connections.
                        try (var outer = pool.getConnection(); var nested = pool.getConnection();
                             var storage = pool.getConnection()) {
                            outer.setAutoCommit(false);
                            nested.setAutoCommit(false);
                            try (var read = outer.createStatement(); var result = read.executeQuery("values (1)")) {
                                if (!result.next() || result.getInt(1) != 1) throw new AssertionError("Outer read failed");
                            }
                            try (var read = nested.createStatement(); var result = read.executeQuery("values (2)")) {
                                if (!result.next() || result.getInt(1) != 2) throw new AssertionError("Nested read failed");
                            }
                            try (var write = storage.createStatement()) {
                                if (write.executeUpdate("insert into foreground_probe values (1)") != 1) {
                                    throw new AssertionError("Foreground storage write failed");
                                }
                            }
                            nested.commit();
                            outer.commit();
                        }
                    } finally {
                        active.release.countDown();
                        try {
                            if (rebuilding != null) rebuilding.get(30, TimeUnit.SECONDS);
                        } finally {
                            gate = null;
                        }
                    }
                    try (var manager = factory.createEntityManager()) {
                        long indexed = Search.session(manager).search(IndexRow.class)
                                .where(f -> f.match().field("slowMarker").matching("ready")).fetchTotalHitCount();
                        if (indexed != ROWS) throw new AssertionError("Incomplete index: " + indexed + " of " + ROWS);
                    }
                    try (var connection = pool.getConnection(); var statement = connection.createStatement();
                         var rows = statement.executeQuery("select count(*) from foreground_probe")) {
                        if (!rows.next() || rows.getInt(1) != 1) throw new AssertionError("Foreground write was not retained");
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static Properties properties(String name) throws Exception {
        var values = new Properties();
        try (var input = EmbeddingConnectionBudgetScenario.class.getResourceAsStream("/" + name)) {
            if (input == null) throw new IllegalStateException("Missing production configuration: " + name);
            values.load(input);
        }
        return values;
    }

    private record Gate(CountDownLatch entered, CountDownLatch release) {
        Gate(int workers) { this(new CountDownLatch(workers), new CountDownLatch(1)); }
    }

    @Entity(name = "EmbeddingBudgetRow")
    @Indexed
    @TypeBinding(binder = @TypeBinderRef(type = SlowBinder.class))
    public static class IndexRow {
        @Id private Long id;
        @KeywordField private String label;
        public IndexRow() { }
        IndexRow(long id) { this.id = id; this.label = "fixture-" + id; }
    }

    public static class SlowBinder implements TypeBinder {
        @Override public void bind(TypeBindingContext context) {
            context.dependencies().use("label");
            IndexFieldReference<String> marker = context.indexSchemaElement()
                    .field("slowMarker", f -> f.asString()).toReference();
            context.bridge(IndexRow.class, (target, entity, writeContext) -> {
                Gate active = gate;
                if (active != null) {
                    active.entered.countDown();
                    try {
                        if (!active.release.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("The test did not release slow indexing work");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Indexing interrupted", interrupted);
                    }
                }
                target.addValue(marker, "ready");
            });
        }
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected one database profile");
        verify(arguments[0]);
        System.out.println("PASS: " + arguments[0] + " foreground storage and " + ROWS + " indexed documents");
    }
}
