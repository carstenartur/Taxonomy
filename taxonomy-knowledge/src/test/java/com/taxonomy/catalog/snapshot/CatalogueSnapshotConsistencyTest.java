package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.controller.ApiController;
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

    @Test
    void provisionalMappingQueueKeepsTheOriginalParentAndExactGeneration() throws Exception {
        var overlayJson = """
                {"schemaVersion":2,"mode":"OVERLAY","baseCatalogue":"source.xlsx","mappingVersion":"review-v2",
                 "nodePatches":[
                   {"code":"IP-3","expectedTitle":"Field report","expectedState":"draft","parentCode":"IP-1",
                    "analysisRole":"PRODUCT","secondaryClassificationCodes":["IP-2"],"confidence":0.61,
                    "reviewRequired":true,"justification":"Proposed from the product title"},
                   {"code":"IP-4","expectedTitle":"Desk report","expectedState":"draft","parentCode":"IP-1",
                    "analysisRole":"PRODUCT","confidence":0.9,"reviewRequired":false,"justification":"Technical mapping"}]}
                """.getBytes(StandardCharsets.UTF_8);
        var workbookBytes = new ByteArrayOutputStream();
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Information Products");
            sheet.createRow(0).createCell(0).setCellValue("Page");
            var rows = List.of(
                    List.of("IP-1", "Operations", "IP", "approved"),
                    List.of("IP-2", "Source group", "IP", "approved"),
                    List.of("IP-3", "Field report", "IP-2", "draft"),
                    List.of("IP-4", "Desk report", "IP-2", "draft"));
            for (var rowValues : rows) {
                var row = sheet.createRow(sheet.getLastRowNum() + 1);
                row.createCell(0).setCellValue(rowValues.get(0));
                row.createCell(2).setCellValue(rowValues.get(1));
                row.createCell(4).setCellValue(rowValues.get(2));
                row.createCell(10).setCellValue(rowValues.get(3));
            }
            workbook.createSheet("Relations");
            workbook.write(workbookBytes);
        }
        var resources = new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                return new ByteArrayResource(location.equals("source.xlsx") ? workbookBytes.toByteArray() : overlayJson) {
                    @Override public String getFilename() { return location; }
                };
            }
        };
        try (var db = new Database()) {
            var taxonomy = db.importer();
            ReflectionTestUtils.setField(taxonomy, "resourceLoader", resources);
            ReflectionTestUtils.setField(taxonomy, "catalogueResource", "source.xlsx");
            ReflectionTestUtils.setField(taxonomy, "catalogueOverlayService",
                    new CatalogueOverlayService(new ObjectMapper(), resources, true, "overlay.json"));
            taxonomy.initOnStartup();
            assertThat(taxonomy.isInitialized()).isTrue();
            var generation = db.journal.current();
            var version = db.em.createQuery("select s.rowVersion from CatalogueSourceState s", Long.class).getSingleResult();
            var mvc = MockMvcBuilders.standaloneSetup(new ApiController(taxonomy, new StaticMessageSource())).build();
            String path = "/api/admin/catalogue/provisional-information-products";

            mvc.perform(get(path).param("q", " FIELD "))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.catalogueRevision").value(generation.id()))
                    .andExpect(jsonPath("$.workbookUse").value("APPLIED"))
                    .andExpect(jsonPath("$.workbookSha256").value(generation.workbook().sha256()))
                    .andExpect(jsonPath("$.overlaySha256").value(generation.overlay().sha256()))
                    .andExpect(jsonPath("$.mappingVersion").value("review-v2"))
                    .andExpect(jsonPath("$.mappings.length()").value(1))
                    .andExpect(jsonPath("$.mappings[0].productCode").value("IP-3"))
                    .andExpect(jsonPath("$.mappings[0].productTitle").value("Field report"))
                    .andExpect(jsonPath("$.mappings[0].sourceParentReference").value("IP-2"))
                    .andExpect(jsonPath("$.mappings[0].proposedParentCode").value("IP-1"))
                    .andExpect(jsonPath("$.mappings[0].secondaryClassificationCodes[0]").value("IP-2"))
                    .andExpect(jsonPath("$.mappings[0].confidence").value(0.61))
                    .andExpect(jsonPath("$.mappings[0].state").value("PROVISIONAL"))
                    .andExpect(jsonPath("$.mappings[0].sourceState").value("draft"))
                    .andExpect(jsonPath("$.mappings[0].justification").value("Proposed from the product title"));
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.mappings.length()").value(1));
            mvc.perform(get(path).param("q", "Desk report"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.mappings.length()").value(0));
            mvc.perform(get(path).param("q", "a".repeat(161))).andExpect(status().isBadRequest());
            mvc.perform(post(path)).andExpect(status().isMethodNotAllowed());
            assertThat(db.journal.current()).isEqualTo(generation);
            assertThat(db.em.createQuery("select s.rowVersion from CatalogueSourceState s", Long.class).getSingleResult())
                    .isEqualTo(version);

            // A backfilled catalogue generation can predate workbook byte retention.
            db.tx.executeWithoutResult(status -> db.em.createNativeQuery("""
                    update catalogue_source_revision set workbook_sha256=null, workbook_use='NOT_RETAINED' where id=:id
                    """).setParameter("id", generation.id()).executeUpdate());
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.workbookUse").value("NOT_RETAINED"))
                    .andExpect(jsonPath("$.mappings[0].sourceParentReference").doesNotExist())
                    .andExpect(jsonPath("$.mappings[0].proposedParentCode").value("IP-1"));

            db.afterRootRead = () -> db.tx.executeWithoutResult(status ->
                    db.journal.reconcileOverlay(SourceUse.applied(bytes("different overlay"))));
            mvc.perform(get(path)).andExpect(status().isServiceUnavailable());
            db.afterRootRead = () -> { throw new AssertionError("Overlay drift must fail before reading mappings"); };
            mvc.perform(get(path)).andExpect(status().isServiceUnavailable());
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
