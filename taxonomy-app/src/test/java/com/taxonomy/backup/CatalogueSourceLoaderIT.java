package com.taxonomy.backup;

import com.taxonomy.catalog.model.*;
import com.taxonomy.catalog.provenance.*;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal.*;
import com.taxonomy.catalog.repository.*;
import com.taxonomy.catalog.service.*;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.service.SystemRepositoryService;
import jakarta.persistence.EntityManager;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CatalogueSourceLoaderIT {
    private static final String WORKBOOK = "memory:base.xlsx", OVERLAY = "memory:overlay.json", CSV = "classpath:data/relations.csv";

    @Test void retainedWorkbookStillProducesThePublicHierarchyProvenanceAndRelationViews() throws Exception {
        try (var f = new Fixture(); var book = new XSSFWorkbook(new ByteArrayInputStream(workbook("Imported parent", true)));
             var input = new ByteArrayOutputStream()) {
            var sheet = book.getSheet("Capabilities"); var parent = sheet.getRow(1);
            parent.createCell(1).setCellValue("11111111-1111-1111-1111-111111111111");
            parent.createCell(3).setCellValue("Source description"); parent.createCell(5).setCellValue("Original dataset");
            parent.createCell(6).setCellValue("External-42"); parent.createCell(7).setCellValue("Source standard");
            parent.createCell(8).setCellValue("Source reference"); parent.createCell(9).setCellValue(4); parent.createCell(10).setCellValue("published");
            var child = sheet.createRow(2); child.createCell(0).setCellValue("CP-2"); child.createCell(2).setCellValue("Imported child");
            child.createCell(4).setCellValue("11111111-1111-1111-1111-111111111111"); child.createCell(11).setCellValue(2);
            var relation = book.getSheet("Relations").createRow(1); relation.createCell(0).setCellValue("CP-1"); relation.createCell(1).setCellValue("CP-2");
            relation.createCell(2).setCellValue("RELATED_TO"); relation.createCell(3).setCellValue("Imported link");
            book.write(input); byte[] original = input.toByteArray(); f.resources.workbook.bytes = original; f.load(false, false);
            var tree = f.tx.execute(s -> f.service.getFullTree()); assertThat(tree).hasSize(8);
            var root = tree.stream().filter(n -> n.getCode().equals("CP")).findFirst().orElseThrow();
            assertThat(root.getChildren()).hasSize(1); var parentDto = root.getChildren().getFirst();
            assertThat(parentDto.getCode()).isEqualTo("CP-1"); assertThat(parentDto.getNameEn()).isEqualTo("Imported parent");
            assertThat(parentDto.getDescriptionEn()).isEqualTo("Source description"); assertThat(parentDto.getDataset()).isEqualTo("Original dataset");
            assertThat(parentDto.getExternalId()).isEqualTo("External-42"); assertThat(parentDto.getSource()).isEqualTo("Source standard");
            assertThat(parentDto.getReference()).isEqualTo("Source reference"); assertThat(parentDto.getSortOrder()).isEqualTo(4);
            assertThat(parentDto.getChildren()).hasSize(1); var childDto = parentDto.getChildren().getFirst();
            assertThat(childDto.getParentCode()).isEqualTo("CP-1"); assertThat(childDto.getLevel()).isEqualTo(2);
            assertThat(parentDto.getOutgoingRelations()).hasSize(1);
            assertThat(parentDto.getOutgoingRelations().getFirst().getDescription()).isEqualTo("Imported link");
            assertThat(childDto.getIncomingRelations()).hasSize(1);
            var fingerprints = f.tx.execute(s -> f.service.getFingerprintTree()); assertThat(fingerprints).hasSize(8);
            var fingerprint = fingerprints.stream().filter(n -> n.getCode().equals("CP")).findFirst().orElseThrow().getChildren().getFirst();
            assertThat(fingerprint.getDescriptionEn()).isEqualTo("Source description"); assertThat(fingerprint.getChildren()).extracting(n -> n.getCode()).containsExactly("CP-2");
            f.assertInput(f.journal.current().workbook(), original);
        }
    }

    @Test void retainsTheSingleOpenedWorkbookAndOverlayThatActuallyProducedTheCatalogue() throws Exception {
        try (var f = new Fixture()) {
            byte[] workbook = workbook("Original title", true), overlay = overlay("first");
            f.resources.workbook.bytes = workbook; f.resources.workbook.afterClose = workbook("Replacement title", true);
            f.resources.overlay.bytes = overlay; f.resources.overlay.afterClose = overlay("replacement");
            f.load(true, false);
            var snapshot = f.journal.current(); assertThat(snapshot).isNotNull();
            assertThat(f.nodes.findByCode("CP-1").orElseThrow().getNameEn()).isEqualTo("Original title");
            f.assertInput(snapshot.workbook(), workbook); f.assertInput(snapshot.overlay(), overlay);
            assertThat(snapshot.relations().use()).isEqualTo(Use.NOT_USED);
            assertThat(f.resources.workbook.opens).isEqualTo(1); assertThat(f.resources.overlay.opens).isEqualTo(1);
            assertThat(f.resources.csv.opens).isZero();
        }
    }

    @Test void restartPreservesWorkbookAndCsvEvidenceWhileRecordingChangedAndDisabledOverlay() throws Exception {
        try (var f = new Fixture()) {
            f.resources.workbook.bytes = workbook("Original title", false); f.resources.csv.bytes = csv("original relation");
            f.resources.overlay.bytes = overlay("first"); f.load(true, false);
            var first = f.journal.current(); assertThat(first).isNotNull();
            f.resources.workbook.bytes = workbook("Must never be loaded", true); f.resources.csv.bytes = csv("must never be loaded");
            byte[] changed = overlay("second"); f.resources.overlay.bytes = changed; f.load(true, false);
            var second = f.journal.current(); assertThat(second).isNotNull();
            assertThat(second.workbook()).isEqualTo(first.workbook()); assertThat(second.relations()).isEqualTo(first.relations());
            f.assertInput(second.overlay(), changed); assertThat(second.id()).isNotEqualTo(first.id());
            f.load(true, false); assertThat(f.count("catalogue_source_revision")).isEqualTo(2);
            f.load(false, false); assertThat(f.journal.current().overlay().use()).isEqualTo(Use.NOT_USED);
            assertThat(f.journal.current().workbook()).isEqualTo(first.workbook());
            assertThat(f.resources.workbook.opens).isEqualTo(1); assertThat(f.resources.csv.opens).isEqualTo(1);
            assertThat(f.nodes.findByCode("CP-1").orElseThrow().getNameEn()).isEqualTo("Original title");
            assertThat(f.jdbc.queryForObject("select description from taxonomy_relation", String.class)).isEqualTo("original relation");
        }
    }

    @Test void legacyReuseDoesNotInventOriginalInputFromCurrentlyConfiguredFiles() throws Exception {
        try (var f = new Fixture()) {
            f.tx.executeWithoutResult(s -> { var node = new TaxonomyNode(); node.setCode("CP"); node.setNameEn("Legacy"); node.setTaxonomyRoot("CP"); node.setLevel(0); f.em.persist(node); });
            f.resources.workbook.bytes = workbook("Unrelated configured catalogue", true); f.load(false, false);
            var snapshot = f.journal.current(); assertThat(snapshot).isNotNull();
            assertThat(snapshot.workbook().use()).isEqualTo(Use.NOT_RETAINED); assertThat(snapshot.relations().use()).isEqualTo(Use.NOT_RETAINED);
            assertThat(snapshot.overlay().use()).isEqualTo(Use.NOT_USED); assertThat(f.count("catalogue_source_blob")).isZero();
            assertThat(f.resources.workbook.opens).isZero(); assertThat(f.resources.csv.opens).isZero();
        }
    }

    @Test void csvFallbackRetainsItsExactConsumedBytesAndMaterializedRelation() throws Exception {
        try (var f = new Fixture()) {
            f.resources.workbook.bytes = workbook("Original title", false); byte[] csv = csv("actual CSV relation");
            f.resources.csv.bytes = csv; f.resources.csv.afterClose = csv("replacement CSV relation"); f.load(false, false);
            var snapshot = f.journal.current(); assertThat(snapshot).isNotNull(); f.assertInput(snapshot.relations(), csv);
            assertThat(f.jdbc.queryForObject("select description from taxonomy_relation", String.class)).isEqualTo("actual CSV relation");
            assertThat(f.resources.csv.opens).isEqualTo(1);
        }
    }

    @Test void absentOptionalCsvIsDistinguishedFromUnreadableInput() throws Exception {
        try (var f = new Fixture()) {
            f.resources.workbook.bytes = workbook("Original title", false); f.resources.csv.present = false; f.load(false, false);
            var snapshot = f.journal.current(); assertThat(snapshot).isNotNull(); assertThat(snapshot.relations().use()).isEqualTo(Use.NOT_USED);
            assertThat(f.resources.csv.opens).isZero(); assertThat(f.count("taxonomy_relation")).isZero();
        }
        try (var f = new Fixture()) {
            f.resources.workbook.bytes = workbook("Original title", false); f.resources.csv.openFailure = new IOException("secret CSV location"); f.load(false, false);
            var snapshot = f.journal.current(); assertThat(snapshot).isNotNull(); assertThat(snapshot.relations().use()).isEqualTo(Use.NOT_RETAINED);
            assertThat(snapshot.relations().sha256()).isNull(); assertThat(f.count("taxonomy_relation")).isZero();
        }
    }

    @Test void failedForcedReplacementRollsBackCatalogueDeletionAndAllSourceEvidence() throws Exception {
        try (var f = new Fixture()) {
            f.resources.workbook.bytes = workbook("Original title", true); f.load(false, false);
            var previous = f.journal.current(); assertThat(previous).isNotNull();
            f.resources.workbook.bytes = "not an Excel workbook".getBytes(StandardCharsets.UTF_8);
            assertThat(f.start(false, true).getState()).isEqualTo(AppInitializationStateService.State.FAILED);
            assertThat(f.journal.current()).isEqualTo(previous); assertThat(f.count("catalogue_source_blob")).isEqualTo(1);
            assertThat(f.nodes.findByCode("CP-1").orElseThrow().getNameEn()).isEqualTo("Original title");
        }
    }

    @Test void successfulForcedReplacementKeepsEarlierBytesAndAdvancesCurrentPointer() throws Exception {
        try (var f = new Fixture()) {
            byte[] original = workbook("Original title", true); f.resources.workbook.bytes = original; f.load(false, false);
            var first = f.journal.current(); assertThat(first).isNotNull();
            byte[] replacement = workbook("Replacement title", true); f.resources.workbook.bytes = replacement; f.load(false, true);
            f.assertInput(f.journal.current().workbook(), replacement); f.assertInput(first.workbook(), original);
            assertThat(f.count("catalogue_source_revision")).isEqualTo(2);
            assertThat(f.nodes.findByCode("CP-1").orElseThrow().getNameEn()).isEqualTo("Replacement title");
        }
    }

    @Test void inputOpenFailureDoesNotExposeConfiguredResourceDiagnostics() throws Exception {
        try (var f = new Fixture()) {
            f.resources.workbook.openFailure = new IOException("https://user:password@private.example/base.xlsx");
            var state = f.start(false, false); assertThat(state.getState()).isEqualTo(AppInitializationStateService.State.FAILED);
            assertThat(state.getError()).doesNotContain("password", "private.example");
            assertThat(f.count("taxonomy_node")).isZero(); assertThat(f.journal.current()).isNull();
        }
    }

    @Test void cancelledCsvReadRollsBackEvenThoughAnOrdinaryOptionalCsvReadMayFail() throws Exception {
        try (var f = new Fixture()) {
            f.resources.workbook.bytes = workbook("Original title", false);
            f.resources.csv.openFailure = new InterruptedIOException("secret interrupted input");
            var state = f.start(false, false);
            assertThat(state.getState()).isEqualTo(AppInitializationStateService.State.FAILED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            Thread.interrupted(); assertThat(f.count("taxonomy_node")).isZero(); assertThat(f.journal.current()).isNull();
        } finally { Thread.interrupted(); }
    }

    static byte[] workbook(String title, boolean relationsSheet) throws Exception {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("Capabilities"); sheet.createRow(0).createCell(0).setCellValue("Code"); var row = sheet.createRow(1);
            row.createCell(0).setCellValue("CP-1"); row.createCell(2).setCellValue(title); row.createCell(4).setCellValue("CP"); row.createCell(11).setCellValue(1);
            if (relationsSheet) book.createSheet("Relations").createRow(0).createCell(0).setCellValue("Source");
            book.write(output); return output.toByteArray();
        }
    }
    static byte[] overlay(String version) { return ("{\"schemaVersion\":2,\"mode\":\"OVERLAY\",\"baseCatalogue\":\"base.xlsx\",\"mappingVersion\":\"" + version + "\",\"nodePatches\":[]}").getBytes(StandardCharsets.UTF_8); }
    static byte[] csv(String description) { return ("SourceCode,TargetCode,RelationType,Description\nCP,CP-1,RELATED_TO," + description + "\n").getBytes(StandardCharsets.UTF_8); }

    static final class ChangingResource extends AbstractResource {
        byte[] bytes = new byte[0], afterClose; boolean present = true; IOException openFailure; int opens; final String filename;
        ChangingResource(String filename) { this.filename = filename; }
        @Override public boolean exists() { return present; }
        @Override public String getDescription() { return "test input"; }
        @Override public String getFilename() { return filename; }
        @Override public InputStream getInputStream() throws IOException {
            opens++; if (openFailure != null) throw openFailure;
            return new ByteArrayInputStream(bytes) { @Override public void close() { if (afterClose != null) bytes = afterClose; } };
        }
    }
    static final class Inputs extends DefaultResourceLoader {
        final ChangingResource workbook = new ChangingResource("base.xlsx"), overlay = new ChangingResource("overlay.json"), csv = new ChangingResource("relations.csv");
        @Override public Resource getResource(String location) {
            return switch (location) { case WORKBOOK -> workbook; case OVERLAY -> overlay; case CSV -> csv; default -> throw new AssertionError("Unexpected resource: " + location); };
        }
    }
    static final class Fixture implements AutoCloseable {
        final Inputs resources = new Inputs(); final JDBCDataSource database = new JDBCDataSource();
        final org.hibernate.SessionFactory factory; final EntityManager em; final JdbcTemplate jdbc; final TransactionTemplate tx;
        final CatalogueSourceJournal journal; final TaxonomyNodeRepository nodes; final TaxonomyRelationRepository relations;
        TaxonomyService service;
        final JpaTransactionManager transactionManager;
        Fixture() {
            database.setUrl("jdbc:hsqldb:mem:catalogue-loader-" + UUID.randomUUID()); database.setUser("sa");
            var config = new Configuration().setProperty("hibernate.connection.url", database.getUrl())
                    .setProperty("hibernate.connection.username", "sa").setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .setProperty("hibernate.search.enabled", "false")
                    .addAnnotatedClass(CatalogueSourceBlob.class).addAnnotatedClass(CatalogueSourceRevision.class).addAnnotatedClass(CatalogueSourceState.class)
                    .addAnnotatedClass(TaxonomyNode.class).addAnnotatedClass(TaxonomyRelation.class).addAnnotatedClass(SystemRepository.class);
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("seedListener", new PrimaryRepositorySeedRelationListener(beans.getBeanProvider(SystemRepositoryService.class)));
            config.getProperties().put("hibernate.resource.beans.container", new SpringBeanContainer(beans)); factory = config.buildSessionFactory();
            em = SharedEntityManagerCreator.createSharedEntityManager(factory); jdbc = new JdbcTemplate(database);
            transactionManager = new JpaTransactionManager(factory); tx = new TransactionTemplate(transactionManager); journal = new CatalogueSourceJournal(factory);
            var repositories = new JpaRepositoryFactory(em); nodes = repositories.getRepository(TaxonomyNodeRepository.class); relations = repositories.getRepository(TaxonomyRelationRepository.class);
            var systems = new SystemRepositoryService(repositories.getRepository(SystemRepositoryRepository.class)); beans.registerSingleton("systems", systems);
            tx.executeWithoutResult(s -> systems.ensureSystemRepository());
        }
        AppInitializationStateService start(boolean overlayEnabled, boolean reload) {
            var state = new AppInitializationStateService(); var loader = new TaxonomyService(nodes, relations, state);
            service = loader;
            ReflectionTestUtils.setField(loader, "entityManager", em); ReflectionTestUtils.setField(loader, "transactionManager", transactionManager);
            ReflectionTestUtils.setField(loader, "resourceLoader", resources); ReflectionTestUtils.setField(loader, "catalogueResource", WORKBOOK);
            ReflectionTestUtils.setField(loader, "catalogueSourceJournal", journal); ReflectionTestUtils.setField(loader, "reloadExisting", reload);
            ReflectionTestUtils.setField(loader, "catalogueOverlayService", new CatalogueOverlayService(new ObjectMapper(), resources, overlayEnabled, OVERLAY));
            loader.initOnStartup(); return state;
        }
        void load(boolean overlayEnabled, boolean reload) { var state = start(overlayEnabled, reload); assertThat(state.getState()).as("initialization error: %s", state.getError()).isEqualTo(AppInitializationStateService.State.READY); }
        long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
        void assertInput(InputReference reference, byte[] expected) throws Exception {
            assertThat(reference.use()).isEqualTo(Use.APPLIED); assertThat(reference.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)));
            assertThat(reference.length()).isEqualTo(expected.length);
            byte[] actual = jdbc.queryForObject("select payload from catalogue_source_blob where sha256=?", (r, n) -> r.getBytes(1), reference.sha256());
            assertThat(actual).isEqualTo(expected);
        }
        @Override public void close() { factory.close(); }
    }
}
