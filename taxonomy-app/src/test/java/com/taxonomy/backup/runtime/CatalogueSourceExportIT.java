package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.catalog.backup.CatalogueSourceBackupContributor;
import com.taxonomy.catalog.provenance.*;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal.*;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.taxonomy.backup.runtime.CurrentStateExportIT.*;

class CatalogueSourceExportIT {
    static final BackupScope SCOPE = new BackupScope.Workspace("repo-a", "private-a");
    static final String STATE = "data/knowledge/catalogue-source-state.ndjson";
    static final String REVISIONS = "data/knowledge/catalogue-source-revision.ndjson";
    static final String BLOBS = "data/knowledge/catalogue-source-blob.ndjson";

    @ParameterizedTest @EnumSource(value = BackupProfile.class, names = { "CURRENT_STATE", "INSTALLATION_CURRENT" })
    void currentKeepsOnlyScalarActiveProvenanceWithoutHistoricalInputBytes(BackupProfile profile) throws Exception {
        try (var f = new Fixture()) {
            var old = f.initialize("OLD-ORIGINAL-SECRET", "OLD-OVERLAY-SECRET");
            var current = f.initialize("CURRENT-INPUT-WITH-SUPERSEDED-SECRET", "CURRENT-OVERLAY-WITH-PAST-SECRET");
            var out = f.export(profile, Set.of(current.id()));
            assertThat(out.entries).containsOnlyKeys(STATE, REVISIONS, BLOBS);
            assertThat(out.text()).contains(current.id(), current.workbook().sha256(), "HISTORY_REQUIRED", "APPLIED")
                    .doesNotContain(old.id(), old.workbook().sha256(), "ORIGINAL-SECRET", "SUPERSEDED-SECRET", "PAST-SECRET", "files/catalogue/");
            assertThat(out.entries.get(BLOBS)).isNotNull();
            assertThat(new String(out.entries.get(BLOBS), StandardCharsets.UTF_8).lines().count()).isEqualTo(1);
        }
    }

