package com.taxonomy.search;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.model.PrimaryRepositorySeedRelationListener;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.search.config.HibernateSearchAnalysisConfigurer;
import com.taxonomy.search.config.SpringContextHolder;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.search.mapper.orm.Search;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Lucene KNN queries; only local model inference is replaced with authored vectors. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddingModelKnnIsolationTest {

    private static final String CURRENT_KEY = "fixture-current-model-sha256-and-contract";
    private static final String WRONG_KEY = "fixture-other-model-sha256-and-contract";
    private static final String CURRENT_NODE = "fixture-current";
    private static final String WRONG_NODE = "fixture-wrong";
    private static final String LEGACY_NODE = "fixture-untagged";
    private static final String QUERY_NODE = "fixture-query-source";
    private static final String CURRENT_REPOSITORY = "fixture-model-isolation-repository";
    private static final WorkspaceContext CENTRAL_CONTEXT =
            new WorkspaceContext("fixture-user", null, "draft", CURRENT_REPOSITORY);

    private final FixtureEmbeddingService embeddings = new FixtureEmbeddingService();
    private GenericApplicationContext context;
    private ApplicationContext previousBridgeContext;
    private EntityManagerFactory factory;
    private EntityManager entityManager;
    private GraphSearchService graphSearch;
    private List<TaxonomyNode> candidates;

    @BeforeAll
    void createMixedModelIndex() {
        previousBridgeContext = (ApplicationContext) ReflectionTestUtils.getField(SpringContextHolder.class, "context");
        context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("localEmbeddingService", embeddings);
        context.registerBean(PrimaryRepositorySeedRelationListener.class,
                () -> new PrimaryRepositorySeedRelationListener(context.getBeanProvider(SystemRepositoryService.class)));
        context.refresh();
        new SpringContextHolder().setApplicationContext(context);

        Configuration configuration = new Configuration()
                .addAnnotatedClass(TaxonomyNode.class)
                .addAnnotatedClass(TaxonomyRelation.class)
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:embedding-model-knn-isolation")
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
        entityManager = factory.createEntityManager();
        ReflectionTestUtils.setField(embeddings, "entityManager", entityManager);
        graphSearch = new GraphSearchService(embeddings);
        ReflectionTestUtils.setField(graphSearch, "entityManager", entityManager);

        entityManager.getTransaction().begin();
        TaxonomyNode current = node(CURRENT_NODE, "Current profile", "BP");
        TaxonomyNode wrong = node(WRONG_NODE, "Wrong profile", "CP");
        TaxonomyNode legacy = node(LEGACY_NODE, "Legacy untagged", "CR");
        TaxonomyNode query = node(QUERY_NODE, "Query source", "UA");
        candidates = List.of(current, wrong, legacy);
        for (TaxonomyNode node : List.of(current, wrong, legacy, query)) entityManager.persist(node);
        entityManager.persist(relation(current, query, RelationType.SUPPORTS));
        entityManager.persist(relation(wrong, query, RelationType.REALIZES));
        entityManager.persist(relation(legacy, query, RelationType.DEPENDS_ON));
        for (var type : List.of(RelationType.SUPPORTS, RelationType.REALIZES, RelationType.DEPENDS_ON)) {
            var foreign = relation(wrong, query, type);
            foreign.setRepositoryId("fixture-foreign-repository");
            foreign.setDescription("foreign relation with closer vector");
            entityManager.persist(foreign);
        }
        var selectedWorkspace = relation(current, query, RelationType.REALIZES);
        selectedWorkspace.setWorkspaceId("fixture-selected-workspace");
        entityManager.persist(selectedWorkspace);
        var otherWorkspace = relation(wrong, query, RelationType.DEPENDS_ON);
        otherWorkspace.setWorkspaceId("fixture-other-workspace");
        otherWorkspace.setDescription("foreign relation with closer vector");
        entityManager.persist(otherWorkspace);
        entityManager.getTransaction().commit();
        embeddings.indexing = false;
        entityManager.getTransaction().begin();
    }

    @AfterAll
    void closeIsolatedIndex() {
        try {
            if (entityManager != null) {
                if (entityManager.getTransaction().isActive()) entityManager.getTransaction().rollback();
                entityManager.close();
            }
            if (factory != null) factory.close();
            if (context != null) context.close();
        } finally {
            ReflectionTestUtils.setField(SpringContextHolder.class, "context", previousBridgeContext);
        }
    }

    @Test
    void fixtureActuallyIndexesCloserWrongModelAndUntaggedVectors() {
        assertThat(Search.session(entityManager).search(TaxonomyNode.class)
                .where(f -> f.bool().must(f.match().field("code").matching(WRONG_NODE))
                        .must(f.match().field("embeddingModel").matching(WRONG_KEY)))
                .fetchTotalHitCount()).isEqualTo(1);
        assertThat(Search.session(entityManager).search(TaxonomyNode.class)
                .where(f -> f.bool().must(f.match().field("code").matching(LEGACY_NODE))
                        .must(f.exists().field("embeddingModel")))
                .fetchTotalHitCount()).isZero();
        assertThat(Search.session(entityManager).search(TaxonomyNode.class)
                .where(f -> f.knn(2).field("embedding").matching(vector(1, 0)))
                .fetchHits(2)).extracting(TaxonomyNode::getCode)
                .containsExactlyInAnyOrder(WRONG_NODE, LEGACY_NODE);
        assertThat(Search.session(entityManager).search(TaxonomyRelation.class)
                .where(f -> f.knn(2).field("embedding").matching(vector(1, 0))
                        .filter(f.bool()
                                .must(f.match().field("repositoryId").matching(CURRENT_REPOSITORY))
                                .must(f.not(f.exists().field("workspaceId")))))
                .fetchHits(2)).extracting(TaxonomyRelation::getRelationType)
                .containsExactlyInAnyOrder(RelationType.REALIZES, RelationType.DEPENDS_ON);
    }

    @Test
    void scoringLeavesWrongModelAndUntaggedCandidatesAtZero() {
        assertThat(embeddings.scoreNodes("authored query", candidates))
                .containsExactlyInAnyOrderEntriesOf(Map.of(CURRENT_NODE, 80, WRONG_NODE, 0, LEGACY_NODE, 0));
    }

    @Test
    void semanticSearchReturnsMatchingModelDespiteCloserIncompatibleVectors() {
        assertThat(embeddings.semanticSearch("authored query", 1))
                .extracting(TaxonomyNodeDto::getCode).containsExactly(CURRENT_NODE);
    }

    @Test
    void similarNodesReturnsMatchingModelDespiteCloserIncompatibleVectors() {
        assertThat(embeddings.findSimilarNodes(QUERY_NODE, 1))
                .extracting(TaxonomyNodeDto::getCode).containsExactly(CURRENT_NODE);
    }

    @Test
    void graphNodeSearchReturnsMatchingModelDespiteCloserIncompatibleVectors() {
        assertThat(graphSearch.graphSearch("authored query", 1, CENTRAL_CONTEXT).getMatchedNodes())
                .extracting(TaxonomyNodeDto::getCode).containsExactly(CURRENT_NODE);
    }

    @Test
    void graphRelationStatisticsExcludeCloserWrongModelAndUntaggedVectors() {
        var result = graphSearch.graphSearch("authored query", 1, CENTRAL_CONTEXT);
        assertThat(result.getRelationCountByRoot()).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1L));
        assertThat(result.getTopRelationTypes()).containsExactlyInAnyOrderEntriesOf(Map.of("SUPPORTS", 1L));
    }

    @Test
    void repositoryAndWorkspaceRestrictionsApplyBeforeTheKnnCandidateBudget() {
        var selected = new WorkspaceContext("fixture-user", "fixture-selected-workspace", "draft", CURRENT_REPOSITORY);
        var result = graphSearch.graphSearch("authored query", 1, selected);

        assertThat(result.getRelationCountByRoot()).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 2L));
        assertThat(result.getTopRelationTypes())
                .containsExactlyInAnyOrderEntriesOf(Map.of("SUPPORTS", 1L, "REALIZES", 1L));
    }

    @Test
    void anEmptyRepositoryCannotReceiveAnotherRepositorysGraphStatistics() {
        var empty = new WorkspaceContext("fixture-user", null, "draft", "fixture-empty-repository");
        var result = graphSearch.graphSearch("authored query", 1, empty);

        assertThat(result.getRelationCountByRoot()).isEmpty();
        assertThat(result.getTopRelationTypes()).isEmpty();
    }

    private static TaxonomyNode node(String code, String name, String root) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(name);
        node.setTaxonomyRoot(root);
        return node;
    }

    private static TaxonomyRelation relation(TaxonomyNode source, TaxonomyNode target, RelationType type) {
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setRepositoryId(CURRENT_REPOSITORY);
        relation.setSourceNode(source);
        relation.setTargetNode(target);
        relation.setRelationType(type);
        return relation;
    }

    private static float[] vector(float x, float y) {
        float[] vector = new float[384];
        vector[0] = x;
        vector[1] = y;
        return vector;
    }

    /** Fixed inference boundary: the production entity binders and all KNN queries remain real. */
    private static class FixtureEmbeddingService extends LocalEmbeddingService {
        private boolean indexing = true;
        private String documentKey;

        @Override public boolean isEnabled() { return true; }
        @Override public boolean isAvailable() { return true; }
        @Override public float[] embedQuery(String text) { return vector(1, 0); }
        @Override public String embeddingIndexKey() { return indexing ? documentKey : CURRENT_KEY; }

        @Override
        public float[] embedDocument(String text) {
            if (!indexing) return vector(1, 0);
            if (text.contains("foreign relation")) {
                documentKey = CURRENT_KEY;
                return vector(1, 0);
            }
            if (text.startsWith("Current profile")) {
                documentKey = CURRENT_KEY;
                return vector(0.8f, 0.6f);
            }
            if (text.startsWith("Wrong profile")) {
                documentKey = WRONG_KEY;
                return vector(1, 0);
            }
            documentKey = null;
            return text.startsWith("Legacy untagged") ? vector(1, 0) : vector(0, 1);
        }
    }
}
