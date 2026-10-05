package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.model.PrimaryRepositorySeedRelationListener;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.provenance.*;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.taxonomy.catalog.provenance.CatalogueSourceJournal.SourceUse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real MVCC database, repository, journal and Spring transaction interception. */
class CatalogueSnapshotConsistencyTest {
    private static final CatalogueSourceIdentity OPERATION =
            new CatalogueSourceIdentity("architecture-repo", "workspace", "draft", "git-commit");
    private static final Set<String> ROOTS = Set.of("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA");

    @Test
    void retainsActualCatalogueSourcesSeparatelyFromTheArchitectureGitSource() throws Exception {
        try (var db = new Database()) {
            var imported = db.importGeneration("generation-one");
            var roots = db.capture();
            for (var root : roots) {
                var json = new ObjectMapper().valueToTree(root);
                assertThat(json.hasNonNull("catalogueProvenance"))
                        .as("Each root must retain genuine catalogue source evidence, not just the operation Git label")
                        .isTrue();
                assertThat(json.path("catalogueProvenance").path("id").asText()).isEqualTo(imported.id());
                assertThat(json.path("catalogueProvenance").path("workbook").path("sha256").asText())
                        .isEqualTo(imported.workbook().sha256());
                assertThat(root.source()).isEqualTo(OPERATION);
            }
        }
    }

    @Test
    void importBetweenRootReadsCannotProduceMixedGenerations() throws Exception {
        try (var db = new Database()) {
            db.importGeneration("generation-one");
            var firstRead = new CountDownLatch(1);
            var resumeRead = new CountDownLatch(1);
            var first = new AtomicBoolean(true);
            db.afterRootRead = () -> {
                if (first.compareAndSet(true, false)) { firstRead.countDown(); await(resumeRead); }
            };
            var threads = Executors.newFixedThreadPool(2,
                    Thread.ofPlatform().daemon(true).name("catalogue-import-race-", 0).factory());
            var tasks = new ArrayList<Future<?>>();
            try {
                var capture = threads.submit(db::capture);
                tasks.add(capture);
                assertThat(firstRead.await(5, TimeUnit.SECONDS)).isTrue();
                var writerStarted = new CountDownLatch(1);
                var writerFinished = new CountDownLatch(1);
                var importing = threads.submit(() -> {
                    writerStarted.countDown();
                    try { return db.importGeneration("generation-two"); }
                    finally { writerFinished.countDown(); }
                });
                tasks.add(importing);
                assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();
                // The unfixed capture lets the import commit while its first root is already read.
                writerFinished.await(1, TimeUnit.SECONDS);
                resumeRead.countDown();
                var captured = capture.get(10, TimeUnit.SECONDS);
                importing.get(10, TimeUnit.SECONDS);
                assertThat(captured).hasSize(8);
                assertThat(captured.stream().flatMap(root -> root.nodes().stream()).map(RootCatalogueSnapshot.Node::nameEn))
                        .containsOnly("generation-one");
                assertThat(db.capture().stream().flatMap(root -> root.nodes().stream()).map(RootCatalogueSnapshot.Node::nameEn))
                        .containsOnly("generation-two");
            } finally {
                resumeRead.countDown();
                tasks.forEach(task -> task.cancel(true));
                threads.shutdownNow();
            }
        }
    }

    @Test
    void startupReconciliationTakesTheGenerationGateBeforeReadingCatalogueRows() throws Exception {
        try (var db = new Database()) {
            db.importGeneration("generation-one");
            var firstRead = new CountDownLatch(1);
            var resumeRead = new CountDownLatch(1);
            var importerRead = new CountDownLatch(1);
            var first = new AtomicBoolean(true);
            db.afterRootRead = () -> {
                if (first.compareAndSet(true, false)) { firstRead.countDown(); await(resumeRead); }
            };
            db.beforeCount = importerRead::countDown;
            var threads = Executors.newFixedThreadPool(2,
                    Thread.ofPlatform().daemon(true).name("catalogue-startup-race-", 0).factory());
            var tasks = new ArrayList<Future<?>>();
            try {
                var capture = threads.submit(db::capture);
                tasks.add(capture);
                assertThat(firstRead.await(5, TimeUnit.SECONDS)).isTrue();
                var importer = db.importer();
                var importing = threads.submit(importer::initOnStartup);
                tasks.add(importing);
                assertThat(importerRead.await(200, TimeUnit.MILLISECONDS))
                        .as("Importer must acquire the catalogue gate before any row read or write")
                        .isFalse();
                resumeRead.countDown();
                assertThat(capture.get(10, TimeUnit.SECONDS)).hasSize(8);
                importing.get(10, TimeUnit.SECONDS);
                assertThat(importer.isInitialized()).isTrue();
                assertThat(importerRead.getCount()).isZero();
            } finally {
                resumeRead.countDown();
                tasks.forEach(task -> task.cancel(true));
                threads.shutdownNow();
            }
        }
    }

