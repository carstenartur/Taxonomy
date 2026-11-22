package com.taxonomy.search;

import com.taxonomy.catalog.model.PrimaryRepositorySeedRelationListener;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.model.RelationType;
import com.taxonomy.search.config.HibernateSearchAnalysisConfigurer;
import com.taxonomy.search.config.SpringContextHolder;
import com.taxonomy.workspace.service.SystemRepositoryService;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.search.mapper.orm.Search;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises database and filesystem Lucene across a closed/reopened session factory. */
class PersistentCatalogueIndexTest {
    @TempDir Path indexDirectory;

    @Test
    void catalogueImportCreatesSearchTextWithoutStartingOptionalModelInference() {
        var startup = new AppInitializationStateService();
        try (var fixture = new Fixture(indexDirectory, startup)) {
            fixture.seed();

            assertThat(Search.session(fixture.manager).search(TaxonomyNode.class)
                    .where(f -> f.matchAll()).fetchTotalHitCount()).isEqualTo(2);
            assertThat(fixture.embeddings.documentInferences).isZero();
            startup.update(AppInitializationStateService.State.READY, "Catalogue ready");
            assertThat(fixture.initialize().getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);
            assertThat(fixture.embeddings.documentInferences).isEqualTo(3);
        }
    }

    @Test
    void completePersistentNodeAndRelationIndexesAreReusedAfterRestart() {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            fixture.restart();

            var initializer = fixture.initialize();

            assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);
            assertThat(initializer.isNodeSearchReady()).isTrue();
            assertThat(fixture.embeddings.documentInferences).isZero();
        }
    }

    @Test
    void missingRelationTextIndexIsRebuiltIndependentlyWithMeasuredProgress() throws Exception {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            fixture.embeddings.documentInferences = 0;
            Search.mapping(fixture.factory).scope(TaxonomyRelation.class).workspace().purge();
            var state = new AppInitializationStateService();
            var rebuilder = new LocalEmbeddingIndexRebuilder(fixture.factory, 1, 4);

            rebuilder.ensureCatalogueIndexes(state);

            assertThat(rebuilder.isNodeIndexCurrent()).isTrue();
            assertThat(rebuilder.isRelationIndexCurrent()).isTrue();
            assertThat(fixture.embeddings.documentInferences).isEqualTo(1);
            assertThat(state.getSnapshot().phase()).isEqualTo("INDEXING_RELATIONS");
            assertThat(state.getSnapshot().processed()).isEqualTo(1);
            assertThat(state.getSnapshot().total()).isEqualTo(1);
            assertThat(state.isReady()).isFalse();
        }
    }

    @Test
    void fullTextValidationRejectsAnIncompleteRebuild() {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            Search.mapping(fixture.factory).scope(TaxonomyNode.class).workspace().purge();
            var state = new AppInitializationStateService();
            var incomplete = new LocalEmbeddingIndexRebuilder(fixture.factory, 1, 4) {
                @Override public void rebuildNodeIndex(java.util.function.BiConsumer<Long, Long> progress) { }
            };

            assertThatThrownBy(() -> incomplete.ensureCatalogueIndexes(state))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("validation failed");
            assertThat(state.isReady()).isFalse();
        }
    }

    @Test
    void concurrentVectorWorkersReportMonotonicCountsAndCompleteWithoutDuplicates() throws Exception {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            fixture.embeddings.modelKey = "replacement-model";
            var progress = new ArrayList<Long>();

            new LocalEmbeddingIndexRebuilder(fixture.factory, 2, 1)
                    .refreshNodeEmbeddings((processed, total) -> {
                        assertThat(total).isEqualTo(2);
                        progress.add(processed);
                    });

            assertThat(progress).containsExactly(0L, 1L, 2L);
            assertThat(Search.session(fixture.manager).search(TaxonomyNode.class)
                    .where(f -> f.matchAll()).fetchTotalHitCount()).isEqualTo(2);
        }
    }

    @Test
    void refreshingVectorsKeepsTheCompleteFullTextIndexSearchable() throws Exception {
        try (var fixture = new Fixture(indexDirectory);
             var worker = Executors.newSingleThreadExecutor()) {
            fixture.seed();
            fixture.embeddings.modelKey = "replacement-model";
            fixture.embeddings.inferenceEntered = new CountDownLatch(1);
            fixture.embeddings.inferenceRelease = new CountDownLatch(1);
            var initializing = worker.submit(fixture::initialize);
            try {
                assertThat(fixture.embeddings.inferenceEntered.await(5, TimeUnit.SECONDS)).isTrue();
                Search.mapping(fixture.factory).scope(TaxonomyNode.class).workspace().refresh();
                assertThat(Search.session(fixture.manager).search(TaxonomyNode.class)
                        .where(f -> f.matchAll()).fetchTotalHitCount()).isEqualTo(2);
            } finally {
                fixture.embeddings.inferenceRelease.countDown();
            }
            assertThat(initializing.get(5, TimeUnit.SECONDS).getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);
            assertThat(Search.session(fixture.manager).search(TaxonomyNode.class)
                    .where(f -> f.matchAll()).fetchTotalHitCount()).isEqualTo(2);
        }
    }

    @Test
    void vectorRefreshCannotResurrectAConcurrentRelationDeletion() throws Exception {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            raceWithOrdinaryWrite(fixture, false, manager -> {
                var relation = manager.createQuery("from TaxonomyRelation", TaxonomyRelation.class).getSingleResult();
                manager.remove(relation);
            });

            assertThat(Search.session(fixture.manager).search(TaxonomyRelation.class)
                    .where(f -> f.matchAll()).fetchTotalHitCount()).isZero();
            assertThat(new LocalEmbeddingIndexRebuilder(fixture.factory, 1, 4).isRelationIndexCurrent()).isTrue();
        }
    }

    @Test
    void vectorRefreshCannotOverwriteANewerRelationDescription() throws Exception {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            raceWithOrdinaryWrite(fixture, false, manager -> manager
                    .createQuery("from TaxonomyRelation", TaxonomyRelation.class).getSingleResult()
                    .setDescription("Newer searchable description"));

            assertThat(Search.session(fixture.manager).search(TaxonomyRelation.class)
                    .where(f -> f.match().field("description").matching("Newer"))
                    .fetchTotalHitCount()).isEqualTo(1);
            assertThat(new LocalEmbeddingIndexRebuilder(fixture.factory, 1, 4).isRelationIndexCurrent()).isTrue();
        }
    }

    @Test
    void vectorRefreshCannotOverwriteNewerNodeGraphMetadata() throws Exception {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            raceWithOrdinaryWrite(fixture, true, manager -> manager
                    .createQuery("from TaxonomyNode where code='BP-1'", TaxonomyNode.class).getSingleResult()
                    .setGraphRole("hub"));

            assertThat(Search.session(fixture.manager).search(TaxonomyNode.class)
                    .where(f -> f.match().field("graphRole").matching("hub"))
                    .fetchTotalHitCount()).isEqualTo(1);
            assertThat(new LocalEmbeddingIndexRebuilder(fixture.factory, 1, 4).isNodeIndexCurrent()).isTrue();
        }
    }

    @Test
    void relationRefreshCannotRestoreAnOldSourceNodeName() throws Exception {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            raceWithOrdinaryWrite(fixture, false, manager -> manager
                    .createQuery("from TaxonomyNode where code='BP-1'", TaxonomyNode.class).getSingleResult()
                    .setNameEn("Current endpoint name"));

            assertThat(Search.session(fixture.manager).search(TaxonomyRelation.class)
                    .where(f -> f.match().field("sourceNode.nameEn").matching("Current"))
                    .fetchTotalHitCount()).isEqualTo(1);
            assertThat(new LocalEmbeddingIndexRebuilder(fixture.factory, 1, 4).isRelationIndexCurrent()).isTrue();
        }
    }

    @Test
    void relationRefreshCannotRestoreAReplacedEndpoint() throws Exception {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            raceWithOrdinaryWrite(fixture, false, manager -> {
                var replacement = Fixture.node("BP-3", "Replacement endpoint");
                manager.persist(replacement);
                manager.createQuery("from TaxonomyRelation", TaxonomyRelation.class).getSingleResult()
                        .setTargetNode(replacement);
            });

            assertThat(Search.session(fixture.manager).search(TaxonomyRelation.class)
                    .where(f -> f.match().field("targetNode.code").matching("BP-3"))
                    .fetchTotalHitCount()).isEqualTo(1);
            assertThat(new LocalEmbeddingIndexRebuilder(fixture.factory, 1, 4).isRelationIndexCurrent()).isTrue();
        }
    }

    @Test
    void newerWriteSyncDocumentsAreRefreshedBeforeFinalEmbeddingValidation() throws Exception {
        try (var fixture = new Fixture(indexDirectory, null, "write-sync")) {
            fixture.seed();
            raceWithOrdinaryWrite(fixture, false, manager -> manager
                    .createQuery("from TaxonomyRelation", TaxonomyRelation.class).getSingleResult()
                    .setDescription("Newer reader-visible description"));

            new LocalEmbeddingIndexRebuilder(fixture.factory, 1, 4)
                    .verifyRelationEmbeddingCoverage("replacement-model");
            assertThat(Search.session(fixture.manager).search(TaxonomyRelation.class)
                    .where(f -> f.match().field("description").matching("Newer"))
                    .fetchTotalHitCount()).isEqualTo(1);
        }
    }

    @Test
    void invalidOptionalVectorDoesNotDiscardAnOrdinaryFullTextUpdate() {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            fixture.embeddings.invalidVector = true;
            try (var manager = fixture.factory.createEntityManager()) {
                manager.getTransaction().begin();
                manager.createQuery("from TaxonomyNode where code='BP-1'", TaxonomyNode.class).getSingleResult()
                        .setDescriptionEn("Still searchable text");
                manager.getTransaction().commit();
            }

            assertThat(Search.session(fixture.manager).search(TaxonomyNode.class)
                    .where(f -> f.match().field("descriptionEn").matching("searchable"))
                    .fetchTotalHitCount()).isEqualTo(1);
            assertThat(new LocalEmbeddingIndexRebuilder(fixture.factory, 1, 4)
                    .isNodeEmbeddingIndexCurrent("original-model")).isFalse();
        }
    }

    @Test
    void failedPreparedRefreshScopeCannotLeakIntoAnOrdinaryOrmWrite() {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            assertThatThrownBy(() -> EmbeddingBridgeSupport.withPrepared(java.util.Map.of(), () -> {
                throw new IllegalStateException("Index write failed");
            })).isInstanceOf(IllegalStateException.class);

            try (var manager = fixture.factory.createEntityManager()) {
                manager.getTransaction().begin();
                manager.createQuery("from TaxonomyNode where code='BP-1'", TaxonomyNode.class).getSingleResult()
                        .setDescriptionEn("Current ordinary update");
                manager.getTransaction().commit();
            }
            assertThat(new LocalEmbeddingIndexRebuilder(fixture.factory, 1, 4)
                    .isNodeEmbeddingIndexCurrent("original-model")).isTrue();
        }
    }

    private void raceWithOrdinaryWrite(Fixture fixture, boolean nodes,
            java.util.function.Consumer<EntityManager> mutation) throws Exception {
        fixture.embeddings.modelKey = "replacement-model";
        fixture.embeddings.inferenceEntered = new CountDownLatch(1);
        fixture.embeddings.inferenceRelease = new CountDownLatch(1);
        fixture.embeddings.blockRefreshOnly = true;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var refreshing = workers.submit(() -> {
                var rebuilder = new LocalEmbeddingIndexRebuilder(fixture.factory, 1, 4);
                if (nodes) rebuilder.refreshNodeEmbeddings((processed, total) -> { });
                else rebuilder.refreshRelationEmbeddings((processed, total) -> { });
                return null;
            });
            try {
                assertThat(fixture.embeddings.inferenceEntered.await(5, TimeUnit.SECONDS)).isTrue();
                // Foreground changes commit while slow inference is blocked. Their
                // current documents must survive the older refresh finishing later.
                workers.submit(() -> {
                    try (var manager = fixture.factory.createEntityManager()) {
                        manager.getTransaction().begin();
                        mutation.accept(manager);
                        manager.getTransaction().commit();
                    }
                }).get(5, TimeUnit.SECONDS);
            } finally {
                fixture.embeddings.inferenceRelease.countDown();
            }
            refreshing.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void sameCountRelationTenantMutationRebuildsRelationsWithoutRecomputingNodeVectors() {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            fixture.mutate("update taxonomy_relation set repository_id='repository-two', owner_username='second-owner'");
            fixture.restart();

            var initializer = fixture.initialize();

            assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);
            assertThat(fixture.embeddings.documentInferences).isEqualTo(1);
            assertThat(Search.session(fixture.manager).search(TaxonomyRelation.class)
                    .where(f -> f.match().field("repositoryId").matching("repository-one"))
                    .fetchTotalHitCount()).isZero();
            assertThat(Search.session(fixture.manager).search(TaxonomyRelation.class)
                    .where(f -> f.match().field("repositoryId").matching("repository-two"))
                    .fetchTotalHitCount()).isEqualTo(1);
        }
    }

    @Test
    void changedNodeTextAtTheSameRowCountCannotReuseOldVectors() {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            fixture.mutate("update taxonomy_node set descriptionEn='Changed without ORM indexing' where code='BP-1'");
            fixture.restart();

            var initializer = fixture.initialize();

            assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);
            assertThat(fixture.embeddings.documentInferences).isEqualTo(2);
        }
    }

    @Test
    void differentModelIdentityRebuildsBothVectorScopes() {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            fixture.restart();
            fixture.embeddings.modelKey = "replacement-model";

            var initializer = fixture.initialize();

            assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);
            assertThat(fixture.embeddings.documentInferences).isEqualTo(3);
            assertThat(Search.session(fixture.manager).search(TaxonomyNode.class)
                    .where(f -> f.match().field("embeddingModel").matching("original-model"))
                    .fetchTotalHitCount()).isZero();
        }
    }

    @Test
    void incompleteRebuildFailsClosedAndNextStartupRepairsIt() {
        try (var fixture = new Fixture(indexDirectory)) {
            fixture.seed();
            fixture.restart();
            fixture.embeddings.modelKey = "replacement-model";
            fixture.embeddings.rejectSecondNode = true;

            var failed = fixture.initialize();

            assertThat(failed.getState()).isEqualTo(LocalOnnxIndexInitializer.State.FAILED);
            assertThat(failed.isNodeSearchReady()).isFalse();
            fixture.restart();
            fixture.embeddings.rejectSecondNode = false;
            var recovered = fixture.initialize();
            assertThat(recovered.getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);
            assertThat(recovered.isNodeSearchReady()).isTrue();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final GenericApplicationContext context = new GenericApplicationContext();
        final ApplicationContext previousContext = (ApplicationContext)
                ReflectionTestUtils.getField(SpringContextHolder.class, "context");
        final FixtureEmbeddings embeddings = new FixtureEmbeddings();
        final String database = "jdbc:hsqldb:mem:index-reuse-" + UUID.randomUUID();
        final Path directory;
        final String synchronization;
        SessionFactory factory;
        EntityManager manager;

        Fixture(Path directory) {
            this(directory, null);
        }

        Fixture(Path directory, AppInitializationStateService startup) {
            this(directory, startup, "sync");
        }

        Fixture(Path directory, AppInitializationStateService startup, String synchronization) {
            this.directory = directory;
            this.synchronization = synchronization;
            context.getBeanFactory().registerSingleton("localEmbeddingService", embeddings);
            if (startup != null) context.getBeanFactory().registerSingleton("initializationState", startup);
            context.registerBean(PrimaryRepositorySeedRelationListener.class,
                    () -> new PrimaryRepositorySeedRelationListener(context.getBeanProvider(SystemRepositoryService.class)));
            context.refresh();
            new SpringContextHolder().setApplicationContext(context);
            open();
        }

        private void open() {
            var configuration = new Configuration().addAnnotatedClass(TaxonomyNode.class)
                    .addAnnotatedClass(TaxonomyRelation.class)
                    .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                    .setProperty("hibernate.connection.url", database)
                    .setProperty("hibernate.connection.username", "sa")
                    .setProperty("hibernate.connection.password", "")
                    .setProperty("hibernate.hbm2ddl.auto", "update")
                    .setProperty("hibernate.search.backend.type", "lucene")
                    .setProperty("hibernate.search.backend.directory.type", "local-filesystem")
                    .setProperty("hibernate.search.backend.directory.root", directory.toString())
                    .setProperty("hibernate.search.backend.lucene_version", "9.12.3")
                    .setProperty("hibernate.search.backend.analysis.configurer", HibernateSearchAnalysisConfigurer.class.getName())
                    .setProperty("hibernate.search.schema_management.strategy", "create-or-validate")
                    .setProperty("hibernate.search.indexing.plan.synchronization.strategy", synchronization);
            configuration.getProperties().put("hibernate.resource.beans.container",
                    new SpringBeanContainer(context.getBeanFactory()));
            factory = configuration.buildSessionFactory();
            manager = factory.createEntityManager();
            ReflectionTestUtils.setField(embeddings, "entityManager", manager);
        }

        void seed() {
            manager.getTransaction().begin();
            var first = node("BP-1", "First node");
            var second = node("BP-2", "Second node");
            manager.persist(first);
            manager.persist(second);
            var relation = new TaxonomyRelation();
            relation.setSourceNode(first);
            relation.setTargetNode(second);
            relation.setRelationType(RelationType.SUPPORTS);
            relation.setDescription("A relation");
            relation.setRepositoryId("repository-one");
            manager.persist(relation);
            manager.getTransaction().commit();
        }

        void mutate(String sql) {
            manager.getTransaction().begin();
            manager.createNativeQuery(sql).executeUpdate();
            manager.getTransaction().commit();
        }

        void restart() {
            manager.close();
            factory.close();
            open();
            embeddings.documentInferences = 0;
        }

        LocalOnnxIndexInitializer initialize() {
            var state = new AppInitializationStateService();
            state.update(AppInitializationStateService.State.READY, "Ready");
            var initializer = new LocalOnnxIndexInitializer(embeddings, state,
                    new LocalEmbeddingIndexRebuilder(factory, 1, 4), new EmbeddingIndexHealth());
            initializer.initializeLocalOnnxIndex();
            return initializer;
        }

        private static TaxonomyNode node(String code, String name) {
            var node = new TaxonomyNode();
            node.setCode(code);
            node.setNameEn(name);
            node.setTaxonomyRoot("BP");
            return node;
        }

        @Override public void close() {
            try {
                if (manager != null) manager.close();
                if (factory != null) factory.close();
                context.close();
            } finally {
                ReflectionTestUtils.setField(SpringContextHolder.class, "context", previousContext);
            }
        }
    }

    private static final class FixtureEmbeddings extends LocalEmbeddingService {
        volatile int documentInferences;
        String modelKey = "original-model";
        boolean rejectSecondNode;
        CountDownLatch inferenceEntered;
        CountDownLatch inferenceRelease;
        boolean blockRefreshOnly;
        boolean invalidVector;
        @Override public boolean isEnabled() { return true; }
        @Override public boolean isAvailable() { return true; }
        @Override public String embeddingIndexKey() { return modelKey; }
        @Override public float[] embed(String text) {
            var vector = new float[384];
            vector[0] = 1;
            return vector;
        }
        @Override public float[] embedDocument(String text) throws InterruptedException {
            documentInferences++;
            if (inferenceEntered != null && (!blockRefreshOnly
                    || Thread.currentThread().getName().startsWith("embedding-refresh-"))) {
                inferenceEntered.countDown();
                inferenceRelease.await();
            }
            if (rejectSecondNode && text.startsWith("Second node"))
                throw new IllegalStateException("Private inference failure");
            if (invalidVector) return new float[]{1};
            return embed(text);
        }
    }
}
