package com.taxonomy.versioning.service;

import com.taxonomy.versioning.model.ArchitectureCommitIndex;
import com.taxonomy.versioning.repository.ArchitectureCommitIndexRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.search.backend.lucene.analysis.LuceneAnalysisConfigurationContext;
import org.hibernate.search.backend.lucene.analysis.LuceneAnalysisConfigurer;
import org.hibernate.search.mapper.orm.Search;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Real database and Lucene restarts; equal document counts alone must not authorize reuse. */
class CommitIndexSearchRestartTest {
    @TempDir Path directory;
    private static final RepositoryContext ALICE = RepositoryContext.workspace("repo-a", "alice-copy", "draft", "alice");
    private static final RepositoryContext BOB = RepositoryContext.workspace("repo-a", "bob-copy", "draft", "bob");

    @Test
    void rebuildsPersistedHistoryAfterTheHeapIndexIsLostOnRestart() {
        try (var first = open("create", "local-heap")) {
            first.insert(ALICE, "a".repeat(40), "medical");
            first.insert(BOB, "b".repeat(40), "logistics");
        }
        try (var restarted = open("validate", "local-heap")) {
            assertThat(restarted.count()).isZero();
            restarted.initialize();
            assertThat(restarted.search(ALICE, "medical")).isEqualTo(1);
            assertThat(restarted.search(BOB, "logistics")).isEqualTo(1);
            assertThat(restarted.search(ALICE, "logistics")).isZero();
            assertThat(restarted.rebuilder.rebuilds).isOne();
        }
    }

    @Test
    void reusesACompleteFilesystemIndexAfterRestart() {
        try (var first = open("create", "local-filesystem")) {
            first.insert(ALICE, "a".repeat(40), "medical", Instant.parse("2026-11-22T10:00:00.123456789Z"));
            first.insert(BOB, "b".repeat(40), "logistics");
        }
        try (var restarted = open("validate", "local-filesystem")) {
            restarted.initialize();
            assertThat(restarted.search(ALICE, "medical")).isEqualTo(1);
            assertThat(restarted.search(BOB, "logistics")).isEqualTo(1);
            assertThat(restarted.rebuilder.rebuilds).isZero();
        }
    }

    @Test
    void repairsStaleSearchTextEvenWhenDocumentIdsAndCountsMatch() {
        try (var first = open("create", "local-filesystem")) {
            first.insert(ALICE, "a".repeat(40), "outdated");
            first.sql("update architecture_commit_index set message='current', tokenized_change_text='current'");
        }
        try (var restarted = open("validate", "local-filesystem")) {
            assertThat(restarted.count()).isEqualTo(1);
            restarted.initialize();
            assertThat(restarted.search(ALICE, "current")).isEqualTo(1);
            assertThat(restarted.search(ALICE, "outdated")).isZero();
            assertThat(restarted.rebuilder.rebuilds).isOne();
        }
    }

    @Test
    void repairsTenantRoutingEvenWhenDocumentIdsAndCountsMatch() {
        try (var first = open("create", "local-filesystem")) {
            first.insert(ALICE, "a".repeat(40), "medical");
            first.sql("update architecture_commit_index set workspace_id='bob-copy', workspace_scope_key='bob-copy'");
        }
        try (var restarted = open("validate", "local-filesystem")) {
            assertThat(restarted.count()).isEqualTo(1);
            restarted.initialize();
            assertThat(restarted.search(ALICE, "medical")).isZero();
            assertThat(restarted.search(BOB, "medical")).isEqualTo(1);
            assertThat(restarted.rebuilder.rebuilds).isOne();
        }
    }

    @Test
    void aSearchableHeadDoesNotHideAMissingOlderHistoryDocument() {
        try (var first = open("create", "local-filesystem")) {
            long older = first.insert(ALICE, "a".repeat(40), "medical");
            first.insert(ALICE, "b".repeat(40), "logistics");
            try (var manager = first.factory.createEntityManager()) {
                manager.getTransaction().begin();
                Search.session(manager).indexingPlan().purge(ArchitectureCommitIndex.class, older, null);
                manager.getTransaction().commit();
            }
        }
        try (var restarted = open("validate", "local-filesystem")) {
            assertThat(restarted.count()).isEqualTo(1);
            restarted.initialize();
            assertThat(restarted.search(ALICE, "medical")).isEqualTo(1);
            assertThat(restarted.search(ALICE, "logistics")).isEqualTo(1);
            assertThat(restarted.rebuilder.rebuilds).isOne();
        }
    }

    @Test
    void repairsDifferentDocumentIdsEvenWhenCountsAndSearchableTextMatch() {
        long original;
        try (var first = open("create", "local-filesystem")) {
            original = first.insert(ALICE, "a".repeat(40), "medical");
            first.sql("update architecture_commit_index set id=id+100");
        }
        try (var restarted = open("validate", "local-filesystem")) {
            assertThat(restarted.count()).isEqualTo(1);
            restarted.initialize();
            try (var manager = restarted.factory.createEntityManager()) {
                assertThat(Search.session(manager).search(ArchitectureCommitIndex.class)
                        .select(f -> f.id(Long.class)).where(f -> f.matchAll()).fetchHits(10))
                        .containsExactly(original + 100);
            }
            assertThat(restarted.rebuilder.rebuilds).isOne();
        }
    }