    @Test
    void rejectsMissingGenerationAndCoordinatorOverlayDriftBeforeReadingNodes() {
        try (var db = new Database()) {
            assertThatThrownBy(db::capture).isInstanceOf(IllegalStateException.class).hasMessageContaining("generation");
            db.importGeneration("generation-one");
            db.tx.executeWithoutResult(status -> db.journal.reconcileOverlay(SourceUse.applied(bytes("different overlay"))));
            db.afterRootRead = () -> { throw new AssertionError("Must reject overlay drift before node capture"); };
            assertThatThrownBy(db::capture).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("overlay");
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Fixture latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static CatalogueSourceBytes bytes(String text) {
        try { return CatalogueSourceBytes.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    static final class Database implements AutoCloseable {
        final org.hibernate.SessionFactory factory;
        final jakarta.persistence.EntityManager em;
        final TransactionTemplate tx;
        final JpaTransactionManager transactions;
        final CatalogueSourceJournal journal;
        final CatalogueSnapshotService snapshots;
        final TaxonomyNodeRepository repository;
        final CatalogueOverlayService overlay;
        volatile Runnable afterRootRead = () -> { };
        volatile Runnable beforeCount = () -> { };

        Database() {
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("seedListener", new PrimaryRepositorySeedRelationListener(beans.getBeanProvider(SystemRepositoryService.class)));
            var configuration = new Configuration()
                    .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:catalogue-freeze-" + UUID.randomUUID() + ";hsqldb.tx=mvcc")
                    .setProperty("hibernate.connection.username", "sa").setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .setProperty("hibernate.search.enabled", "false")
                    .addAnnotatedClass(TaxonomyNode.class).addAnnotatedClass(TaxonomyRelation.class)
                    .addAnnotatedClass(CatalogueSourceBlob.class).addAnnotatedClass(CatalogueSourceRevision.class)
                    .addAnnotatedClass(CatalogueSourceState.class);
            configuration.getProperties().put("hibernate.resource.beans.container", new SpringBeanContainer(beans));
            // Same connection-hold settings used by Spring's application EMF (needed for isolation control).
            configuration.getProperties().putAll(new HibernateJpaVendorAdapter().getJpaPropertyMap());
            factory = configuration.buildSessionFactory();
            em = SharedEntityManagerCreator.createSharedEntityManager(factory);
            transactions = new JpaTransactionManager(factory);
            transactions.setJpaDialect(new HibernateJpaDialect());
            tx = new TransactionTemplate(transactions);
            journal = new CatalogueSourceJournal(factory);
            var actualRepository = new JpaRepositoryFactory(em).getRepository(TaxonomyNodeRepository.class);
            repository = (TaxonomyNodeRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{TaxonomyNodeRepository.class}, (proxy, method, args) -> {
                        try {
                            if (method.getName().equals("count")) beforeCount.run();
                            Object result = method.invoke(actualRepository, args);
                            if (method.getName().equals("findByTaxonomyRootOrderByLevelAscNameEnAsc")) afterRootRead.run();
                            return result;
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            overlay = new CatalogueOverlayService(new ObjectMapper(), new DefaultResourceLoader(), false, "unused");
            var target = new CatalogueSnapshotService(repository, overlay, CatalogueRuntimePolicy.fullCatalogue(), journal);
            var proxy = new ProxyFactory(target);
            proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            snapshots = (CatalogueSnapshotService) proxy.getProxy();
        }

        CatalogueSourceJournal.Snapshot importGeneration(String title) {
            return tx.execute(status -> {
                // The journal lock is taken before writes, as a production catalogue importer must do.
                journal.lockForMutation();
                for (String root : ROOTS) {
                    var rows = em.createQuery("select n from TaxonomyNode n where n.code=:root", TaxonomyNode.class)
                            .setParameter("root", root).getResultList();
                    if (rows.isEmpty()) {
                        var node = CatalogueSnapshotServiceTest.node(root, root, null, title); em.persist(node);
                    } else rows.getFirst().setNameEn(title);
                }
                return journal.initialize(bytes(title), SourceUse.notUsed(), SourceUse.notUsed());
            });
        }

        List<RootCatalogueSnapshot> capture() {
            return snapshots.captureRoots(OPERATION, ROOTS);
        }

        TaxonomyService importer() {
            var importer = new TaxonomyService(repository, mock(TaxonomyRelationRepository.class), new AppInitializationStateService());
            ReflectionTestUtils.setField(importer, "entityManager", em);
            ReflectionTestUtils.setField(importer, "transactionManager", transactions);
            ReflectionTestUtils.setField(importer, "catalogueOverlayService", overlay);
            ReflectionTestUtils.setField(importer, "catalogueSourceJournal", journal);
            ReflectionTestUtils.setField(importer, "catalogueResource", "fixture");
            return importer;
        }

        @Override public void close() { factory.close(); }
    }
}
