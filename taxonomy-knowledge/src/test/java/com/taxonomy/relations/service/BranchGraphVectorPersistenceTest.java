package com.taxonomy.relations.service;

import com.taxonomy.error.SearchUnavailableException;
import com.taxonomy.relations.model.RelationDecisionProjectionCheckpoint;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

class BranchGraphVectorPersistenceTest {
    @TempDir Path directory;
    static final String HEAD = "1".repeat(40);

    @Test void aRestartPreservesExactNativeFieldsAndCompetingBuildersOrPurgesFencePublication() throws Exception {
        var a = RepositoryContext.centralRead("repo:A", "main", "reader");
        var b = RepositoryContext.centralRead("repo:B", "main", "reader");
        var draft = RepositoryContext.centralRead("repo:A", "draft", "reader");
        var worker = new RepositoryContext("repo:A", "copy", "main", "reader", RepositoryScope.WORKSPACE);
        var otherWorker = new RepositoryContext("repo:A", "copy-2", "main", "reader", RepositoryScope.WORKSPACE);
        var foreignWorker = new RepositoryContext("repo:B", "copy", "main", "reader", RepositoryScope.WORKSPACE);
        var scopes = List.of(a, b, draft, worker, otherWorker, foreignWorker);
        try (var first = open("create")) {
            for (var scope : scopes) {
                first.checkpoint(scope);
                var source = source(scope, "m1");
                String token = first.transaction(() -> first.store.begin(scope, source, "fixture-model"));
                var archive = archive(source, scope.repositoryId() + "/" + scope.workspaceId() + "/" + scope.branch());
                first.transaction(() -> { first.store.publish(scope, source, token, archive, () -> {}); return null; });
            }
        }
        try (var second = open("validate")) {
            for (var scope : scopes) {
                var source = source(scope, "m1");
                var state = second.transaction(() -> second.store.status(scope, source));
                assertThat(state.state()).isEqualTo(BranchGraphVectorStore.State.READY);
                assertThat(state.nodeCount()).isOne();
                assertThat(state.relationCount()).isOne();
                var reopened = second.transaction(() -> second.store.read(scope, source));
                assertThat(BranchGraphVectorIndex.search(reopened.index(), vector(), 1, 1).nodes())
                        .containsExactly(scope.repositoryId() + "/" + scope.workspaceId() + "/" + scope.branch());
                assertThatThrownBy(() -> second.transaction(() -> second.store.read(scope, source(scope, "m2"))))
                        .isInstanceOf(SearchUnavailableException.class);
            }
            var guessed = new RepositoryContext("repo:A", "unknown-copy", "main", "reader", RepositoryScope.WORKSPACE);
            assertThatThrownBy(() -> second.transaction(() -> second.store.read(guessed, source(guessed, "m1"))))
                    .isInstanceOf(SearchUnavailableException.class);
            var chosen = source(a, "m1");
            String first = second.transaction(() -> second.store.begin(a, chosen, "fixture-model"));
            String secondToken = second.transaction(() -> second.store.begin(a, chosen, "fixture-model"));
            var obsolete = archive(chosen, "obsolete");
            assertThatThrownBy(() -> second.transaction(() -> {second.store.publish(a, chosen, first, obsolete, () -> {}); return null;}))
                    .isInstanceOf(SearchUnavailableException.class);
            second.transaction(() -> {second.store.fail(a, first); return null;});
            assertThat(second.transaction(() -> second.store.status(a, chosen)).state()).isEqualTo(BranchGraphVectorStore.State.BUILDING);
            second.transaction(() -> {second.store.purge(a, () -> {}); return null;});
            assertThatThrownBy(() -> second.transaction(() -> {second.store.publish(a, chosen, secondToken, obsolete, () -> {}); return null;}))
                    .isInstanceOf(SearchUnavailableException.class);
            assertThat(second.transaction(() -> second.store.status(a, chosen)).state()).isEqualTo(BranchGraphVectorStore.State.MISSING);
            assertThat(second.transaction(() -> second.store.status(worker, source(worker, "m1"))).state()).isEqualTo(BranchGraphVectorStore.State.READY);
            second.transaction(() -> {
                second.em.createNativeQuery("update branch_graph_vector_generation set index_sha256 = :wrong where source_key = :chosen")
                        .setParameter("wrong", "0".repeat(64)).setParameter("chosen", source(worker, "m1").key()).executeUpdate();
                return null;
            });
            assertThatThrownBy(() -> second.transaction(() -> second.store.read(worker, source(worker, "m1"))))
                    .isInstanceOf(SearchUnavailableException.class);
        }
    }

    private static BranchGraphVectorSource source(RepositoryContext context, String model) {
        return new BranchGraphVectorSource(context.repositoryId(), context.workspaceId(), context.branch(), HEAD,
                "official-1", "a".repeat(64), model, "b".repeat(64), 1);
    }

    private static float[] vector() {
        float[] values = new float[384]; values[0] = 1f; return values;
    }

    private static BranchGraphVectorIndex.Archive archive(BranchGraphVectorSource source, String label) throws Exception {
        try (var builder = new BranchGraphVectorIndex.Builder(source.key())) {
            builder.node(label, label, vector());
            builder.relation("A", "B", "CP", "SUPPORTS", "supports", vector());
            return builder.finish();
        }
    }

    private Database open(String ddl) { return open(directory, ddl); }

    static Database open(Path directory, String ddl) {
        var config = new Configuration().addAnnotatedClass(RelationDecisionProjectionCheckpoint.class)
                .addAnnotatedClass(BranchGraphVectorGeneration.class)
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", "jdbc:hsqldb:file:" + directory.resolve("branches") + ";shutdown=true")
                .setProperty("hibernate.connection.username","sa").setProperty("hibernate.connection.password","")
                .setProperty("hibernate.search.enabled", "false").setProperty("hibernate.hbm2ddl.auto", ddl);
        return new Database(config.buildSessionFactory());
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions { }

    static class Database implements AutoCloseable {
        final AnnotationConfigApplicationContext application = new AnnotationConfigApplicationContext();
        final EntityManagerFactory factory;
        final EntityManager em;
        final TransactionTemplate transactions;
        final BranchGraphVectorStore store;
        Database(EntityManagerFactory factory) {
            this.factory = factory;
            this.em = SharedEntityManagerCreator.createSharedEntityManager(factory);
            this.transactions = new TransactionTemplate(new JpaTransactionManager(factory));
            application.register(Transactions.class);
            application.registerBean(EntityManagerFactory.class, () -> factory);
            application.registerBean(PlatformTransactionManager.class, () -> transactions.getTransactionManager());
            application.registerBean(RelationDecisionProjectionCheckpointRepository.class,
                    () -> new JpaRepositoryFactory(em).getRepository(RelationDecisionProjectionCheckpointRepository.class));
            application.registerBean(BranchGraphVectorStore.class);
            application.refresh();
            this.store = application.getBean(BranchGraphVectorStore.class);
        }
        <T> T transaction(Supplier<T> work) { return transactions.execute(status -> work.get()); }
        void checkpoint(RepositoryContext context) { checkpoint(context, 1); }
        void checkpoint(RepositoryContext context, int relationCount) {
            transaction(() -> {
                var row = new RelationDecisionProjectionCheckpoint();
                row.setRepositoryId(context.repositoryId()); row.setWorkspaceId(context.workspaceId()); row.setBranch(context.branch());
                row.setAuthoritativeCommitId(HEAD); row.setRelationCount(relationCount); em.persist(row); return null;
            });
        }
        @Override public void close() { application.close(); if (factory.isOpen()) factory.close(); }
    }
}
