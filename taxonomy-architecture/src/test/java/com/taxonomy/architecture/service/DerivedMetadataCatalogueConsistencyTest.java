package com.taxonomy.architecture.service;

import com.taxonomy.catalog.model.*;
import com.taxonomy.catalog.provenance.*;
import com.taxonomy.catalog.repository.*;
import com.taxonomy.relations.model.RequirementCoverage;
import com.taxonomy.relations.repository.RequirementCoverageRepository;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;

import static com.taxonomy.catalog.provenance.CatalogueSourceJournal.SourceUse;
import static org.assertj.core.api.Assertions.*;

/** Real ORM updates must not restore stale official catalogue fields while saving graph metadata. */
class DerivedMetadataCatalogueConsistencyTest {
    @Test
    void concurrentImportRetainsItsCatalogueColumnsAndJournalIdentity() throws Exception {
        try (var db = new Database()) {
            var metadataRead = new CountDownLatch(1);
            var resumeMetadata = new CountDownLatch(1);
            db.afterNodeRead = () -> { metadataRead.countDown(); await(resumeMetadata); };
            var threads = Executors.newFixedThreadPool(2,
                    Thread.ofPlatform().daemon(true).name("derived-metadata-race-", 0).factory());
            var tasks = new ArrayList<Future<?>>();
            try {
                var metadata = threads.submit(db.service::recomputeAll);
                tasks.add(metadata);
                assertThat(metadataRead.await(5, TimeUnit.SECONDS)).isTrue();
                var importFinished = new CountDownLatch(1);
                var importing = threads.submit(() -> {
                    try { return db.tx.execute(status -> {
                        db.journal.lockForMutation();
                        db.nodes.findByCode("CP").orElseThrow().setNameEn("generation-two");
                        return db.journal.initialize(bytes("generation-two"), SourceUse.notUsed(), SourceUse.notUsed());
                    }); } finally { importFinished.countDown(); }
                });
                tasks.add(importing);
                importFinished.await(1, TimeUnit.SECONDS);
                resumeMetadata.countDown();
                assertThat(metadata.get(10, TimeUnit.SECONDS)).isEqualTo(1);
                var imported = importing.get(10, TimeUnit.SECONDS);
                var actual = db.nodes.findByCode("CP").orElseThrow();
                assertThat(actual.getNameEn()).isEqualTo("generation-two");
                assertThat(actual.getGraphRole()).isEqualTo("isolated");
                assertThat(db.journal.current()).isEqualTo(imported);
            } finally {
                resumeMetadata.countDown();
                tasks.forEach(task -> task.cancel(true));
                threads.shutdownNow();
            }
        }
    }

    @Test
    void waitsForCatalogueGateBeforeTraversingRelationNodes() throws Exception {
        try (var db = new Database()) {
            var relationRead = new CountDownLatch(1);
            db.beforeRelationRead = relationRead::countDown;
            var threads = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon(true).name("derived-metadata-gate-", 0).factory());
            var tasks = new ArrayList<Future<?>>();
            try {
                Future<Integer> result = db.tx.execute(status -> {
                    db.journal.lockForCapture();
                    var running = threads.submit(db.service::recomputeAll);
                    tasks.add(running);
                    try {
                        assertThat(relationRead.await(200, TimeUnit.MILLISECONDS))
                                .as("No relation traversal can load managed catalogue nodes before the gate")
                                .isFalse();
                    } catch (InterruptedException failure) { throw new AssertionError(failure); }
                    return running;
                });
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(relationRead.getCount()).isZero();
            } finally {
                tasks.forEach(task -> task.cancel(true));
                threads.shutdownNow();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Fixture latch timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static CatalogueSourceBytes bytes(String value) {
        try { return CatalogueSourceBytes.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static final class Database implements AutoCloseable {
        final org.hibernate.SessionFactory factory;
        final TransactionTemplate tx;
        final CatalogueSourceJournal journal;
        final TaxonomyNodeRepository nodes;
        final DerivedMetadataService service;
        volatile Runnable afterNodeRead = () -> { };
        volatile Runnable beforeRelationRead = () -> { };

        Database() {
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("seedListener", new PrimaryRepositorySeedRelationListener(beans.getBeanProvider(SystemRepositoryService.class)));
            var config = new Configuration()
                    .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:derived-freeze-" + UUID.randomUUID() + ";hsqldb.tx=mvcc")
                    .setProperty("hibernate.connection.username", "sa").setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .setProperty("hibernate.search.enabled", "false")
                    .addAnnotatedClass(CatalogueSourceBlob.class).addAnnotatedClass(CatalogueSourceRevision.class)
                    .addAnnotatedClass(CatalogueSourceState.class).addAnnotatedClass(TaxonomyNode.class)
                    .addAnnotatedClass(TaxonomyRelation.class).addAnnotatedClass(RequirementCoverage.class)
                    .addAnnotatedClass(SystemRepository.class);
            config.getProperties().put("hibernate.resource.beans.container", new SpringBeanContainer(beans));
            factory = config.buildSessionFactory();
            var em = SharedEntityManagerCreator.createSharedEntityManager(factory);
            var transactions = new JpaTransactionManager(factory);
            tx = new TransactionTemplate(transactions);
            journal = new CatalogueSourceJournal(factory);
            var repositories = new JpaRepositoryFactory(em);
            nodes = repositories.getRepository(TaxonomyNodeRepository.class);
            var observedNodes = observe(nodes, TaxonomyNodeRepository.class, "findAll", () -> { }, () -> afterNodeRead.run());
            var relations = observe(repositories.getRepository(TaxonomyRelationRepository.class),
                    TaxonomyRelationRepository.class, "findAll", () -> beforeRelationRead.run(), () -> { });
            var target = new DerivedMetadataService(observedNodes, relations,
                    repositories.getRepository(RequirementCoverageRepository.class), journal);
            var proxy = new ProxyFactory(target);
            proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            service = (DerivedMetadataService) proxy.getProxy();
            tx.executeWithoutResult(status -> {
                journal.lockForMutation();
                var node = new TaxonomyNode(); node.setCode("CP"); node.setTaxonomyRoot("CP");
                node.setNameEn("generation-one"); em.persist(node);
                journal.initialize(bytes("generation-one"), SourceUse.notUsed(), SourceUse.notUsed());
            });
        }

        private static <T> T observe(T target, Class<T> type, String name, Runnable before, Runnable after) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                try {
                    if (method.getName().equals(name)) before.run();
                    Object result = method.invoke(target, args);
                    if (method.getName().equals(name)) after.run();
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            }));
        }

        @Override public void close() { factory.close(); }
    }
}
