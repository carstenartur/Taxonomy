package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.runtime.*;
import com.taxonomy.backup.snapshot.*;
import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureElement;
import com.taxonomy.editor.*;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.security.backup.IdentityBackupContributor;
import com.taxonomy.workspace.backup.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.storage.*;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real owner journal, history exporter, native ZIP, staged Hibernate Git host and editor application boundary. */
class WorkspaceEditorRestoreRuntimeTest {
    private static final String VERSION="1.5.1-SNAPSHOT",SOURCE_SCOPE="source-owner",TARGET_SCOPE="target-owner";
    private static final String EDIT="00000000-0000-0000-0000-000000000211",SAVE="00000000-0000-0000-0000-000000000212";
    private static final BackupCheckpoint CHECK=()->{};
    private static final BackupRepositoryKey CENTRAL=new BackupRepositoryKey("editor-source-repository",null),WORKSPACE=new BackupRepositoryKey("editor-source-repository","editor-source-workspace");
    private static final BackupComponentId APPLICATION=new BackupComponentId("application"),WORKSPACE_OWNER=new BackupComponentId("workspace");
    @TempDir Path temporary;

    @Test void explicitActorRebindingPreservesActualEditorHistoryUndoRedoAndInterruptedCheckpointEvidence()throws Exception {
        try(var fixture=fixture(SOURCE_SCOPE)) {
            fixture.restore();
            var target=RepositoryContext.workspace("editor-target-repository","editor-target-workspace","main",TARGET_SCOPE);
            var journal=new EditorJournal(fixture.targetFactory,new JpaTransactionManager(fixture.targetFactory));
            try(var repositories=new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(fixture.targetFactory))) {
                var editor=new ArchitectureEditorService(repositories,journal,new ArchitectureCheckpointWriter());
                var before=editor.read(target,null);
                assertThat(before.dsl()).contains("Restored editor draft");
                assertThat(before.context().revision()).isEqualTo(1);
                assertThat(before.history()).extracting(ArchitectureEditorService.HistoryEntry::commandId).containsExactly(EDIT);
                assertThat(before.history().getFirst().actor()).isEqualTo(TARGET_SCOPE);
                assertThat(before.pendingCheckpointId()).isNull();
                assertThat(journal.checkpoints(target)).singleElement().satisfies(checkpoint->{
                    assertThat(checkpoint.commandId()).isEqualTo(SAVE);assertThat(checkpoint.failureCode()).isEqualTo("RESTORED_INTERRUPTED");
                    assertThat(checkpoint.actor()).isEqualTo(TARGET_SCOPE);assertThat(checkpoint.completed()).isTrue();
                });
                var undo=editor.execute(target,new Command(before.context(),metadata(UUID.randomUUID().toString()),new UndoArchitectureCommand(UUID.fromString(EDIT))));
                assertThat(editor.read(target,null).dsl()).isEmpty();
                var redo=editor.execute(target,new Command(editor.read(target,null).context(),metadata(UUID.randomUUID().toString()),new RedoArchitectureCommand(UUID.fromString(undo.commandId()))));
                assertThat(redo.context().revision()).isEqualTo(3);
                assertThat(editor.read(target,null).dsl()).isEqualTo(before.dsl());
                assertThat(new JdbcTemplate(fixture.target).queryForObject("select enabled from app_principal",Integer.class)).isZero();
                assertThat(new JdbcTemplate(fixture.target).queryForObject("select record_json from restore_workspace_evidence where record_kind='operations'",String.class)).contains("\"actor\":\""+SOURCE_SCOPE+"\"");
                assertThat(new JdbcTemplate(fixture.source).queryForObject("select actor from editor_operation",String.class)).isEqualTo(SOURCE_SCOPE);
                assertThat(fixture.sourceJournal.read(fixture.sourceContext).state().pendingCheckpoint()).isEqualTo(SAVE);
            }
        }
    }

    @Test void unknownHistoricalActorMatchingTargetScopeQuarantinesEntireEditorInsteadOfGrantingPersonalUndo()throws Exception {
        try(var fixture=fixture(TARGET_SCOPE)) {
            fixture.restore();var rows=new JdbcTemplate(fixture.target);
            for(String table:List.of("editor_workspace","editor_operation","editor_checkpoint"))assertThat(rows.queryForObject("select count(*) from "+table,Long.class)).as(table).isZero();
            assertThat(rows.queryForList("select classification from restore_workspace_evidence where record_kind in ('working-states','operations','checkpoints')",String.class)).containsOnly("PRIVATE_QUARANTINE");
            assertThat(rows.queryForObject("select record_json from restore_workspace_evidence where record_kind='operations'",String.class)).contains("\"actor\":\""+TARGET_SCOPE+"\"");
            assertThat(new JdbcTemplate(fixture.source).queryForObject("select count(*) from editor_operation",Long.class)).isEqualTo(1);
        }
    }

    private Fixture fixture(String actor)throws Exception {
        var source=database();var target=database();RestoreSchemaMigration.bootstrap(database(),source,CHECK);
        var sourceFactory=RestoreSchemaMigration.openValidatedFactory(database(),source,model(),CHECK);
        var sourceRepositories=new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(sourceFactory));
        var sourceContext=RepositoryContext.workspace(CENTRAL.repositoryId(),WORKSPACE.workspaceId(),"main",actor);
        var principal=PrincipalId.create();var rows=new JdbcTemplate(source);var created=java.sql.Timestamp.from(Instant.now());
        rows.update("insert into app_principal(principal_id,scope_key,enabled) values(?,?,1)",principal.value().toString(),SOURCE_SCOPE);
        rows.update("insert into system_repository(repository_id,storage_repository_name,slug,display_name,visibility,lifecycle_state,owner_type,owner_id,topology_mode,default_branch,primary_repo,created_at,version) values(?,?,'editor-source','Editor source','PRIVATE','ACTIVE','USER',?,'INTERNAL_SHARED','main',false,?,0)",CENTRAL.repositoryId(),"editor-source-central-storage",SOURCE_SCOPE,created);
        sourceRepositories.createCentralRepository(CENTRAL.repositoryId(),"editor-source-central-storage").commitDsl("main","",SOURCE_SCOPE,"Original central baseline");
        String baseline=sourceRepositories.resolveRepository(sourceContext).commitDsl("main","",SOURCE_SCOPE,"Original workspace baseline");
        rows.update("insert into user_workspace(workspace_id,username,display_name,current_branch,base_branch,shared,created_at,provisioning_status,topology_mode,source_repository_id,source_branch,relationship_type,base_commit,current_commit,archived,is_default) values(?,?,'Editor draft','main','main',false,?,'READY','INTERNAL_SHARED',?,'main','WORKING_COPY',?,?,false,false)",WORKSPACE.workspaceId(),SOURCE_SCOPE,created,CENTRAL.repositoryId(),baseline,baseline);
        var sourceJournal=new EditorJournal(sourceFactory,new JpaTransactionManager(sourceFactory));
        var service=new ArchitectureEditorService(sourceRepositories,sourceJournal,new ArchitectureCheckpointWriter());
        var accepted=service.execute(sourceContext,new Command(Context.of(sourceContext,baseline,0),metadata(EDIT),new SemanticCommand(new CreateArchitectureElement("restored-editor-element","System",Map.of("title","Restored editor draft")))));
        sourceJournal.locked(sourceContext,sourceJournal.read(sourceContext).state(),session->session.prepare(metadata(SAVE),actor,"a".repeat(64)));
        assertThat(accepted.context().revision()).isEqualTo(1);
        assertThat(sourceJournal.read(sourceContext).state().pendingCheckpoint()).isEqualTo(SAVE);
        var archive=export(source,sourceFactory,principal);
        RestoreSchemaMigration.bootstrap(source,target,CHECK);
        var targetFactory=RestoreSchemaMigration.openValidatedFactory(source,target,model(),CHECK);
        var allocations=Map.of(CENTRAL,new RestoreRepositoryAllocation(new BackupRepositoryKey("editor-target-repository",null),"editor-target-central-storage"),
                WORKSPACE,new RestoreRepositoryAllocation(new BackupRepositoryKey("editor-target-repository","editor-target-workspace"),DslGitRepositoryFactory.workspaceRepositoryName("editor-target-workspace")));
        var generation=RestoreTargetGeneration.verified(UUID.randomUUID(),source,target,targetFactory,allocations,CHECK);
        var principals=Map.of(principal,new RestorePrincipalAllocation(PrincipalId.create(),TARGET_SCOPE,RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET));
        return new Fixture(source,target,sourceFactory,targetFactory,sourceRepositories,sourceJournal,sourceContext,archive,generation,principals,authority(principal));
    }

    private VerifiedBackup export(JDBCDataSource source,org.hibernate.SessionFactory sourceFactory,PrincipalId principal)throws Exception {
        BackupMaintenanceLease.initialize(source);var prerequisites=BackupFeaturePrerequisites.discover();
        var existing=new ExistingGitBackupRepositories(source,sourceFactory);
        var history=new GitHistoryBackupContributor(existing,new GitStandBackupSource(source,existing,document->document),new WorkspaceGitHistoryReferences(source,existing),GitHistoryCapture.Limits.defaults(),
                new GitStandBackupSource.Limits(new GitTreeCapture.Limits(100,1_000_000,2_000_000),100_000));
        var authorization=authority(principal);var request=new BackupRequest(BackupProfile.INSTALLATION_FULL,new BackupScope.Installation(),new BackupTime.History(),GitRepresentation.BUNDLE,SecretsSelection.EXCLUDE);
        var components=Map.of(APPLICATION,new BackupSnapshotCoordinator.CaptureComponent(1,BackupCompleteness.COMPLETE,Set.of()),WORKSPACE_OWNER,new BackupSnapshotCoordinator.CaptureComponent(1,BackupCompleteness.COMPLETE,Set.of(APPLICATION)));
        // Coordinator accepts one contributor per component. This fixture tests real owner adapters,
        // not production inventory/readiness registration for the installation as a whole.
        var data=new WorkspaceBackupContributor(source,document->document);
        var workspace=new BackupContributor(){
            public BackupComponentId componentId(){return WORKSPACE_OWNER;}
            public int schemaVersion(){return 1;}
            public void write(SnapshotContext snapshot,ComponentSink sink)throws IOException{data.write(snapshot,sink);history.write(snapshot,sink);}
        };
        var coordinator=new BackupSnapshotCoordinator(new BackupMaintenanceLease(source,Duration.ofSeconds(30)),authorization,
                auth->new BackupSnapshotCoordinator.CapturePlan(VERSION,"editor-owner-runtime-fixture",new JdbcTemplate(source).queryForObject("select installation_id from principal_installation",String.class),history.inspect(auth,Set.of(CENTRAL,WORKSPACE),CHECK),components,List.of(),List.of(),new SourceDatabaseMetadata(1,SourceDatabaseFamily.HSQLDB)),
                List.of(new IdentityBackupContributor(source,(snapshot,checkpoint)->Set.of(principal)),workspace),temporary.resolve(UUID.randomUUID()+"-capture"),BackupSnapshotCoordinator.CaptureLimits.defaults(),Clock.systemUTC(),prerequisites);
        Path output=temporary.resolve(UUID.randomUUID()+".taxbackup");try(var captured=coordinator.capture(authorization.authorize(principal,request))){new BackupArchiveWriter(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),prerequisites).write(captured,output);}
        return new BackupArchiveReader(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),VERSION,Map.of(APPLICATION,1,WORKSPACE_OWNER,1,new BackupComponentId("capture-proof"),1),prerequisites).verify(output);
    }

    private static BackupAuthorizationService authority(PrincipalId principal) {
        return new BackupAuthorizationService(new BackupAccessPolicy(){
            public boolean isEnabled(PrincipalId id){return principal.equals(id);}
            public boolean hasCapability(PrincipalId id,BackupCapability capability,BackupScope scope){return principal.equals(id);}
            public boolean canRead(PrincipalId id,BackupRepositoryKey repository){return principal.equals(id);}
            public boolean canReadVersion(PrincipalId id,BackupRepositoryKey repository,String commit){return principal.equals(id);}
        },Clock.systemUTC());
    }
    private static Metadata metadata(String command){return new Metadata(command,command,command,"Editor restore acceptance");}
    private static JDBCDataSource database(){var database=new JDBCDataSource();database.setUrl("jdbc:hsqldb:mem:editor-restore-"+UUID.randomUUID()+";hsqldb.tx=mvcc");database.setUser("sa");return database;}
    private static List<Class<?>> model()throws Exception {try(var input=WorkspaceEditorRestoreRuntimeTest.class.getResourceAsStream("/db/restore/hsqldb/approved-business-model.txt")){var model=new ArrayList<Class<?>>();for(String name:new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).lines().toList())if(!name.isBlank()&&!name.startsWith("#"))model.add(Class.forName(name));return model;}}
    private final class Fixture implements AutoCloseable {
        final JDBCDataSource source,target;final org.hibernate.SessionFactory sourceFactory,targetFactory;final DslGitRepositoryFactory sourceRepositories;
        final EditorJournal sourceJournal;final RepositoryContext sourceContext;final VerifiedBackup archive;final RestoreTargetGeneration generation;
        final Map<PrincipalId,RestorePrincipalAllocation> principals;final BackupAuthorizationService authority;
        Fixture(JDBCDataSource source,JDBCDataSource target,org.hibernate.SessionFactory sourceFactory,org.hibernate.SessionFactory targetFactory,DslGitRepositoryFactory sourceRepositories,EditorJournal sourceJournal,RepositoryContext sourceContext,VerifiedBackup archive,RestoreTargetGeneration generation,Map<PrincipalId,RestorePrincipalAllocation> principals,BackupAuthorizationService authority){
            this.source=source;this.target=target;this.sourceFactory=sourceFactory;this.targetFactory=targetFactory;this.sourceRepositories=sourceRepositories;this.sourceJournal=sourceJournal;this.sourceContext=sourceContext;this.archive=archive;this.generation=generation;this.principals=principals;this.authority=authority;
        }
        void restore()throws IOException {
            var git=new ApplicationGitHistoryRestoreImporter(()->principals.keySet().iterator().next(),authority,temporary.resolve("restore-private"),GitHistoryCapture.Limits.defaults());
            Files.createDirectories(temporary.resolve("restore-private"));
            var proof=git.importGit(archive,generation.allocations(),generation,CHECK);
            new RestoreIdentityImporter().restore(archive,generation,principals,CHECK);
            new WorkspaceRestoreImporter().restore(archive,generation,principals,proof,CHECK);
        }
        public void close()throws IOException{archive.close();sourceRepositories.close();sourceFactory.close();targetFactory.close();}
    }
}
