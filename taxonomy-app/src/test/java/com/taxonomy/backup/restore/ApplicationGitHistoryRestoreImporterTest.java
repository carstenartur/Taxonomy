package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.backup.runtime.*;
import com.taxonomy.workspace.backup.*;
import com.taxonomy.workspace.storage.StagedJgitSchemaMigration;
import io.github.carstenartur.jgit.storage.hibernate.*;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class ApplicationGitHistoryRestoreImporterTest {
    static final BackupCheckpoint CHECK=()->{};
    static final PrincipalId ACTOR=new PrincipalId(UUID.fromString("00000000-0000-0000-0000-000000000123"));
    static final RestoreRepositoryAllocation ALLOCATION=new RestoreRepositoryAllocation(new BackupRepositoryKey("explicit-target",null),"private-original-target");
    static final Map<BackupRepositoryKey,RestoreRepositoryAllocation> ALLOCATIONS=Map.of(GitHistoryArchiveTest.SOURCE,ALLOCATION);
    @TempDir Path temporary;

    @Test void realNativeArchiveImportsOriginalHistoryAndRetryReadsCompletedProofWithoutNewAudit()throws Exception {
        try(var fixture=GitHistoryArchiveTest.fixture(temporary,GitRepresentation.BUNDLE,entries->{});var stage=new Stage()) {
            var importer=importer();var result=importer.importGit(fixture.archive(),ALLOCATIONS,stage.generation,CHECK);
            var proof=result.repositories().get(GitHistoryArchiveTest.SOURCE);assertThat(proof).isNotNull();
            assertThat(proof.targetKey()).isEqualTo(ALLOCATION.targetKey());assertThat(proof.physicalStorageName()).isEqualTo(ALLOCATION.physicalStorageName());
            assertThat(proof.commitMappings()).containsEntry(fixture.first(),fixture.first()).containsEntry(fixture.head(),fixture.head());
            assertThat(proof.restoredState().workingStates()).isEmpty();
            assertThat(stage.sql.queryForObject("select owner_state from restore_git_repository",String.class)).isEqualTo("COMPLETE");
            assertThat(stage.sql.queryForObject("select actor_id from restore_git_owner",String.class)).isEqualTo(ACTOR.value().toString());
            var original=new GitReflogProvenanceStore(stage.target).read(ALLOCATION.targetKey(),ALLOCATION.physicalStorageName(),10_000,CHECK);
            assertThat(original).isNotEmpty();assertThat(original).allSatisfy(event->assertThat(event.origin().archiveSha256()).isEqualTo(fixture.archive().archiveSha256()));
            long before=stage.sql.queryForObject("select count(*) from git_reflog",Long.class);
            try(var storage=stage.repositories.open(new RepositoryName(ALLOCATION.physicalStorageName()))) {
                assertThat(storage.repository().exactRef("HEAD").getTarget().getName()).isEqualTo("refs/heads/alias");
                assertThat(storage.repository().exactRef("refs/heads/main").getObjectId().name()).isEqualTo(fixture.head());
                assertThat(storage.repository().getReflogReader("refs/heads/main").getLastEntry().getWho().getName()).contains(ACTOR.value().toString());
            }
            assertThat(importer().importGit(fixture.archive(),ALLOCATIONS,stage.generation,CHECK)).isEqualTo(result);
            assertThat(stage.sql.queryForObject("select count(*) from git_reflog",Long.class)).isEqualTo(before);
            stage.sql.execute("delete from workspace_git_reflog_provenance");
            assertThatThrownBy(()->importer.importGit(fixture.archive(),ALLOCATIONS,stage.generation,CHECK)).isInstanceOf(IOException.class);
            assertThat(stage.sql.queryForObject("select count(*) from workspace_git_reflog_provenance",Long.class)).isZero();
            assertThat(stage.sql.queryForObject("select count(*) from git_reflog",Long.class)).isEqualTo(before);
        }
    }

    @Test void lostCompletionCommitAcknowledgementResumesOnlyAfterActualTargetReadback()throws Exception {
        try(var fixture=GitHistoryArchiveTest.fixture(temporary,GitRepresentation.BARE,entries->{});var stage=new Stage()) {
            stage.target.loseCompletionAck.set(true);
            assertThatThrownBy(()->importer().importGit(fixture.archive(),ALLOCATIONS,stage.generation,CHECK)).isInstanceOf(IOException.class);
            assertThat(stage.target.completionAckLost).isTrue();
            assertThat(stage.sql.queryForObject("select owner_state from restore_git_repository",String.class)).isEqualTo("COMPLETE");
            long before=stage.sql.queryForObject("select count(*) from git_reflog",Long.class);
            assertThat(importer().importGit(fixture.archive(),ALLOCATIONS,stage.generation,CHECK).repositories()).containsKey(GitHistoryArchiveTest.SOURCE);
            assertThat(stage.sql.queryForObject("select count(*) from git_reflog",Long.class)).isEqualTo(before);
        }
    }

    @Test void cancellationAfterRealObjectPublicationLeavesPreparedAllocationForOriginalIdResume()throws Exception {
        try(var fixture=GitHistoryArchiveTest.fixture(temporary,GitRepresentation.WORKTREE,entries->{});var stage=new Stage()) {
            try {
                assertThatThrownBy(()->importer().importGit(fixture.archive(),ALLOCATIONS,stage.generation,()->{
                    if(stage.target.packCommitted.get())throw new InterruptedIOException("fixture cancelled after actual object commit");
                })).isInstanceOf(InterruptedIOException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally{Thread.interrupted();}
            assertThat(stage.target.packCommitted).isTrue();
            assertThat(stage.sql.queryForObject("select owner_state from restore_git_repository",String.class)).isEqualTo("PREPARED");
            assertThat(stage.sql.queryForObject("select count(*) from workspace_git_reflog_provenance",Long.class)).isZero();
            String operation=stage.sql.queryForObject("select operation_id from restore_git_repository",String.class);
            var result=importer().importGit(fixture.archive(),ALLOCATIONS,stage.generation,CHECK);
            assertThat(result.repositories().get(GitHistoryArchiveTest.SOURCE).commitMappings()).containsEntry(fixture.head(),fixture.head());
            assertThat(stage.sql.queryForObject("select operation_id from restore_git_repository",String.class)).isEqualTo(operation);
        }
    }

    @Test void preexistingStorageWithoutPreparedAllocationIsRejectedWithoutChangingItsContents()throws Exception {
        try(var fixture=GitHistoryArchiveTest.fixture(temporary,GitRepresentation.BUNDLE,entries->{});var stage=new Stage();
            var foreign=new com.taxonomy.workspace.storage.DslGitRepository(stage.repositories,ALLOCATION.physicalStorageName())) {
            String existing=foreign.commitDsl("foreign","KEEP FOREIGN","Existing target","existing data");
            assertThatThrownBy(()->importer().importGit(fixture.archive(),ALLOCATIONS,stage.generation,CHECK)).isInstanceOf(IOException.class);
            assertThat(stage.sql.queryForObject("select count(*) from restore_git_owner",Long.class)).isZero();
            assertThat(foreign.getGitRepository().exactRef("refs/heads/foreign").getObjectId().name()).isEqualTo(existing);
            assertThat(foreign.getGitRepository().newObjectReader().has(org.eclipse.jgit.lib.ObjectId.fromString(fixture.head()))).isFalse();
        }
    }

    @Test void activeDatabaseReroutingAfterStatementPreparationIsRejectedBeforeOwnerInsertOrGitAllocation()throws Exception {
        try(var fixture=GitHistoryArchiveTest.fixture(temporary,GitRepresentation.BUNDLE,entries->{});var stage=new Stage()) {
            stage.target.beforeOwnerInsert=()->stage.active.route.set(stage.rawTarget);
            try {
                assertThatThrownBy(()->importer().importGit(fixture.archive(),ALLOCATIONS,stage.generation,CHECK)).isInstanceOf(IOException.class);
            } finally{stage.active.route.set(stage.rawActive);}
            assertThat(stage.sql.queryForObject("select count(*) from restore_git_owner",Long.class)).isZero();
            assertThat(stage.sql.queryForObject("select count(*) from git_packs",Long.class)).isZero();
            assertThat(new JdbcTemplate(stage.rawActive).queryForObject("select id from active_marker",Integer.class)).isEqualTo(42);
        }
    }

    @Test void actualResultSetCannotExposeAnUnfencedStatementOrConnection()throws Exception {
        try(var stage=new Stage()) {
            stage.sql.execute("create table isolation_probe(id integer)");
            var fenced=new ApplicationGitHistoryRestoreImporter.FencedDataSource(stage.generation,CHECK);
            try(var connection=fenced.getConnection();var statement=connection.createStatement();
                var rows=statement.executeQuery("values(1)")) {
                assertThat(rows.next()).isTrue();
                Connection recovered=rows.unwrap(ResultSet.class).getStatement().getConnection();
                stage.active.route.set(stage.rawTarget);
                try {
                    assertThatThrownBy(()->{
                        try(var mutation=recovered.createStatement()){mutation.executeUpdate("insert into isolation_probe values(1)");}
                    }).isInstanceOf(SQLException.class);
                } finally{stage.active.route.set(stage.rawActive);}
            }
            assertThat(stage.sql.queryForObject("select count(*) from isolation_probe",Long.class)).isZero();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void currentAuthorityAndActorAreRequiredAfterActualPreparedCommit(boolean changeActor)throws Exception {
        try(var fixture=GitHistoryArchiveTest.fixture(temporary,GitRepresentation.BUNDLE,entries->{});var stage=new Stage()) {
            var current=new AtomicReference<>(ACTOR);var allowed=new AtomicBoolean(true);
            var importer=importer(current::get,allowed);
            assertThatThrownBy(()->importer.importGit(fixture.archive(),ALLOCATIONS,stage.generation,()->{
                if(stage.target.ownerPrepared.get()) {
                    if(changeActor)current.set(new PrincipalId(UUID.fromString("00000000-0000-0000-0000-000000000124")));
                    else allowed.set(false);
                }
            })).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(stage.target.ownerPrepared).isTrue();
            assertThat(stage.sql.queryForObject("select owner_state from restore_git_repository",String.class)).isEqualTo("PREPARED");
            assertThat(stage.sql.queryForObject("select count(*) from git_packs",Long.class)).isZero();
            assertThat(stage.sql.queryForObject("select count(*) from workspace_git_reflog_provenance",Long.class)).isZero();
            current.set(ACTOR);allowed.set(true);
            assertThat(importer.importGit(fixture.archive(),ALLOCATIONS,stage.generation,CHECK).repositories()).containsKey(GitHistoryArchiveTest.SOURCE);
        }
    }

    private ApplicationGitHistoryRestoreImporter importer()throws IOException {
        return importer(()->ACTOR,new AtomicBoolean(true));
    }
    private ApplicationGitHistoryRestoreImporter importer(java.util.function.Supplier<PrincipalId> current,AtomicBoolean allowed)throws IOException {
        Path spool=temporary.resolve("private");Files.createDirectories(spool);
        var policy=new BackupAccessPolicy() {
            public boolean isEnabled(PrincipalId principal){return allowed.get()&&principal.equals(ACTOR);}
            public boolean hasCapability(PrincipalId principal,BackupCapability capability,BackupScope scope){return principal.equals(ACTOR)&&capability==BackupCapability.RESTORE_INSTALLATION&&scope instanceof BackupScope.Installation;}
            public boolean canRead(PrincipalId principal,BackupRepositoryKey repository){return false;}
            public boolean canReadVersion(PrincipalId principal,BackupRepositoryKey repository,String commit){return false;}
        };
        return new ApplicationGitHistoryRestoreImporter(current,new BackupAuthorizationService(policy,Clock.systemUTC()),spool,GitHistoryCapture.Limits.defaults());
    }
    static final class Stage implements AutoCloseable {
        final JDBCDataSource rawActive=database(),rawTarget=database();
        final TrackingDataSource active=new TrackingDataSource(rawActive),target=new TrackingDataSource(rawTarget);
        final JdbcTemplate sql=new JdbcTemplate(rawTarget);
        final org.hibernate.SessionFactory factory;
        final DefaultHibernateRepositoryFactory repositories;
        final RestoreTargetGeneration generation;
        Stage()throws Exception {
            new JdbcTemplate(rawActive).execute("create table active_marker(id integer)");new JdbcTemplate(rawActive).update("insert into active_marker values(42)");
            RestoreSchemaMigration.bootstrap(active,target,CHECK);
            var config=new org.hibernate.cfg.Configuration();config.getProperties().put("hibernate.connection.datasource",target);
            config.setProperty("hibernate.hbm2ddl.auto","validate");config.setProperty("hibernate.search.enabled","false");
            StagedJgitSchemaMigration.configureModel(config);factory=config.buildSessionFactory();
            repositories=new DefaultHibernateRepositoryFactory(factory);
            generation=RestoreTargetGeneration.verified(UUID.randomUUID(),active,target,factory,ALLOCATIONS,CHECK);
        }
        public void close(){factory.close();}
    }
    static final class TrackingDataSource extends AbstractDataSource {
        final AtomicReference<DataSource> route;
        final AtomicBoolean loseCompletionAck=new AtomicBoolean(),completionAckLost=new AtomicBoolean(),packCommitted=new AtomicBoolean(),ownerPrepared=new AtomicBoolean();
        volatile Runnable beforeOwnerInsert;
        TrackingDataSource(DataSource actual){route=new AtomicReference<>(actual);}
        public Connection getConnection()throws SQLException {
            var actual=route.get().getConnection();var completed=new AtomicBoolean();var pack=new AtomicBoolean();var prepared=new AtomicBoolean();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql) {
                    String lower=sql.toLowerCase(Locale.ROOT);
                    if(lower.startsWith("update restore_git_repository"))completed.set(true);
                    if(lower.startsWith("insert into git_packs"))pack.set(true);
                    if(lower.startsWith("insert into restore_git_owner"))prepared.set(true);
                    if(lower.startsWith("insert into restore_git_owner")&&beforeOwnerInsert!=null)beforeOwnerInsert.run();
                }
                Object result=invoke(actual,method,args);
                if(method.getName().equals("commit")) {
                    if(pack.get())packCommitted.set(true);
                    if(prepared.get())ownerPrepared.set(true);
                    if(completed.get()&&loseCompletionAck.compareAndSet(true,false)){completionAckLost.set(true);throw new SQLException("fixture lost actual completed commit acknowledgement");}
                }
                return result;
            });
        }
        public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("Fixed fixture credentials");}
    }
    private static Object invoke(Object target,Method method,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException wrapped){throw wrapped.getCause();}
    }
    private static JDBCDataSource database(){var source=new JDBCDataSource();source.setURL("jdbc:hsqldb:mem:git-host-"+UUID.randomUUID());source.setUser("sa");return source;}
}
