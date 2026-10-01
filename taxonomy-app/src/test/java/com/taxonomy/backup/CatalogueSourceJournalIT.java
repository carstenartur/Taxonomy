package com.taxonomy.backup;

import com.taxonomy.catalog.provenance.*;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.model.PrimaryRepositorySeedRelationListener;
import com.taxonomy.workspace.service.SystemRepositoryService;
import jakarta.persistence.*;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class CatalogueSourceJournalIT {
    @Test void exactInputSurvivesRestartAndRepeatedImportsDoNotInventHistory() throws Exception {
        try (var f = new Fixture()) {
            var workbook = bytes("original workbook"); var overlay = SourceUse.applied(bytes("original overlay"));
            var first = f.tx.execute(s -> f.journal.initialize(workbook, overlay, SourceUse.notUsed()));
            assertThat(first).isNotNull();
            assertThat(first.workbook()).isEqualTo(new InputReference(Use.APPLIED, workbook.sha256(), workbook.length()));
            assertThat(first.relations().use()).isEqualTo(Use.NOT_USED);
            assertThat(f.blob(workbook.sha256())).isEqualTo("original workbook".getBytes(StandardCharsets.UTF_8));
            assertThat(new CatalogueSourceJournal(f.factory).current()).isEqualTo(first);
            assertThat(f.tx.<Snapshot>execute(s -> f.journal.initialize(workbook, overlay, SourceUse.notUsed()))).isEqualTo(first);
            assertThat(f.count("catalogue_source_blob")).isEqualTo(2); assertThat(f.count("catalogue_source_revision")).isEqualTo(1);
        }
    }

    @Test void overlayReconciliationPreservesTheActualWorkbookAndCsvEvidence() throws Exception {
        try (var f = new Fixture()) {
            var workbook = bytes("workbook used by parser"); var csv = SourceUse.rejected(bytes("rejected relation rows"));
            var first = f.tx.execute(s -> f.journal.initialize(workbook, SourceUse.notUsed(), csv));
            assertThat(first).isNotNull();
            var overlay = SourceUse.applied(bytes("new overlay"));
            var next = f.tx.execute(s -> f.journal.reconcileOverlay(overlay));
            assertThat(next).isNotNull(); assertThat(next.id()).isNotEqualTo(first.id());
            assertThat(next.workbook()).isEqualTo(first.workbook()); assertThat(next.relations()).isEqualTo(first.relations());
            assertThat(next.overlay().sha256()).isEqualTo(overlay.input().sha256());
            assertThat(f.count("catalogue_source_revision")).isEqualTo(2); assertThat(f.count("catalogue_source_blob")).isEqualTo(3);
            var disabled = f.tx.execute(s -> f.journal.reconcileOverlay(SourceUse.notUsed()));
            assertThat(disabled.overlay().use()).isEqualTo(Use.NOT_USED);
            assertThat(f.count("catalogue_source_blob")).isEqualTo(3);
        }
    }

    @Test void legacyReconciliationCannotClaimThatConfiguredFilesWereOriginallyImported() throws Exception {
        try (var f = new Fixture()) {
            var retainedOverlay = SourceUse.applied(bytes("actually applied overlay"));
            var first = f.tx.execute(s -> f.journal.reconcileOverlay(retainedOverlay));
            assertThat(first).isNotNull();
            assertThat(first.workbook()).isEqualTo(new InputReference(Use.NOT_RETAINED, null, 0));
            assertThat(first.relations()).isEqualTo(new InputReference(Use.NOT_RETAINED, null, 0));
            assertThat(first.overlay().sha256()).isEqualTo(retainedOverlay.input().sha256());
            assertThat(f.count("catalogue_source_blob")).isEqualTo(1);
        }
    }

    @Test void rollbackRemovesBothCatalogueMutationAndNewRetainedSources() throws Exception {
        try (var f = new Fixture()) {
            var workbook = bytes("must roll back");
            assertThatThrownBy(() -> f.tx.executeWithoutResult(s -> {
                var node = new TaxonomyNode(); node.setCode("TEST"); node.setNameEn("new content"); f.em.persist(node);
                assertThat(f.journal.initialize(workbook, SourceUse.notUsed(), SourceUse.notUsed())).isNotNull();
                throw new IllegalStateException("rollback fixture");
            })).isInstanceOf(IllegalStateException.class).hasMessage("rollback fixture");
            assertThat(f.count("taxonomy_node")).isZero();
            assertThat(f.count("catalogue_source_blob")).isZero(); assertThat(f.count("catalogue_source_revision")).isZero();
            assertThat(f.journal.current()).isNull();
        }
    }

    @Test void cannotRetainEvidenceOutsideTheCatalogueTransaction() throws Exception {
        try (var f = new Fixture()) {
            assertThatThrownBy(() -> f.journal.initialize(bytes("uncommitted"), SourceUse.notUsed(), SourceUse.notUsed())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> f.journal.reconcileOverlay(SourceUse.notUsed())).isInstanceOf(IllegalStateException.class);
            assertThat(f.count("catalogue_source_blob")).isZero();
        }
    }

    @Test void replacingTheWorkbookKeepsPriorInputAsHistoryAndMovesTheCurrentPointer() throws Exception {
        try (var f = new Fixture()) {
            var oldInput = bytes("old catalogue"); var newInput = bytes("new catalogue");
            var old = f.tx.execute(s -> f.journal.initialize(oldInput, SourceUse.notUsed(), SourceUse.notUsed()));
            assertThat(old).isNotNull();
            var current = f.tx.execute(s -> f.journal.initialize(newInput, SourceUse.notUsed(), SourceUse.notUsed()));
            assertThat(current.id()).isNotEqualTo(old.id()); assertThat(f.journal.current()).isEqualTo(current);
            assertThat(f.blob(oldInput.sha256())).isEqualTo("old catalogue".getBytes(StandardCharsets.UTF_8));
            assertThat(f.count("catalogue_source_revision")).isEqualTo(2);
        }
    }

    @Test void damagedRetainedBytesCannotBeSilentlyReplacedUnderTheSameIdentity() throws Exception {
        try (var f = new Fixture()) {
            var input = bytes("original");
            assertThat(f.tx.<Snapshot>execute(s -> f.journal.initialize(input, SourceUse.notUsed(), SourceUse.notUsed()))).isNotNull();
            f.jdbc.update("update catalogue_source_blob set payload=? where sha256=?", "CORRUPTED-SECRET".getBytes(StandardCharsets.UTF_8), input.sha256());
            assertThatThrownBy(() -> f.tx.execute(s -> f.journal.initialize(input, SourceUse.notUsed(), SourceUse.notUsed())))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("SECRET");
            assertThat(f.count("catalogue_source_revision")).isEqualTo(1);
        }
    }

    @Test void brokenCurrentPointerFailsInsteadOfInventingAnUnretainedLegacySource() throws Exception {
        try (var f = new Fixture()) {
            var input = bytes("original");
            assertThat(f.tx.<Snapshot>execute(s -> f.journal.initialize(input, SourceUse.notUsed(), SourceUse.notUsed()))).isNotNull();
            f.jdbc.update("delete from catalogue_source_revision");
            assertThatThrownBy(f.journal::current).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> f.tx.execute(s -> f.journal.reconcileOverlay(SourceUse.notUsed()))).isInstanceOf(IllegalStateException.class);
        }
    }

    private static CatalogueSourceBytes bytes(String value) throws Exception { return CatalogueSourceBytes.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))); }
    private static class Fixture implements AutoCloseable {
        final JDBCDataSource database = new JDBCDataSource();
        final org.hibernate.SessionFactory factory;
        final EntityManager em;
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final CatalogueSourceJournal journal;
        Fixture() {
            database.setUrl("jdbc:hsqldb:mem:catalogue-source-" + UUID.randomUUID()); database.setUser("sa");
            var config = new Configuration().setProperty("hibernate.connection.url", database.getUrl())
                    .setProperty("hibernate.connection.username", "sa").setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .setProperty("hibernate.search.enabled", "false")
                    .addAnnotatedClass(CatalogueSourceBlob.class).addAnnotatedClass(CatalogueSourceRevision.class)
                    .addAnnotatedClass(CatalogueSourceState.class).addAnnotatedClass(TaxonomyNode.class)
                    .addAnnotatedClass(TaxonomyRelation.class);
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("seedListener", new PrimaryRepositorySeedRelationListener(beans.getBeanProvider(SystemRepositoryService.class)));
            config.getProperties().put("hibernate.resource.beans.container", new SpringBeanContainer(beans));
            factory = config.buildSessionFactory();
            jdbc = new JdbcTemplate(database); em = SharedEntityManagerCreator.createSharedEntityManager(factory);
            tx = new TransactionTemplate(new JpaTransactionManager(factory)); journal = new CatalogueSourceJournal(factory);
        }
        long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
        byte[] blob(String hash) { return jdbc.queryForObject("select payload from catalogue_source_blob where sha256=?", (r, n) -> r.getBytes(1), hash); }
        @Override public void close() { factory.close(); }
    }
}