    @Test void scopedHistoryIncludesOnlyExplicitlyAuthorizedRevisionsAndTheirRawBytes() throws Exception {
        try (var f = new Fixture()) {
            var selected = f.initialize("SELECTED-ORIGINAL", "SELECTED-OVERLAY");
            var foreign = f.initialize("UNSELECTED-ORIGINAL", "UNSELECTED-OVERLAY");
            // Unselected content must not even be opened or verified.
            f.jdbc.update("update catalogue_source_blob set payload=? where sha256=?", new byte[] { 1 }, foreign.workbook().sha256());
            var out = f.export(BackupProfile.REPOSITORY_HISTORY, Set.of(selected.id()));
            assertThat(out.entries).containsKey(STATE); assertThat(out.text()).contains(selected.id(), "SELECTED-ORIGINAL", "SELECTED-OVERLAY")
                    .doesNotContain(foreign.id(), foreign.workbook().sha256(), "UNSELECTED");
            assertThat(new String(out.entries.get(STATE), StandardCharsets.UTF_8).lines().count()).isEqualTo(1);
            assertThat(out.entries.get(path(selected.workbook()))).isEqualTo("SELECTED-ORIGINAL".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test void installationHistoryEnumeratesRetainedRevisionsAndDeduplicatesSharedOriginals() throws Exception {
        try (var f = new Fixture()) {
            var first = f.initialize("SHARED-WORKBOOK", "FIRST-OVERLAY"); var second = f.initialize("SHARED-WORKBOOK", "SECOND-OVERLAY");
            var out = new Contents();
            new CatalogueSourceBackupContributor(f.database, context -> { throw new AssertionError("Installation must use its authorized global inventory"); })
                    .write(snapshot(BackupProfile.INSTALLATION_FULL, new BackupScope.Installation()), out);
            assertThat(out.entries).hasSize(6); assertThat(out.text()).contains(first.id(), second.id(), "SHARED-WORKBOOK", "FIRST-OVERLAY", "SECOND-OVERLAY");
            assertThat(new String(out.entries.get(STATE), StandardCharsets.UTF_8)).contains(second.id()).doesNotContain(first.id());
            assertThat(out.entries.get(path(first.workbook()))).isEqualTo("SHARED-WORKBOOK".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test void selectedVersionDoesNotReadLiveDatabaseOrSelection() throws Exception {
        var out = new Contents(); new CatalogueSourceBackupContributor(unreadableDatabase(), context -> { throw new AssertionError("Live source selection"); })
                .write(snapshot(BackupProfile.SELECTED_VERSION, SCOPE), out);
        assertEmpty(out);
    }

    @ParameterizedTest @EnumSource(value = BackupProfile.class, names = { "CURRENT_STATE", "REPOSITORY_HISTORY" })
    void emptyScopedSelectionDoesNotEnumerateTheGlobalCatalogue(BackupProfile profile) throws Exception {
        var out = new Contents(); new CatalogueSourceBackupContributor(unreadableDatabase(), context -> Set.of())
                .write(snapshot(profile, SCOPE), out); assertEmpty(out);
    }

    @ParameterizedTest @EnumSource(value = BackupProfile.class, names = { "INSTALLATION_CURRENT", "INSTALLATION_FULL" })
    void legacyEvidenceExplicitlyKeepsOriginalInputsUnavailable(BackupProfile profile) throws Exception {
        try (var f = new Fixture()) {
            f.tx.executeWithoutResult(s -> f.journal.reconcileOverlay(SourceUse.notUsed()));
            var out = f.export(profile, Set.of()); assertThat(out.entries).containsOnlyKeys(STATE, REVISIONS, BLOBS);
            assertThat(out.text()).contains("NOT_RETAINED", "NOT_USED").doesNotContain("files/catalogue/");
        }
    }

    @Test void anUninitializedInstallationHasNoFabricatedOriginal() throws Exception {
        try (var f = new Fixture()) { assertEmpty(f.export(BackupProfile.INSTALLATION_FULL, Set.of())); }
    }

    @Test void currentRejectsAnExplicitHistoricalRevisionInsteadOfLeakingOldProvenance() throws Exception {
        try (var f = new Fixture()) {
            var old = f.initialize("PAST", "PAST-OVERLAY"); f.initialize("PRESENT", "PRESENT-OVERLAY"); var out = new Contents();
            assertThatThrownBy(() -> f.contributor(Set.of(old.id())).write(snapshot(BackupProfile.CURRENT_STATE, SCOPE), out)).isInstanceOf(IOException.class);
            assertThat(out.entries).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = { "1-1-1-1-1", "private-location", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", "00000000-0000-0000-0000-000000000001" })
    void invalidOrMissingServerSelectionFailsBeforeOutput(String id) throws Exception {
        try (var f = new Fixture()) {
            f.initialize("BASE", "OVERLAY"); var out = new Contents();
            assertThatThrownBy(() -> f.contributor(Set.of(id)).write(snapshot(BackupProfile.REPOSITORY_HISTORY, SCOPE), out))
                    .isInstanceOf(IOException.class).hasNoCause().hasMessageNotContaining("private-location");
            assertThat(out.entries).isEmpty();
        }
    }

    enum Corruption { PAYLOAD, LENGTH, MISSING_BLOB, USE, CREATED_AT, POINTER, SHORT_POINTER, ORPHAN_BLOB }
    @ParameterizedTest @EnumSource(Corruption.class)
    void validatesEverySelectedReferenceAndBlobBeforeTheFirstWrite(Corruption corruption) throws Exception {
        try (var f = new Fixture()) {
            var retained = f.initialize("RETAINED", "OVERLAY");
            switch (corruption) {
                case PAYLOAD -> f.jdbc.update("update catalogue_source_blob set payload=? where sha256=?", "TAMPERED".getBytes(StandardCharsets.UTF_8), retained.workbook().sha256());
                case LENGTH -> f.jdbc.update("update catalogue_source_blob set byte_length=byte_length+1");
                case MISSING_BLOB -> f.jdbc.update("delete from catalogue_source_blob where sha256=?", retained.workbook().sha256());
                case USE -> f.jdbc.update("update catalogue_source_revision set workbook_use='PRIVATE-DIAGNOSTIC'");
                case CREATED_AT -> f.jdbc.update("update catalogue_source_revision set created_at='PRIVATE-SOURCE-DIAGNOSTIC'");
                case POINTER -> f.jdbc.update("update catalogue_source_state set current_revision=?", UUID.randomUUID().toString());
                case SHORT_POINTER -> f.jdbc.update("update catalogue_source_state set current_revision='1-1-1-1-1'");
                case ORPHAN_BLOB -> f.jdbc.update("insert into catalogue_source_blob(sha256,byte_length,payload) values(?,?,?)", "a".repeat(64), 4, new byte[]{1,2,3,4});
            }
            var out = new Contents();
            assertThatThrownBy(() -> f.contributor(Set.of()).write(snapshot(BackupProfile.INSTALLATION_FULL, new BackupScope.Installation()), out))
                    .isInstanceOf(IOException.class).hasNoCause().hasMessageNotContaining("PRIVATE");
            assertThat(out.entries).isEmpty();
        }
    }

    @Test void cancellationDuringBlobPreflightProducesNoPartialArchive() throws Exception {
        try (var f = new Fixture()) {
            var retained = f.initialize("large input ".repeat(30000), "OVERLAY"); var out = new Contents();
            var sink = new ComponentSink() {
                int checks;
                @Override public void checkpoint() throws IOException { if (++checks > 15) throw new InterruptedIOException("cancelled"); }
                @Override public BackupEntry write(String path, InputStream input) throws IOException { return out.write(path, input); }
            };
            assertThatThrownBy(() -> f.contributor(Set.of(retained.id())).write(snapshot(BackupProfile.REPOSITORY_HISTORY, SCOPE), sink))
                    .isInstanceOf(InterruptedIOException.class);
            assertThat(out.entries).isEmpty();
        } finally { Thread.interrupted(); }
    }

    @ParameterizedTest @ValueSource(booleans = { false, true })
    void refusesAnIncompleteOrMismatchedSinkReceiptForAnOriginalFile(boolean consume) throws Exception {
        try (var f = new Fixture()) {
            var retained = f.initialize("ORIGINAL", "OVERLAY"); var out = new Contents();
            ComponentSink sink = (path, input) -> {
                if (!path.startsWith("files/")) return out.write(path, input);
                long length = consume ? input.readAllBytes().length : retained.workbook().length();
                return new BackupEntry(path, length, consume ? "0".repeat(64) : path.substring("files/catalogue/".length(), path.length()-4));
            };
            assertThatThrownBy(() -> f.contributor(Set.of(retained.id())).write(snapshot(BackupProfile.REPOSITORY_HISTORY, SCOPE), sink)).isInstanceOf(IOException.class);
        }
    }

    @Test void rechecksSourceBytesWhileStreamingAfterPreflight() throws Exception {
        try (var f = new Fixture()) {
            var retained = f.initialize("ORIGINAL", "OVERLAY"); var out = new Contents(); boolean[] changed = { false };
            ComponentSink sink = (path, input) -> {
                if (!changed[0]) { changed[0] = true; f.jdbc.update("update catalogue_source_blob set payload=?", "CHANGED".getBytes(StandardCharsets.UTF_8)); }
                return out.write(path, input);
            };
            assertThatThrownBy(() -> f.contributor(Set.of(retained.id())).write(snapshot(BackupProfile.REPOSITORY_HISTORY, SCOPE), sink)).isInstanceOf(IOException.class);
        }
    }

    enum MetadataDrift { WORKBOOK_REFERENCE, CREATED_AT, MISSING_REVISION, EXTRA_REVISION }
    @ParameterizedTest @EnumSource(MetadataDrift.class)
    void refusesMetadataDriftBetweenPreflightAndDatasetOutput(MetadataDrift drift) throws Exception {
        try (var f = new Fixture()) {
            var retained = f.initialize("ORIGINAL", "OVERLAY"); var out = new Contents(); boolean[] changed = { false };
            ComponentSink sink = (path, input) -> {
                if (!changed[0]) {
                    changed[0] = true;
                    switch (drift) {
                        case WORKBOOK_REFERENCE -> f.jdbc.update("update catalogue_source_revision set workbook_sha256=? where id=?", retained.overlay().sha256(), retained.id());
                        case CREATED_AT -> f.jdbc.update("update catalogue_source_revision set created_at='2020-01-01T00:00:00Z' where id=?", retained.id());
                        case MISSING_REVISION -> f.jdbc.update("delete from catalogue_source_revision where id=?", retained.id());
                        case EXTRA_REVISION -> f.jdbc.update("insert into catalogue_source_revision(id,created_at,workbook_sha256,workbook_use,overlay_sha256,overlay_use,relations_sha256,relations_use) "
                                + "select ?,created_at,workbook_sha256,workbook_use,overlay_sha256,overlay_use,relations_sha256,relations_use from catalogue_source_revision where id=?",
                                UUID.randomUUID().toString(), retained.id());
                    }
                }
                return out.write(path, input);
            };
            var profile = drift == MetadataDrift.EXTRA_REVISION ? BackupProfile.INSTALLATION_FULL : BackupProfile.REPOSITORY_HISTORY;
            assertThatThrownBy(() -> f.contributor(Set.of(retained.id())).write(snapshot(profile,
                    profile.isInstallation() ? new BackupScope.Installation() : SCOPE), sink)).isInstanceOf(IOException.class);
        }
    }

    @Test void refusesAChangedCurrentPointerBeforeStateDatasetOutput() throws Exception {
        try (var f = new Fixture()) {
            var old = f.initialize("OLD", "OLD-OVERLAY"); var retained = f.initialize("CURRENT", "CURRENT-OVERLAY");
            int[] stateQueries = { 0 }; boolean[] changed = { false };
            var database = new AbstractDataSource() {
                @Override public Connection getConnection() throws java.sql.SQLException {
                    var delegate = f.database.getConnection();
                    return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args[0].toString().startsWith("select id,current_revision from catalogue_source_state")
                                && ++stateQueries[0] == 2) {
                            changed[0] = true; f.jdbc.update("update catalogue_source_state set current_revision=?", old.id());
                        }
                        try { return method.invoke(delegate, args); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
                }
                @Override public Connection getConnection(String user, String password) throws java.sql.SQLException { return getConnection(); }
            };
            var contributor = new CatalogueSourceBackupContributor(database, context -> Set.of(retained.id()));
            var failure = catchThrowable(() -> contributor.write(snapshot(BackupProfile.REPOSITORY_HISTORY, SCOPE), new Contents()));
            assertThat(changed[0]).isTrue(); assertThat(failure).isInstanceOf(IOException.class);
        }
    }

    @Test void suppressesPrivateJdbcCleanupDetailsWhenBlobVerificationAlreadyFailed() throws Exception {
        try (var f = new Fixture()) {
            var retained = f.initialize("ORIGINAL", "OVERLAY");
            f.jdbc.update("update catalogue_source_blob set payload=?", "TAMPERED".getBytes(StandardCharsets.UTF_8));
            boolean[] cleanupFailed = { false };
            var database = new AbstractDataSource() {
                @Override public Connection getConnection() throws java.sql.SQLException {
                    var delegate = f.database.getConnection(); boolean[] blobQuery = { false };
                    return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args[0].toString().contains("select byte_length,payload")) blobQuery[0] = true;
                        try {
                            var value = method.invoke(delegate, args);
                            if (method.getName().equals("close") && blobQuery[0]) {
                                cleanupFailed[0] = true; throw new java.sql.SQLException("PRIVATE-JDBC-DIAGNOSTIC");
                            }
                            return value;
                        } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
                }
                @Override public Connection getConnection(String user, String password) throws java.sql.SQLException { return getConnection(); }
            };
            var out = new Contents(); var contributor = new CatalogueSourceBackupContributor(database, context -> Set.of(retained.id()));
            var failure = catchThrowable(() -> contributor.write(snapshot(BackupProfile.REPOSITORY_HISTORY, SCOPE), out));
            assertThat(failure).isInstanceOf(IOException.class); assertThat(cleanupFailed[0]).isTrue();
            var trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain("PRIVATE-JDBC-DIAGNOSTIC");
            assertThat(out.entries).isEmpty();
        }
    }

    private static void assertEmpty(Contents out) {
        assertThat(out.entries).containsOnlyKeys(STATE, REVISIONS, BLOBS);
        for (var content : out.entries.values()) assertThat(new String(content, StandardCharsets.UTF_8).lines().count()).isEqualTo(1);
    }
    static String path(InputReference input) { return "files/catalogue/" + input.sha256() + ".bin"; }
    static DataSource unreadableDatabase() { return new AbstractDataSource() {
        @Override public Connection getConnection() { throw new AssertionError("Unexpected global source read"); }
        @Override public Connection getConnection(String user, String password) { return getConnection(); }
    }; }
    static final class Fixture implements AutoCloseable {
        final JDBCDataSource database = new JDBCDataSource(); final org.hibernate.SessionFactory factory;
        final TransactionTemplate tx; final CatalogueSourceJournal journal; final JdbcTemplate jdbc;
        Fixture() {
            database.setUrl("jdbc:hsqldb:mem:catalogue-export-" + UUID.randomUUID()); database.setUser("sa");
            factory = new Configuration().setProperty("hibernate.connection.url", database.getUrl())
                    .setProperty("hibernate.connection.username", "sa").setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .addAnnotatedClass(CatalogueSourceBlob.class).addAnnotatedClass(CatalogueSourceRevision.class)
                    .addAnnotatedClass(CatalogueSourceState.class).buildSessionFactory();
            tx = new TransactionTemplate(new JpaTransactionManager(factory)); journal = new CatalogueSourceJournal(factory); jdbc = new JdbcTemplate(database);
        }
        Snapshot initialize(String workbook, String overlay) throws IOException {
            var w = CatalogueSourceBytes.read(new ByteArrayInputStream(workbook.getBytes(StandardCharsets.UTF_8)));
            var o = CatalogueSourceBytes.read(new ByteArrayInputStream(overlay.getBytes(StandardCharsets.UTF_8)));
            return tx.execute(s -> journal.initialize(w, SourceUse.applied(o), SourceUse.notUsed()));
        }
        CatalogueSourceBackupContributor contributor(Set<String> ids) { return new CatalogueSourceBackupContributor(database, context -> ids); }
        Contents export(BackupProfile profile, Set<String> ids) throws IOException {
            var out = new Contents(); contributor(ids).write(snapshot(profile, profile.isInstallation() ? new BackupScope.Installation() : SCOPE), out); return out;
        }
        @Override public void close() { factory.close(); }
    }
}