    @Test
    void repairsRestoredCommitTimestampsBeforeReusingChronologicalOrder() {
        long firstId;
        long secondId;
        try (var first = open("create", "local-filesystem")) {
            firstId = first.insert(ALICE, "a".repeat(40), "medical", Instant.parse("2026-11-22T10:00:00Z"));
            secondId = first.insert(ALICE, "b".repeat(40), "medical", Instant.parse("2026-11-22T11:00:00Z"));
            first.sql("update architecture_commit_index set commit_timestamp=timestamp '2026-11-22 12:00:00' where id=" + firstId);
        }
        try (var restarted = open("validate", "local-filesystem")) {
            restarted.initialize();
            try (var manager = restarted.factory.createEntityManager()) {
                assertThat(Search.session(manager).search(ArchitectureCommitIndex.class)
                        .select(f -> f.id(Long.class)).where(f -> f.matchAll())
                        .sort(f -> f.field("commitTimestamp").asc()).fetchHits(10))
                        .containsExactly(secondId, firstId);
            }
            assertThat(restarted.rebuilder.rebuilds).isOne();
        }
    }

    @Test
    void purgesOrphanDocumentsAfterDatabaseRowsWereRemovedOutsideOrm() {
        try (var first = open("create", "local-filesystem")) {
            first.insert(ALICE, "a".repeat(40), "medical");
            first.sql("delete from architecture_commit_index");
        }
        try (var restarted = open("validate", "local-filesystem")) {
            assertThat(restarted.count()).isEqualTo(1);
            restarted.initialize();
            assertThat(restarted.count()).isZero();
            assertThat(restarted.rebuilder.rebuilds).isOne();
        }
    }

    @Test
    void anEmptyDatabaseAndIndexNeedNoRebuild() {
        try (var empty = open("create", "local-heap")) {
            empty.initialize();
            assertThat(empty.count()).isZero();
            assertThat(empty.rebuilder.rebuilds).isZero();
        }
    }

    private Database open(String ddl, String directoryType) {
        var configuration = new Configuration().addAnnotatedClass(ArchitectureCommitIndex.class)
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", "jdbc:hsqldb:file:" + directory.resolve("history") + ";shutdown=true")
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", ddl)
                .setProperty("hibernate.search.backend.type", "lucene")
                .setProperty("hibernate.search.backend.directory.type", directoryType)
                .setProperty("hibernate.search.backend.directory.root", directory.resolve("lucene").toString())
                .setProperty("hibernate.search.backend.lucene_version", "9.12.3")
                .setProperty("hibernate.search.backend.analysis.configurer", Analysis.class.getName())
                .setProperty("hibernate.search.schema_management.strategy", "create-or-validate")
                .setProperty("hibernate.search.indexing.plan.synchronization.strategy", "sync");
        return new Database(configuration.buildSessionFactory());
    }

    public static class Analysis implements LuceneAnalysisConfigurer {
        @Override public void configure(LuceneAnalysisConfigurationContext context) {
            for (String name : new String[] {"english", "dsl", "csv-keyword"}) {
                context.analyzer(name).custom().tokenizer("standard").tokenFilter("lowercase");
            }
        }
    }

    private static final class TrackingRebuilder extends CommitIndexSearchRebuilder {
        int rebuilds;
        TrackingRebuilder(EntityManagerFactory factory) { super(factory); }
        @Override public void rebuildAll() throws InterruptedException {
            rebuilds++;
            super.rebuildAll();
        }
    }

    private static final class Database implements AutoCloseable {
        final EntityManagerFactory factory;
        final TrackingRebuilder rebuilder;
        final CommitIndexSearchLifecycle lifecycle;
        Database(EntityManagerFactory factory) {
            this.factory = factory;
            this.rebuilder = new TrackingRebuilder(factory);
            var repository = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory))
                    .getRepository(ArchitectureCommitIndexRepository.class);
            this.lifecycle = new CommitIndexSearchLifecycle(repository, rebuilder);
        }
        void initialize() { lifecycle.run(new DefaultApplicationArguments()); }
        long insert(RepositoryContext context, String commit, String text) {
            return insert(context, commit, text, Instant.parse("2026-11-22T10:00:00Z"));
        }
        long insert(RepositoryContext context, String commit, String text, Instant timestamp) {
            try (var manager = factory.createEntityManager()) {
                manager.getTransaction().begin();
                var row = new ArchitectureCommitIndex();
                row.setRepositoryId(context.repositoryId());
                row.setWorkspaceId(context.workspaceId());
                row.setBranch(context.branch());
                row.setCommitId(commit);
                row.setAuthor(context.username());
                row.setCommitTimestamp(timestamp);
                row.setMessage(text);
                row.setTokenizedChangeText(text);
                manager.persist(row);
                manager.getTransaction().commit();
                return row.getId();
            }
        }
        void sql(String statement) {
            try (var manager = factory.createEntityManager()) {
                manager.getTransaction().begin();
                manager.createNativeQuery(statement).executeUpdate();
                manager.getTransaction().commit();
            }
        }
        long count() {
            try (var manager = factory.createEntityManager()) {
                return Search.session(manager).search(ArchitectureCommitIndex.class)
                        .where(f -> f.matchAll()).fetchTotalHitCount();
            }
        }
        long search(RepositoryContext context, String term) {
            try (var manager = factory.createEntityManager()) {
                return Search.session(manager).search(ArchitectureCommitIndex.class)
                        .where(f -> f.bool()
                                .filter(f.match().field("repositoryId").matching(context.repositoryId()))
                                .filter(f.match().field("workspaceId").matching(context.workspaceId()))
                                .filter(f.match().field("branch").matching(context.branch()))
                                .must(f.match().field("tokenizedChangeText").matching(term)))
                        .fetchTotalHitCount();
            }
        }
        @Override public void close() { lifecycle.stop(); factory.close(); }
    }
}
