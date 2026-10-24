package com.taxonomy.search;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.model.PrimaryRepositorySeedRelationListener;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.relations.service.GraphSearchAuthorityService;
import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.model.RelationDecisionProjectionCheckpoint;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.relations.service.RelationProjectionRecoveryService;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.error.SearchUnavailableException;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.HashMap;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import com.taxonomy.search.config.HibernateSearchAnalysisConfigurer;
import com.taxonomy.search.config.SpringContextHolder;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.RepositoryScope;
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
import static org.assertj.core.api.Assertions.within;

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
    private static final RepositoryContext CENTRAL_CONTEXT =
            RepositoryContext.centralRead(CURRENT_REPOSITORY, "draft", "fixture-user");

    private final Map<RepositoryContext, String> heads = new HashMap<>();
    private RelationProjectionReadService relationReads;
    private RelationBranchProjectionReadinessService readiness;
    private final RelationProjectionRecoveryService recovery = mock(RelationProjectionRecoveryService.class);
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
                .addAnnotatedClass(SystemRepository.class)
                .addAnnotatedClass(RelationDecisionProjection.class)
                .addAnnotatedClass(RelationDecisionProjectionCheckpoint.class)
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
        var shared = SharedEntityManagerCreator.createSharedEntityManager(factory);
        var repositories = new JpaRepositoryFactory(shared);
        WorkspaceDslReadPort reads = selected -> {
            var read = mock(WorkspaceDslReadPort.RepositoryRead.class);
            try { when(read.currentHead()).thenAnswer(call -> heads.get(selected)); }
            catch (java.io.IOException impossible) { throw new AssertionError(impossible); }
            return read;
        };
        readiness = new RelationBranchProjectionReadinessService(reads,
                repositories.getRepository(RelationDecisionProjectionRepository.class),
                repositories.getRepository(RelationDecisionProjectionCheckpointRepository.class));
        var repositoryService = mock(SystemRepositoryService.class);
        var primary = new SystemRepository();
        primary.setRepositoryId(CURRENT_REPOSITORY);
        primary.setDefaultBranch("draft");
        when(repositoryService.getPrimaryRepository()).thenReturn(primary);
        var nodeRepository = repositories.getRepository(TaxonomyNodeRepository.class);
        var legacyRelations = new TaxonomyRelationService(
                repositories.getRepository(TaxonomyRelationRepository.class), nodeRepository, repositoryService);
        relationReads = new RelationProjectionReadService(readiness, recovery, legacyRelations,
                nodeRepository, repositoryService);
        heads.put(CENTRAL_CONTEXT, "a".repeat(40));
        graphSearch = new GraphSearchService(embeddings,
                new GraphSearchAuthorityService(relationReads, readiness, new JpaTransactionManager(factory)));
        ReflectionTestUtils.setField(graphSearch, "entityManager", shared);
        var proxy = new ProxyFactory(graphSearch);
        proxy.addAdvice(new TransactionInterceptor(new JpaTransactionManager(factory),
                new AnnotationTransactionAttributeSource()));
        graphSearch = (GraphSearchService) proxy.getProxy();

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
        var scores = embeddings.scoreNodes("authored query", candidates);
        assertThat(scores.get(CURRENT_NODE)).isCloseTo(80.0, within(0.00001));
        assertThat(scores).containsEntry(WRONG_NODE, 0.0).containsEntry(LEGACY_NODE, 0.0);
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
    void readyGraphSearchRejectsUnprovedBranchVectorsAndStaleHeads() {
        var main = RepositoryContext.workspace(CURRENT_REPOSITORY, "fixture-selected-workspace", "main", "fixture-user");
        var draft = RepositoryContext.workspace(CURRENT_REPOSITORY, "fixture-selected-workspace", "draft", "fixture-user");
        project(main, "b".repeat(40), RelationType.SUPPORTS);
        project(draft, "c".repeat(40), RelationType.DEPENDS_ON);
        assertThat(graphSearch.graphSearch("authored query", 1, CENTRAL_CONTEXT).getTopRelationTypes())
                .containsExactlyInAnyOrderEntriesOf(Map.of("SUPPORTS", 1L));
        assertThat(readiness.inspect(main).rows()).hasSize(1);
        assertThat(readiness.inspect(draft).rows()).hasSize(1);
        assertAll(
                () -> assertThatThrownBy(() -> graphSearch.graphSearch("authored query", 1, main))
                        .isInstanceOf(SearchUnavailableException.class),
                () -> assertThatThrownBy(() -> graphSearch.graphSearch("authored query", 1, draft))
                        .isInstanceOf(SearchUnavailableException.class),
                () -> {
                    heads.put(draft, "d".repeat(40));
                    assertThatThrownBy(() -> graphSearch.graphSearch("authored query", 1, draft))
                            .isInstanceOf(SearchUnavailableException.class);
                });
    }

    @Test
    void exactEmptyProjectionReturnsGlobalNodesWithoutLegacyRelationStatistics() {
        var selected = RepositoryContext.workspace(CURRENT_REPOSITORY, "empty-workspace", "draft", "fixture-user");
        project(selected, "e".repeat(40), null);
        var result = graphSearch.graphSearch("authored query", 1, selected);
        assertThat(result.getMatchedNodes()).extracting(TaxonomyNodeDto::getCode).containsExactly(CURRENT_NODE);
        assertThat(result.getRelationCountByRoot()).isEmpty();
        assertThat(result.getTopRelationTypes()).isEmpty();
    }

    @Test
    void unprovedWorkspaceNondefaultAndForkScopesDoNotInheritLegacyAuthority() {
        var workspace = RepositoryContext.workspace(CURRENT_REPOSITORY, "unbuilt-workspace", "draft", "fixture-user");
        var nondefault = RepositoryContext.centralRead(CURRENT_REPOSITORY, "main", "fixture-user");
        var fork = new RepositoryContext(CURRENT_REPOSITORY, null, "draft", "fixture-user", RepositoryScope.FORK);
        for (var selected : List.of(workspace, nondefault, fork)) {
            heads.put(selected, "a".repeat(40));
            assertThatThrownBy(() -> graphSearch.graphSearch("authored query", 1, selected))
                    .isInstanceOf(SearchUnavailableException.class);
        }
    }

    @Test
    void fallbackBecomingProjectionAtTheSameHeadDuringInferenceRejects() {
        try {
            expectUnavailableDuringInference(CENTRAL_CONTEXT,
                    () -> project(CENTRAL_CONTEXT, "a".repeat(40), null));
        } finally {
            var writer = factory.createEntityManager();
            writer.getTransaction().begin();
            writer.createQuery("delete from RelationDecisionProjectionCheckpoint c where c.repositoryId = :repository and c.workspaceId is null and c.branch = 'draft'")
                    .setParameter("repository", CURRENT_REPOSITORY).executeUpdate();
            writer.getTransaction().commit();
            writer.close();
        }
    }

    @Test
    void fallbackRecoveryBeginningDuringInferenceRejects() {
        try {
            expectUnavailableDuringInference(CENTRAL_CONTEXT,
                    () -> when(recovery.pendingCount(CENTRAL_CONTEXT)).thenReturn(1L));
        } finally {
            reset(recovery);
        }
    }

    @Test
    void fallbackHeadAdvancingDuringInferenceRejects() {
        try {
            expectUnavailableDuringInference(CENTRAL_CONTEXT,
                    () -> heads.put(CENTRAL_CONTEXT, "f".repeat(40)));
        } finally {
            heads.put(CENTRAL_CONTEXT, "a".repeat(40));
        }
    }

    @Test
    void sameHeadCheckpointChangeDuringInferenceCannotReuseManagedAuthority() {
        var selected = RepositoryContext.workspace(CURRENT_REPOSITORY, "fresh-checkpoint", "draft", "fixture-user");
        project(selected, "1".repeat(40), null);
        expectUnavailableDuringInference(selected, () -> {
            // A separately committed corrupt checkpoint at the same head must
            // be observed rather than the formerly managed READY/count=0 token.
            var writer = factory.createEntityManager();
            writer.getTransaction().begin();
            var checkpoint = writer.createQuery("select c from RelationDecisionProjectionCheckpoint c where c.workspaceId = 'fresh-checkpoint'",
                    RelationDecisionProjectionCheckpoint.class).getSingleResult();
            checkpoint.setRelationCount(1);
            writer.getTransaction().commit();
            writer.close();
        });
    }

    private void expectUnavailableDuringInference(RepositoryContext selected, Runnable transition) {
        var inferred = new java.util.concurrent.atomic.AtomicBoolean();
        embeddings.onQuery = () -> {
            inferred.set(true);
            transition.run();
        };
        try {
            assertThatThrownBy(() -> graphSearch.graphSearch("authored query", 1, selected))
                    .isInstanceOf(SearchUnavailableException.class);
            assertThat(inferred).as("source was supported before inference").isTrue();
        } finally {
            embeddings.onQuery = () -> {};
        }
    }

    private void project(RepositoryContext selected, String head, RelationType type) {
        heads.put(selected, head);
        var writer = factory.createEntityManager();
        writer.getTransaction().begin();
        var checkpoint = new RelationDecisionProjectionCheckpoint();
        checkpoint.setRepositoryId(selected.repositoryId());
        checkpoint.setWorkspaceId(selected.workspaceId());
        checkpoint.setBranch(selected.branch());
        checkpoint.setAuthoritativeCommitId(head);
        checkpoint.setRelationCount(type == null ? 0 : 1);
        writer.persist(checkpoint);
        if (type != null) {
            var row = new RelationDecisionProjection();
            row.setRepositoryId(selected.repositoryId());
            row.setWorkspaceId(selected.workspaceId());
            row.setBranch(selected.branch());
            row.setAuthoritativeCommitId(head);
            row.setSourceCode(CURRENT_NODE);
            row.setTargetCode(QUERY_NODE);
            row.setRelationType(type);
            row.setRelationPresent(true);
            row.setCausationId("fixture");
            writer.persist(row);
        }
        writer.getTransaction().commit();
        writer.close();
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
        private Runnable onQuery = () -> {};

        @Override public boolean isEnabled() { return true; }
        @Override public boolean isAvailable() { return true; }
        @Override public float[] embedQuery(String text) {
            Runnable action = onQuery;
            onQuery = () -> {};
            action.run();
            return vector(1, 0);
        }
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
