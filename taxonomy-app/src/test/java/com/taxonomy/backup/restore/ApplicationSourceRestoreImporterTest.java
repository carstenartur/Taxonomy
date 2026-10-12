package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.runtime.*;
import com.taxonomy.backup.snapshot.*;
import com.taxonomy.provenance.backup.ApplicationBackupContributor;
import com.taxonomy.identity.StableIdentityHash;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Genuine source contributor -> writer -> verified archive -> isolated typed SQL/file import. */
class ApplicationSourceRestoreImporterTest {
    @TempDir Path root;
    private static final BackupComponentId APPLICATION = new BackupComponentId("application");
    @Test void remapsTechnicalIdsAndRestoresBinaryTextAndFragmentAncestryWithoutChangingSource() throws Exception {
        var source = sourceDatabase(); var staged = database();
        Path activeContent = Files.createDirectory(root.resolve("active-content"));
        byte[] binary = new byte[]{0, 1, 42, (byte)255}; Files.write(activeContent.resolve("source.bin"), binary);
        Files.writeString(activeContent.resolve("source.txt"), "Retained original text");
        seed(source, HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(binary)));
        Path targetContent = Files.createDirectory(root.resolve("target-content"));
        RestoreSchemaMigration.bootstrap(source, staged, () -> {});
        try (var factory = RestoreSchemaMigration.openValidatedFactory(source, staged, model(), () -> {});
             var archive = export(source, activeContent)) {
            var generation = RestoreTargetGeneration.verified(UUID.randomUUID(), source, staged, factory, Map.of(), () -> {});
            var importer = new ApplicationSourceRestoreImporter(activeContent, targetContent);
            var restored = importer.restore(archive, generation, () -> {});
            var artifact = new SourceRecordId("application.source-artifact", "100");
            var version = new SourceRecordId("application.source-version", "200");
            var parent = new SourceRecordId("application.source-fragment", "300");
            var child = new SourceRecordId("application.source-fragment", "301");
            assertThat(restored.artifactIds()).containsKey(artifact);
            assertThat(restored.artifactIds().get(artifact)).isNotEqualTo(100L);
            assertThat(restored.versionIds().get(version)).isNotEqualTo(200L);
            var rows = new JdbcTemplate(staged);
            assertThat(rows.queryForObject("select source_artifact_id from source_version", Long.class)).isEqualTo(restored.artifactIds().get(artifact));
            assertThat(rows.queryForObject("select parent_fragment_id from source_fragment where id=?", Long.class, restored.fragmentIds().get(child)))
                    .isEqualTo(restored.fragmentIds().get(parent));
            assertThat(rows.queryForObject("select fragment_text from source_fragment where id=?", String.class, restored.fragmentIds().get(child)))
                    .isEqualTo("Child evidence");
            String original = rows.queryForObject("select storage_location from source_version", String.class);
            String rawText = rows.queryForObject("select raw_text_location from source_version", String.class);
            assertThat(original).doesNotContain("source.bin").doesNotStartWith("/");
            assertThat(Files.readAllBytes(targetContent.resolve(original))).isEqualTo(binary);
            assertThat(Files.readString(targetContent.resolve(rawText))).isEqualTo("Retained original text");
            // A re-opened importer must resume the same committed owner phase without duplicate rows.
            var repeated = new ApplicationSourceRestoreImporter(activeContent, targetContent).restore(archive, generation, () -> {});
            assertThat(repeated).isEqualTo(restored);
            assertThat(rows.queryForObject("select count(*) from source_artifact", Long.class)).isEqualTo(1);
            assertThat(rows.queryForObject("select count(*) from source_fragment", Long.class)).isEqualTo(2);
            rows.update("update source_fragment set fragment_text='Unauthorized edit' where id=?", restored.fragmentIds().get(child));
            assertThatThrownBy(() -> importer.restore(archive, generation, () -> {})).isInstanceOf(IOException.class).hasMessageContaining("row proof");
            rows.update("update source_fragment set fragment_text='Child evidence' where id=?", restored.fragmentIds().get(child));
            Files.writeString(targetContent.resolve(original), "changed bytes");
            assertThatThrownBy(() -> importer.restore(archive, generation, () -> {})).isInstanceOf(IOException.class).hasMessageContaining("file proof");
            Files.delete(targetContent.resolve(original));
            Files.createSymbolicLink(targetContent.resolve(original), activeContent.resolve("source.bin"));
            assertThatThrownBy(() -> importer.restore(archive, generation, () -> {})).isInstanceOf(IOException.class);
            var writeConnections = new java.util.concurrent.atomic.AtomicInteger();
            var armed = new java.util.concurrent.atomic.AtomicBoolean();
            var rerouted = new org.springframework.jdbc.datasource.AbstractDataSource() {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                    return armed.get() && writeConnections.incrementAndGet() > 1 ? source.getConnection() : staged.getConnection();
                }
                @Override public java.sql.Connection getConnection(String user, String password) throws java.sql.SQLException { throw new java.sql.SQLException("Explicit credentials unsupported"); }
            };
            var reroutedGeneration = RestoreTargetGeneration.verified(UUID.randomUUID(), source, rerouted, factory, Map.of(), () -> {});
            armed.set(true);
            assertThatThrownBy(() -> importer.restore(archive, reroutedGeneration, () -> {}))
                    .isInstanceOf(IOException.class).hasMessageContaining("database binding");
            assertThat(writeConnections).hasValue(2);
        }
        var originalRows = new JdbcTemplate(source);
        assertThat(originalRows.queryForObject("select id from source_artifact", Long.class)).isEqualTo(100);
        assertThat(originalRows.queryForObject("select parent_fragment_id from source_fragment where id=301", Long.class)).isEqualTo(300);
        assertThat(Files.readAllBytes(activeContent.resolve("source.bin"))).isEqualTo(binary);
    }
    @Test void activeAliasOnActualStagedWriteBorrowFailsBeforeSourceRowsOrFiles()throws Exception {
        var source=sourceDatabase();var staged=database();
        Path activeContent=Files.createDirectory(root.resolve("active-content")),targetContent=Files.createDirectory(root.resolve("target-content"));
        Files.writeString(activeContent.resolve("source.bin"),"original");Files.writeString(activeContent.resolve("source.txt"),"text");
        seed(source,StableIdentityHash.sha256("original"));RestoreSchemaMigration.bootstrap(source,staged,()->{});
        var active=new org.springframework.jdbc.datasource.DelegatingDataSource(source);
        var armed=new java.util.concurrent.atomic.AtomicBoolean();var borrows=new java.util.concurrent.atomic.AtomicInteger();
        var guarded=new org.springframework.jdbc.datasource.AbstractDataSource(){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException {
                if(armed.get()&&borrows.incrementAndGet()==2)active.setTargetDataSource(staged);
                return staged.getConnection();
            }
            @Override public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{throw new java.sql.SQLException("Explicit credentials unsupported");}
        };
        try(var factory=RestoreSchemaMigration.openValidatedFactory(source,staged,model(),()->{});var archive=export(source,activeContent)) {
            var generation=RestoreTargetGeneration.verified(UUID.randomUUID(),active,guarded,factory,Map.of(),()->{});armed.set(true);
            assertThatThrownBy(()->new ApplicationSourceRestoreImporter(activeContent,targetContent).restore(archive,generation,()->{})).isInstanceOf(IOException.class).hasMessageContaining("binding");
            assertThat(borrows).hasValue(2);var rows=new JdbcTemplate(staged);
            assertThat(rows.queryForObject("select count(*) from source_artifact",Long.class)).isZero();
            assertThat(rows.queryForObject("select count(*) from restore_source_owner",Long.class)).isZero();
            try(var files=Files.list(targetContent)){assertThat(files.toList()).isEmpty();}
            assertThat(new JdbcTemplate(source).queryForObject("select id from source_artifact",Long.class)).isEqualTo(100);
        }
    }
    @Test void overlappingContentRootsAreRejectedBeforeAnyTargetRowsOrFiles() throws Exception {
        var source = sourceDatabase(); var staged = database();
        Path activeContent = Files.createDirectory(root.resolve("content"));
        Files.writeString(activeContent.resolve("source.bin"), "original"); Files.writeString(activeContent.resolve("source.txt"), "text");
        seed(source, StableIdentityHash.sha256("original"));
        Path nested = Files.createDirectory(activeContent.resolve("nested-stage"));
        RestoreSchemaMigration.bootstrap(source, staged, () -> {});
        try (var factory = RestoreSchemaMigration.openValidatedFactory(source, staged, model(), () -> {});
             var archive = export(source, activeContent)) {
            var generation = RestoreTargetGeneration.verified(UUID.randomUUID(), source, staged, factory, Map.of(), () -> {});
            assertThatThrownBy(() -> new ApplicationSourceRestoreImporter(activeContent, nested).restore(archive, generation, () -> {}))
                    .isInstanceOf(IOException.class).hasMessageContaining("isolated");
            assertThat(new JdbcTemplate(staged).queryForObject("select count(*) from source_artifact", Long.class)).isZero();
            try (var children = Files.list(nested)) { assertThat(children).isEmpty(); }
            Path separate = Files.createDirectory(root.resolve("external-stage"));
            var factoryChecks = new java.util.concurrent.atomic.AtomicInteger();
            BackupCheckpoint replaceDirectory = () -> {
                boolean factoryCheck = Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame -> frame.getClassName().equals(RestoreTargetGeneration.class.getName()) && frame.getMethodName().equals("requireFactory"));
                if (factoryCheck && factoryChecks.incrementAndGet() == 2) {
                    Files.move(separate, root.resolve("moved-stage"));
                    Files.createSymbolicLink(separate, activeContent);
                }
            };
            assertThatThrownBy(() -> new ApplicationSourceRestoreImporter(activeContent, separate).restore(archive, generation, replaceDirectory))
                    .isInstanceOf(IOException.class).hasMessageContaining("directory binding");
            assertThat(new JdbcTemplate(staged).queryForObject("select count(*) from source_artifact", Long.class)).isZero();
            try (var children = Files.list(activeContent)) { assertThat(children.map(path -> path.getFileName().toString())).noneMatch(name -> name.endsWith(".source")); }
        }
        assertThat(Files.readString(activeContent.resolve("source.bin"))).isEqualTo("original");
    }
    @Test void verifiedBytesWithDanglingFragmentParentAreRejectedBeforeSqlAndFileWrites() throws Exception {
        var source = sourceDatabase(); var staged = database();
        Path activeContent = Files.createDirectory(root.resolve("parent-source"));
        Files.writeString(activeContent.resolve("source.bin"), "original"); Files.writeString(activeContent.resolve("source.txt"), "text");
        seed(source, StableIdentityHash.sha256("original"));
        Path targetContent = Files.createDirectory(root.resolve("parent-stage"));
        RestoreSchemaMigration.bootstrap(source, staged, () -> {});
        try (var factory = RestoreSchemaMigration.openValidatedFactory(source, staged, model(), () -> {});
             var archive = export(source, activeContent, true)) {
            try (var content = archive.openEntry("data/application/source-fragment.ndjson")) {
                assertThat(new String(content.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("\"parentId\":{\"kind\":\"application.source-fragment\",\"value\":\"999\"}");
            }
            var generation = RestoreTargetGeneration.verified(UUID.randomUUID(), source, staged, factory, Map.of(), () -> {});
            assertThatThrownBy(() -> new ApplicationSourceRestoreImporter(activeContent, targetContent).restore(archive, generation, () -> {}))
                    .isInstanceOf(IOException.class).hasMessageContaining("ancestry");
            assertThat(new JdbcTemplate(staged).queryForObject("select count(*) from source_artifact", Long.class)).isZero();
            try (var children = Files.list(targetContent)) { assertThat(children).isEmpty(); }
        }
        assertThat(new JdbcTemplate(source).queryForObject("select parent_fragment_id from source_fragment where id=301", Long.class)).isEqualTo(300);
    }
    @Test void committedOwnerPhaseResumesAfterCancellationBeforeOuterJournalAndQuarantinesUnmappedLegacyData() throws Exception {
        var source = sourceDatabase(); var staged = database();
        Path active = Files.createDirectory(root.resolve("crash-source")), target = Files.createDirectory(root.resolve("crash-target"));
        Files.writeString(active.resolve("source.bin"), "original"); Files.writeString(active.resolve("source.txt"), "text");
        seed(source, StableIdentityHash.sha256("original"));
        new JdbcTemplate(source).update("insert into requirement_source_link(id,source_artifact_id,source_version_id,source_fragment_id,requirement_id,link_type,note) values(400,100,200,301,'legacy-private-key','REFERENCES','Private historic note')");
        RestoreSchemaMigration.bootstrap(source, staged, () -> {});
        try (var factory = RestoreSchemaMigration.openValidatedFactory(source, staged, model(), () -> {});
             var archive = export(source, active, false, true)) {
            var generation = RestoreTargetGeneration.verified(UUID.randomUUID(), source, staged, factory, Map.of(), () -> {});
            var rows = new JdbcTemplate(staged);
            var failRollback = new java.util.concurrent.atomic.AtomicBoolean();
            var guarded = new org.springframework.jdbc.datasource.AbstractDataSource() {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                    var delegate = staged.getConnection();
                    return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("rollback") && failRollback.get()) {
                            delegate.rollback(); throw new java.sql.SQLException("Injected cleanup failure");
                        }
                        try { return method.invoke(delegate, arguments); }
                        catch (java.lang.reflect.InvocationTargetException wrapped) { throw wrapped.getCause(); }
                    });
                }
                @Override public java.sql.Connection getConnection(String user, String password) throws java.sql.SQLException { throw new java.sql.SQLException("Explicit credentials unsupported"); }
            };
            var cleanupGeneration = RestoreTargetGeneration.verified(generation.generationId(), source, guarded, factory, Map.of(), () -> {});
            var importer = new ApplicationSourceRestoreImporter(active, target);
            BackupCheckpoint beforeCommit = () -> {
                try (var files = Files.list(target)) {
                    if (files.findAny().isPresent()) throw new InterruptedIOException("Stop during staged file write");
                }
            };
            failRollback.set(true);
            Throwable cancelled = catchThrowable(() -> importer.restore(archive, cleanupGeneration, beforeCommit));
            failRollback.set(false);
            assertThat(cancelled).isInstanceOf(InterruptedIOException.class);
            assertThat(cancelled.getSuppressed()).anyMatch(error -> error instanceof java.sql.SQLException && error.getMessage().equals("Injected cleanup failure"));
            assertThat(Thread.interrupted()).isTrue();
            assertThat(rows.queryForObject("select count(*) from source_artifact", Long.class)).isZero();
            assertThat(rows.queryForObject("select count(*) from restore_source_owner", Long.class)).isZero();
            try (var files = Files.list(target)) { assertThat(files).isEmpty(); }
            // This checkpoint can observe the owner only after its real SQL commit.
            BackupCheckpoint crash = () -> {
                if (rows.queryForObject("select count(*) from restore_source_owner", Long.class) == 1L)
                    throw new InterruptedIOException("Outer journal stopped after owner commit");
            };
            assertThatThrownBy(() -> importer.restore(archive, generation, crash)).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.interrupted()).isTrue();
            assertThat(rows.queryForObject("select count(*) from source_artifact", Long.class)).isEqualTo(1);
            var restored = new ApplicationSourceRestoreImporter(active, target).restore(archive, generation, () -> {});
            assertThat(restored.quarantinedLegacyLinks()).containsExactly(new SourceRecordId("application.legacy-source-link", "400"));
            assertThat(rows.queryForObject("select count(*) from requirement_source_link", Long.class)).isZero();
            assertThat(rows.queryForObject("select record_json from restore_source_quarantine", String.class)).contains("legacy-private-key", "Private historic note");
            assertThat(rows.queryForObject("select count(*) from source_fragment", Long.class)).isEqualTo(2);
            var another = RestoreTargetGeneration.verified(UUID.randomUUID(), source, staged, factory, Map.of(), () -> {});
            assertThatThrownBy(() -> importer.restore(archive, another, () -> {})).isInstanceOf(IOException.class).hasMessageContaining("another restore plan");
        }
        assertThat(new JdbcTemplate(source).queryForObject("select requirement_id from requirement_source_link", String.class)).isEqualTo("legacy-private-key");
    }
    private VerifiedBackup export(JDBCDataSource source, Path content) throws Exception { return export(source, content, false); }
    private VerifiedBackup export(JDBCDataSource source, Path content, boolean corruptParent) throws Exception {
        return export(source, content, corruptParent, false);
    }
    private VerifiedBackup export(JDBCDataSource source, Path content, boolean corruptParent, boolean installation) throws Exception {
        BackupMaintenanceLease.initialize(source);
        var contributor = new ApplicationBackupContributor(source, snapshot -> List.of(new BackupSourceReference(
                new SourceRecordId("portfolio.requirement-version", "evidence"), new SourceRecordId("application.source-artifact", "100"),
                new SourceRecordId("application.source-version", "200"), List.of(new SourceRecordId("application.source-fragment", "301")))), content);
        BackupContributor capturedContributor = new BackupContributor() {
            public BackupComponentId componentId() { return APPLICATION; }
            public int schemaVersion() { return 1; }
            public void write(SnapshotContext snapshot, ComponentSink target) throws IOException {
                contributor.write(snapshot, new ComponentSink() {
                    public BackupEntry write(String path, InputStream input) throws IOException {
                        if (!corruptParent || !path.equals("data/application/source-fragment.ndjson")) return target.write(path, input);
                        String data = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        String original = "\"parentId\":{\"kind\":\"application.source-fragment\",\"value\":\"300\"}";
                        assertThat(data).contains(original);
                        String altered = data.replace(original, original.replace("300", "999"));
                        return target.write(path, new ByteArrayInputStream(altered.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    }
                    public void checkpoint() throws IOException { target.checkpoint(); }
                    public BackupEntry writeGenerated(String path, EntryWriter producer) throws IOException {
                        return target.writeGenerated(path, producer);
                    }
                });
            }
        };
        var policy = new BackupAccessPolicy() {
            public boolean isEnabled(PrincipalId p) { return true; }
            public boolean hasCapability(PrincipalId p, BackupCapability c, BackupScope s) { return true; }
            public boolean canRead(PrincipalId p, BackupRepositoryKey k) { return true; }
            public boolean canReadVersion(PrincipalId p, BackupRepositoryKey k, String c) { return true; }
        };
        var auth = new BackupAuthorizationService(policy, Clock.systemUTC());
        var request = new BackupRequest(installation?BackupProfile.INSTALLATION_CURRENT:BackupProfile.CURRENT_STATE,
                installation?new BackupScope.Installation():new BackupScope.Workspace("repo","workspace"),new BackupTime.Current(),GitRepresentation.NONE,SecretsSelection.EXCLUDE);
        var state = new SnapshotContext.RepositoryState(Map.of(),null,Map.of(),Set.of());
        var plan = new BackupSnapshotCoordinator.CapturePlan("1.5.1-SNAPSHOT","typed-restore-fixture","source-installation",
                List.of(new BackupManifest.Repository(new BackupRepositoryKey("repo","workspace"),"opaque",GitRepresentation.NONE,state,null,null,null)),
                Map.of(APPLICATION,new BackupSnapshotCoordinator.CaptureComponent(1,BackupCompleteness.COMPLETE,Set.of())),List.of(),List.of(),
                new SourceDatabaseMetadata(1,SourceDatabaseFamily.HSQLDB));
        var prerequisites = BackupFeaturePrerequisites.discover();
        var coordinator = new BackupSnapshotCoordinator(new BackupMaintenanceLease(source,Duration.ofSeconds(30)),auth,ignored -> plan,
                List.of(capturedContributor),root.resolve("spool"),BackupSnapshotCoordinator.CaptureLimits.defaults(),Clock.systemUTC(),prerequisites);
        Path archive = root.resolve(UUID.randomUUID()+".taxbackup");
        try (var captured = coordinator.capture(auth.authorize(PrincipalId.create(),request))) {
            new BackupArchiveWriter(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),prerequisites).write(captured,archive);
        }
        return new BackupArchiveReader(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),"1.5.1-SNAPSHOT",
                Map.of(APPLICATION,1,new BackupComponentId("capture-proof"),1),prerequisites).verify(archive);
    }
    private static void seed(JDBCDataSource source,String hash) {
        var rows = new JdbcTemplate(source);
        rows.update("insert into source_artifact(id,source_type,title,created_at) values(100,'REGULATION','Retained source',timestamp '2026-10-11 00:00:00')");
        rows.update("insert into source_version(id,source_artifact_id,retrieved_at,content_hash,storage_location,raw_text_location) values(200,100,timestamp '2026-10-11 00:00:00',?,'source.bin','source.txt')",hash);
        rows.update("insert into source_fragment(id,source_version_id,fragment_text) values(300,200,'Parent evidence')");
        rows.update("insert into source_fragment(id,source_version_id,fragment_text,parent_fragment_id) values(301,200,'Child evidence',300)");
    }
    private static JDBCDataSource sourceDatabase() throws Exception {
        var data = database(); var rows = new JdbcTemplate(data);
        // Genuine baseline DDL, no Hibernate schema creation in the application restore path.
        try (var stream = ApplicationSourceRestoreImporterTest.class.getResourceAsStream("/db/restore/hsqldb/V1__Fresh_business_schema.sql")) {
            if (stream == null) throw new IllegalStateException("Missing versioned restore schema");
            for (String sql : new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
                if (sql.matches("create table (source_artifact|source_version|source_fragment|requirement_source_link) .*")) rows.execute(sql);
            }
        }
        return data;
    }
    private static List<Class<?>> model() throws Exception {
        try (var stream = ApplicationSourceRestoreImporterTest.class.getResourceAsStream("/db/restore/hsqldb/approved-business-model.txt")) {
            var types = new ArrayList<Class<?>>();
            for (String name : new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).lines().toList())
                if (!name.isBlank() && !name.startsWith("#")) types.add(Class.forName(name));
            return types;
        }
    }
    private static JDBCDataSource database() {
        var data = new JDBCDataSource(); data.setURL("jdbc:hsqldb:mem:typed-source-restore-"+UUID.randomUUID()+";hsqldb.tx=mvcc"); data.setUser("sa");return data;
    }
}
