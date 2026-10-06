package com.taxonomy.search;

import com.taxonomy.catalog.model.PrimaryRepositorySeedRelationListener;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.model.RelationType;
import com.taxonomy.search.config.HibernateSearchAnalysisConfigurer;
import com.taxonomy.search.config.SpringContextHolder;
import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.search.mapper.orm.Search;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real HSQLDB, Lucene, entity binders and mass indexer with deterministic local inference failures. */
class SearchIndexCoverageTest {
    private final FixtureEmbeddings embeddings = new FixtureEmbeddings();
    private final EmbeddingIndexHealth indexHealth = new EmbeddingIndexHealth();
    private GenericApplicationContext context;
    private ApplicationContext previousContext;
    private EntityManagerFactory factory;
    private EntityManager manager;
    private LocalOnnxIndexInitializer initializer;

    @BeforeEach
    void createIsolatedIndex() {
        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringContextHolder.class, "context");
        context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("localEmbeddingService", embeddings);
        context.getBeanFactory().registerSingleton("embeddingIndexHealth", indexHealth);
        context.registerBean(PrimaryRepositorySeedRelationListener.class,
                () -> new PrimaryRepositorySeedRelationListener(context.getBeanProvider(SystemRepositoryService.class)));
        context.refresh();
        new SpringContextHolder().setApplicationContext(context);
        var configuration = new Configuration().addAnnotatedClass(TaxonomyNode.class)
                .addAnnotatedClass(TaxonomyRelation.class)
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:search-coverage-" + UUID.randomUUID())
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.search.backend.type", "lucene")
                .setProperty("hibernate.search.backend.directory.type", "local-heap")
                .setProperty("hibernate.search.backend.lucene_version", "9.12.3")
                .setProperty("hibernate.search.backend.analysis.configurer", HibernateSearchAnalysisConfigurer.class.getName())
                .setProperty("hibernate.search.schema_management.strategy", "create")
                .setProperty("hibernate.search.indexing.plan.synchronization.strategy", "sync");
        configuration.getProperties().put("hibernate.resource.beans.container",
                new SpringBeanContainer(context.getBeanFactory()));
        factory = configuration.buildSessionFactory();
        manager = factory.createEntityManager();
        ReflectionTestUtils.setField(embeddings, "entityManager", manager);
        var startup = mock(AppInitializationStateService.class);
        when(startup.getState()).thenReturn(AppInitializationStateService.State.READY);
        initializer = new LocalOnnxIndexInitializer(embeddings, startup,
                new LocalEmbeddingIndexRebuilder(factory, 1, 4), indexHealth, "LOCAL_ONNX");
    }

    @AfterEach
    void closeIndex() {
        try {
            if (manager != null) manager.close();
            if (factory != null) factory.close();
            if (context != null) context.close();
        } finally {
            ReflectionTestUtils.setField(SpringContextHolder.class, "context", previousContext);
        }
    }

    @Test
    void silentlySkippedRelationVectorCannotBeReportedAsReady() {
        seed(true);
        embeddings.failRelation = true;
        initializer.initializeLocalOnnxIndex();

        assertThat(vectorCount(TaxonomyNode.class)).isEqualTo(2);
        assertThat(vectorCount(TaxonomyRelation.class)).isEqualTo(1);
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.PARTIAL);
        assertThat(initializer.isNodeSearchReady()).isTrue();
        assertThat(initializer.getDetail()).doesNotContain("private-");
    }

    @Test
    void aSingleSearchableNodeDoesNotHideAnotherMissingNodeVector() {
        seed(true);
        embeddings.failNode = true;
        initializer.initializeLocalOnnxIndex();

        assertThat(vectorCount(TaxonomyNode.class)).isEqualTo(1);
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.FAILED);
        assertThat(initializer.isNodeSearchReady()).isFalse();
    }

    @Test
    void completeModelBoundCoverageEnablesSearch() {
        seed(true);
        initializer.initializeLocalOnnxIndex();

        assertThat(vectorCount(TaxonomyNode.class)).isEqualTo(2);
        assertThat(vectorCount(TaxonomyRelation.class)).isEqualTo(2);
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);
        assertThat(initializer.isNodeSearchReady()).isTrue();
    }

    @Test
    void vectorsTaggedWithAnotherModelDoNotSatisfyRelationReadiness() {
        seed(true);
        embeddings.wrongRelationModel = true;
        initializer.initializeLocalOnnxIndex();

        assertThat(vectorCount(TaxonomyRelation.class)).isEqualTo(1);
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.PARTIAL);
        assertThat(initializer.isNodeSearchReady()).isTrue();
    }

    @Test
    void catalogueWithoutRelationsHasCompleteEmptyRelationCoverage() {
        seed(false);
        initializer.initializeLocalOnnxIndex();

        assertThat(vectorCount(TaxonomyNode.class)).isEqualTo(2);
        assertThat(vectorCount(TaxonomyRelation.class)).isZero();
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);
    }

    @Test
    void failedAutomaticRelationUpdateRevokesGraphReadinessAfterStartup() {
        seed(true);
        initializer.initializeLocalOnnxIndex();
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);

        embeddings.failRelation = true;
        updateRelation();

        assertThat(vectorCount(TaxonomyRelation.class)).isEqualTo(1);
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.PARTIAL);
        assertThat(initializer.isNodeSearchReady()).isTrue();
        assertThat(initializer.getDetail()).doesNotContain("private-");
    }

    @Test
    void failedAutomaticNodeUpdateRevokesAllSemanticReadinessAfterStartup() {
        seed(true);
        initializer.initializeLocalOnnxIndex();
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);

        embeddings.failNode = true;
        manager.getTransaction().begin();
        var node = manager.createQuery("from TaxonomyNode where code = :code", TaxonomyNode.class)
                .setParameter("code", "fixture-coverage-second").getSingleResult();
        node.setDescriptionEn("Updated fixture description");
        manager.getTransaction().commit();

        assertThat(vectorCount(TaxonomyNode.class)).isEqualTo(1);
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.FAILED);
        assertThat(initializer.isNodeSearchReady()).isFalse();
        assertThat(initializer.getDetail()).doesNotContain("private-");
    }

    @Test
    void successfulAutomaticUpdateRetainsReadiness() {
        seed(true);
        initializer.initializeLocalOnnxIndex();
        updateRelation();

        assertThat(vectorCount(TaxonomyRelation.class)).isEqualTo(2);
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);
        assertThat(initializer.isNodeSearchReady()).isTrue();
    }

    @Test
    void nativeLinkageFailureDuringAutomaticIndexingRevokesReadiness() {
        seed(true);
        initializer.initializeLocalOnnxIndex();
        embeddings.failRelationNative = true;
        updateRelation();

        assertThat(vectorCount(TaxonomyRelation.class)).isEqualTo(1);
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.PARTIAL);
        assertThat(initializer.isNodeSearchReady()).isTrue();
    }

    @Test
    void rebuildCannotForgetAFailedWriteObservedBeforeItStarted() {
        seed(true);
        embeddings.failRelation = true;
        updateRelation();
        embeddings.failRelation = false;

        initializer.initializeLocalOnnxIndex();

        assertThat(vectorCount(TaxonomyRelation.class)).isEqualTo(2);
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.PARTIAL);
        assertThat(initializer.isNodeSearchReady()).isTrue();
    }

    @Test
    void interruptedAutomaticIndexingRetainsCancellationAndRevokesReadiness() {
        seed(true);
        initializer.initializeLocalOnnxIndex();
        embeddings.failRelationInterrupted = true;
        try {
            assertThatThrownBy(this::updateRelation)
                    .isInstanceOf(org.hibernate.HibernateException.class)
                    .hasMessageContaining("interrupt signal");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        // Cancellation discarded the complete index update; the previous vector remains.
        assertThat(vectorCount(TaxonomyRelation.class)).isEqualTo(2);
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.PARTIAL);
    }

    private void updateRelation() {
        manager.getTransaction().begin();
        var relation = manager.createQuery("from TaxonomyRelation where description = :description", TaxonomyRelation.class)
                .setParameter("description", "private-relation-failure").getSingleResult();
        relation.setDescription("private-relation-failure updated");
        manager.getTransaction().commit();
    }

    private void seed(boolean withRelations) {
        manager.getTransaction().begin();
        var first = node("fixture-coverage-first", "Fixture first");
        var second = node("fixture-coverage-second", "Fixture missing vector");
        manager.persist(first);
        manager.persist(second);
        if (withRelations) {
            manager.persist(relation(first, second, RelationType.SUPPORTS, "Normal relation"));
            manager.persist(relation(second, first, RelationType.DEPENDS_ON, "private-relation-failure"));
        }
        manager.getTransaction().commit();
        embeddings.enabled = true;
    }

    private long vectorCount(Class<?> entityType) {
        return Search.session(manager).search(entityType)
                .where(f -> f.match().field("embeddingModel").matching("fixture-coverage-model"))
                .fetchTotalHitCount();
    }

    private static TaxonomyNode node(String code, String name) {
        var node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(name);
        node.setTaxonomyRoot("BP");
        return node;
    }

    private static TaxonomyRelation relation(TaxonomyNode source, TaxonomyNode target,
            RelationType type, String description) {
        var relation = new TaxonomyRelation();
        relation.setRepositoryId("fixture-coverage-repository");
        relation.setSourceNode(source);
        relation.setTargetNode(target);
        relation.setRelationType(type);
        relation.setDescription(description);
        return relation;
    }

    private static final class FixtureEmbeddings extends LocalEmbeddingService {
        private volatile boolean enabled;
        private volatile boolean failNode;
        private volatile boolean failRelation;
        private volatile boolean failRelationNative;
        private volatile boolean failRelationInterrupted;
        private volatile boolean wrongRelationModel;
        private final ThreadLocal<String> modelKey = ThreadLocal.withInitial(() -> "fixture-coverage-model");
        @Override public boolean isEnabled() { return enabled; }
        @Override public boolean isAvailable() { return enabled; }
        @Override public String embeddingIndexKey() { return modelKey.get(); }
        @Override public float[] embed(String text) { return vector(); }
        @Override public float[] embedQuery(String text) { return vector(); }
        @Override public float[] embedDocument(String text) throws Exception {
            modelKey.set(wrongRelationModel && text.contains("private-relation-failure")
                    ? "fixture-other-model" : "fixture-coverage-model");
            if (text.contains("private-relation-failure")) {
                if (failRelationNative) throw new UnsatisfiedLinkError("private-native-path");
                if (failRelationInterrupted) throw new InterruptedException("private-cancellation-payload");
            }
            if ((failNode && text.startsWith("Fixture missing vector.\n"))
                    || (failRelation && text.contains("private-relation-failure"))) {
                throw new IllegalStateException("private-inference-payload");
            }
            return vector();
        }
        private static float[] vector() { var vector = new float[384]; vector[0] = 1; return vector; }
    }
}
