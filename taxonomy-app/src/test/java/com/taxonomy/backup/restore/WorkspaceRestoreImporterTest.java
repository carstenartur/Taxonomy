package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.runtime.*;
import com.taxonomy.backup.snapshot.*;
import com.taxonomy.security.backup.IdentityBackupContributor;
import com.taxonomy.workspace.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.workspace.service.RepositoryContext;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.taxonomy.workspace.backup.WorkspaceGitHistoryReferences.*;
import static org.assertj.core.api.Assertions.*;

/** Real HSQL owner export/archive/import plus native Git core proof; host DB-Git acceptance is separate. */
class WorkspaceRestoreImporterTest {
    @TempDir Path root;
    private final PrincipalId principal=PrincipalId.create();
    private final BackupRepositoryKey central=new BackupRepositoryKey("source-repository",null),workspace=new BackupRepositoryKey("source-repository","source-workspace");
    private final String editor=RepositoryContext.workspace(workspace.repositoryId(),workspace.workspaceId(),"main","source-owner").repositoryWorkspaceScopeKey();
    private static final String COMMAND="00000000-0000-0000-0000-000000000019",SAVE="00000000-0000-0000-0000-000000000020",RAW="Unsaved architecture\nλ\n",TIME="2026-10-10T00:00:00Z";

    @Test void historyOwnerImportsExactRoutingRawSavedStateAndInterruptedEvidenceWithoutAuthorityReplay()throws Exception {
        try(var fixture=fixture()) {
            var generation=fixture.generation();var importer=new WorkspaceRestoreImporter();
            var result=importer.restore(fixture.archive,generation,fixture.principals,fixture.git,()->{});
            var target=new JdbcTemplate(fixture.target);String targetEditor=RepositoryContext.workspace("target-repository","target-workspace","main","target-owner").repositoryWorkspaceScopeKey();
            assertThat(result.editorScopes()).containsEntry(new SourceRecordId("workspace.editor",editor),targetEditor);
            assertThat(target.queryForObject("select storage_repository_name from system_repository",String.class)).isEqualTo("target-central-storage");
            assertThat(target.queryForObject("select owner_id from system_repository",String.class)).isEqualTo("target-owner");
            assertThat(target.queryForObject("select visibility from system_repository",String.class)).isEqualTo("PRIVATE");
            assertThat(target.queryForObject("select primary_repo from system_repository",Boolean.class)).isFalse();
            assertThat(target.queryForObject("select source_repository_id from user_workspace",String.class)).isEqualTo("target-repository");
            assertThat(target.queryForObject("select workspace_id from user_workspace",String.class)).isEqualTo("target-workspace");
            assertThat(target.queryForObject("select current_commit from user_workspace",String.class)).isEqualTo(fixture.commit);
            assertThat(target.queryForObject("select dsl from editor_workspace",String.class)).isEqualTo("1:"+RAW);
            assertThat(target.queryForObject("select scope_id from editor_workspace",String.class)).isEqualTo(targetEditor);
            assertThat(target.queryForObject("select semantic_revision from editor_workspace",Long.class)).isEqualTo(7);
            assertThat(target.queryForObject("select checkpoint_revision from editor_workspace",Long.class)).isEqualTo(4);
            assertThat(target.queryForObject("select pending_checkpoint from editor_workspace",String.class)).isNull();
            assertThat(target.queryForObject("select checkpoint_commit from editor_workspace",String.class)).isEqualTo(fixture.commit);
            assertThat(target.queryForObject("select id from editor_operation",String.class)).isEqualTo(targetEditor+":"+COMMAND);
            assertThat(target.queryForObject("select after_dsl from editor_operation",String.class)).isEqualTo("1:"+RAW);
            assertThat(target.queryForObject("select completed from editor_checkpoint",Boolean.class)).isTrue();
            assertThat(target.queryForObject("select failure_code from editor_checkpoint",String.class)).isEqualTo("RESTORED_INTERRUPTED");
            assertThat(target.queryForObject("select commit_id from architecture_commit_index",String.class)).isEqualTo(fixture.commit);
            assertThat(target.queryForObject("select workspace_scope_key from architecture_commit_index",String.class)).isEqualTo(com.taxonomy.versioning.model.ArchitectureCommitIndex.scopeKeyFor("target-workspace"));
            assertThat(target.queryForObject("select count(*) from repository_membership",Long.class)).isZero();
            assertThat(target.queryForObject("select count(*) from context_history_record",Long.class)).isZero();
            assertThat(target.queryForObject("select record_json from restore_workspace_evidence where record_kind='working-states'",String.class)).contains(SAVE,RAW.replace("\n","\\n"));
            assertThat(target.queryForObject("select classification from restore_workspace_evidence where record_kind='memberships'",String.class)).isEqualTo("INERT_SOURCE_AUTHORITY");
            assertThat(target.queryForObject("select count(*) from restore_workspace_owner",Long.class)).isEqualTo(1);
            assertThat(new WorkspaceRestoreImporter().restore(fixture.archive,generation,fixture.principals,fixture.git,()->{})).isEqualTo(result);
            assertThat(target.queryForObject("select count(*) from editor_workspace",Long.class)).isEqualTo(1);
            var source=new JdbcTemplate(fixture.source);
            assertThat(source.queryForObject("select pending_checkpoint from editor_workspace",String.class)).isEqualTo(SAVE);
            assertThat(source.queryForObject("select owner_id from system_repository",String.class)).isEqualTo("source-owner");
            assertThat(source.queryForObject("select count(*) from repository_membership",Long.class)).isEqualTo(1);
        }
    }
    @Test void cancellationRollsBackOwnerAndRestartVerifiesCommittedRowsInsteadOfDuplicatingThem()throws Exception {
        try(var fixture=fixture()) {
            var generation=fixture.generation();var target=new JdbcTemplate(fixture.target);var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
            BackupCheckpoint stop=()->{if(cancelled.get())throw new InterruptedIOException("stop after workspace write");};
            var observed=new org.springframework.jdbc.datasource.AbstractDataSource(){
                @Override public java.sql.Connection getConnection()throws java.sql.SQLException {
                    var connection=fixture.target.getConnection();return(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                        Object value=invoke(method,connection,args);
                        if(method.getName().equals("prepareStatement")&&((String)args[0]).startsWith("insert into editor_workspace"))cancelled.set(true);
                        return value;
                    });
                }
                @Override public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{throw new java.sql.SQLException("Explicit credentials unsupported");}
            };
            var guarded=RestoreTargetGeneration.verified(generation.generationId(),fixture.source,observed,fixture.factory,allocations(),()->{});
            assertThatThrownBy(()->new WorkspaceRestoreImporter().restore(fixture.archive,guarded,fixture.principals,fixture.git,stop)).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.interrupted()).isTrue();assertThat(target.queryForObject("select count(*) from system_repository",Long.class)).isZero();
            assertThat(target.queryForObject("select count(*) from restore_workspace_owner",Long.class)).isZero();
            var committed=new java.util.concurrent.atomic.AtomicBoolean();
            var commitObserved=new org.springframework.jdbc.datasource.AbstractDataSource(){
                @Override public java.sql.Connection getConnection()throws java.sql.SQLException {
                    var connection=fixture.target.getConnection();return(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                        Object value=invoke(method,connection,args);if(method.getName().equals("commit"))committed.set(true);return value;
                    });
                }
                @Override public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{throw new java.sql.SQLException("Explicit credentials unsupported");}
            };
            var commitGeneration=RestoreTargetGeneration.verified(generation.generationId(),fixture.source,commitObserved,fixture.factory,allocations(),()->{});
            BackupCheckpoint lostAck=()->{if(committed.get())throw new InterruptedIOException("lost completion acknowledgement");};
            assertThatThrownBy(()->new WorkspaceRestoreImporter().restore(fixture.archive,commitGeneration,fixture.principals,fixture.git,lostAck)).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.interrupted()).isTrue();new WorkspaceRestoreImporter().restore(fixture.archive,generation,fixture.principals,fixture.git,()->{});
            target.update("update editor_workspace set dsl='1:changed saved work'");
            assertThatThrownBy(()->new WorkspaceRestoreImporter().restore(fixture.archive,generation,fixture.principals,fixture.git,()->{})).isInstanceOf(IOException.class).hasMessageContaining("proof");
        }
    }
    @Test void centralIndexUsesItsReviewedSharedScopeWithoutInventingAWorkspaceOrPrincipal()throws Exception {
        try(var fixture=fixture(true)) {
            var result=new WorkspaceRestoreImporter().restore(fixture.archive,fixture.generation(),fixture.principals,fixture.git,()->{});
            var target=new JdbcTemplate(fixture.target);
            assertThat(target.queryForObject("select count(*) from architecture_commit_index",Long.class)).isEqualTo(2);
            assertThat(target.queryForObject("select workspace_scope_key from architecture_commit_index where workspace_id is null",String.class)).isEqualTo(com.taxonomy.versioning.model.ArchitectureCommitIndex.scopeKeyFor(null));
            assertThat(target.queryForObject("select repository_id from architecture_commit_index where workspace_id is null",String.class)).isEqualTo("target-repository");
            assertThat(new WorkspaceRestoreImporter().restore(fixture.archive,fixture.generation(),fixture.principals,fixture.git,()->{})).isEqualTo(result);
        }
    }
    @Test void systemOwnershipAndHistoricalNavigationLabelsStayPrivateWithoutInventedIdentityAuthority()throws Exception {
        try(var fixture=fixture(false,true,true)) {
            var result=new WorkspaceRestoreImporter().restore(fixture.archive,fixture.generation(),fixture.principals,fixture.git,()->{});
            assertThat(result.targetRows()).isZero();assertThat(result.editorScopes()).isEmpty();var target=new JdbcTemplate(fixture.target);
            for(String table:List.of("system_repository","user_workspace","repository_membership","sync_state","editor_workspace","editor_operation","editor_checkpoint","architecture_commit_index","context_history_record"))
                assertThat(target.queryForObject("select count(*) from "+table,Long.class)).as(table).isZero();
            assertThat(target.queryForObject("select record_json from restore_workspace_evidence where record_kind='repositories'",String.class)).contains("unmapped-system-label");
            assertThat(target.queryForObject("select classification from restore_workspace_evidence where record_kind='repositories'",String.class)).isEqualTo("PRIVATE_QUARANTINE");
            assertThat(target.queryForObject("select record_json from restore_workspace_evidence where record_kind='context-history'",String.class)).contains("deleted-historical-navigation-label");
            assertThat(new WorkspaceRestoreImporter().restore(fixture.archive,fixture.generation(),fixture.principals,fixture.git,()->{})).isEqualTo(result);
        }
    }
    @Test void activeReroutingAtActualStatementPreparationFailsBeforeExecutingAnyWorkspaceMutation()throws Exception {
        try(var fixture=fixture()) {
            var active=new org.springframework.jdbc.datasource.DelegatingDataSource(fixture.source);var executed=new java.util.concurrent.atomic.AtomicInteger();
            var observed=new org.springframework.jdbc.datasource.AbstractDataSource(){
                @Override public java.sql.Connection getConnection()throws java.sql.SQLException {
                    var connection=fixture.target.getConnection();return(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                        Object value=invoke(method,connection,args);
                        if(!method.getName().equals("prepareStatement")||!((String)args[0]).startsWith("insert into system_repository"))return value;
                        active.setTargetDataSource(fixture.target);var statement=(java.sql.PreparedStatement)value;
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.PreparedStatement.class},(stmtProxy,stmtMethod,stmtArgs)->{
                            if(stmtMethod.getName().equals("executeUpdate"))executed.incrementAndGet();return invoke(stmtMethod,statement,stmtArgs);
                        });
                    });
                }
                @Override public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{throw new java.sql.SQLException("Explicit credentials unsupported");}
            };
            var generation=RestoreTargetGeneration.verified(fixture.generationId,active,observed,fixture.factory,allocations(),()->{});
            assertThatThrownBy(()->new WorkspaceRestoreImporter().restore(fixture.archive,generation,fixture.principals,fixture.git,()->{})).isInstanceOf(IOException.class).hasMessageContaining("binding");
            assertThat(executed).as("the actually prepared statement must remain unexecuted after active aliasing").hasValue(0);
            var target=new JdbcTemplate(fixture.target);assertThat(target.queryForObject("select count(*) from system_repository",Long.class)).isZero();
            assertThat(target.queryForObject("select count(*) from restore_workspace_owner",Long.class)).isZero();
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"physical-name","split-repository","foreign-required"})
    void callerApprovedProofStillRequiresActualRuntimeRoutingAndSourceArchiveClosure(String fault)throws Exception {
        try(var fixture=fixture()) {
            var allocations=new HashMap<>(allocations());var proofs=new HashMap<>(fixture.git.repositories());var old=proofs.get(workspace);
            if(fault.equals("foreign-required")) {
                var required=new HashSet<>(old.restoredState().requiredCommits());required.add("b".repeat(40));var commits=new HashMap<>(old.commitMappings());commits.put("b".repeat(40),"b".repeat(40));
                proofs.put(workspace,new GitRestoreImporter.RepositoryProof(old.targetKey(),old.physicalStorageName(),new SnapshotContext.RepositoryState(old.restoredState().refs(),old.restoredState().symbolicHead(),Map.of(),required),commits,old.repositoryDigest()));
            } else {
                var wrong=fault.equals("physical-name")?new RestoreRepositoryAllocation(old.targetKey(),"approved-but-runtime-wrong"):
                        new RestoreRepositoryAllocation(new BackupRepositoryKey("split-target-repository","target-workspace"),old.physicalStorageName());
                allocations.put(workspace,wrong);proofs.put(workspace,new GitRestoreImporter.RepositoryProof(wrong.targetKey(),wrong.physicalStorageName(),old.restoredState(),old.commitMappings(),old.repositoryDigest()));
            }
            var generation=RestoreTargetGeneration.verified(fixture.generationId,fixture.source,fixture.target,fixture.factory,allocations,()->{});
            assertThatThrownBy(()->new WorkspaceRestoreImporter().restore(fixture.archive,generation,fixture.principals,new GitRestoreImporter.Result(proofs),()->{})).isInstanceOf(IOException.class);
            var target=new JdbcTemplate(fixture.target);assertThat(target.queryForObject("select count(*) from system_repository",Long.class)).isZero();assertThat(target.queryForObject("select count(*) from restore_workspace_owner",Long.class)).isZero();
        }
    }
    private Fixture fixture()throws Exception{return fixture(false);}
    private Fixture fixture(boolean centralIndex)throws Exception{return fixture(centralIndex,false,false);}
    private Fixture fixture(boolean centralIndex,boolean systemOwner,boolean historicalUnknownPrincipal)throws Exception {
        Path nativeSource=root.resolve(UUID.randomUUID()+"-source");Files.createDirectories(nativeSource);
        var git=Git.init().setDirectory(nativeSource.toFile()).setInitialBranch("main").call();Files.writeString(nativeSource.resolve("architecture.taxdsl"),"architecture Original {}");git.add().addFilepattern(".").call();
        String commit=git.commit().setMessage("Original source history").setAuthor(new PersonIdent("Original","source@example.test",Date.from(Instant.parse(TIME)),TimeZone.getTimeZone("UTC"))).setCommitter(new PersonIdent("Original","source@example.test",Date.from(Instant.parse(TIME)),TimeZone.getTimeZone("UTC"))).call().name();
        var source=database();RestoreSchemaMigration.bootstrap(database(),source,()->{});var rows=new JdbcTemplate(source);
        rows.update("insert into app_principal(principal_id,scope_key,enabled) values(?,'source-owner',1)",principal.value().toString());
        rows.update("insert into system_repository(repository_id,slug,display_name,visibility,lifecycle_state,owner_type,owner_id,topology_mode,default_branch,fork_point_commit,last_fetch_commit,primary_repo,created_at,version) values('source-repository','source-slug','Original','PUBLIC','ACTIVE','USER','source-owner','INTERNAL_SHARED','main',?,?,true,?,0)",commit,commit,java.sql.Timestamp.from(Instant.parse(TIME)));
        rows.update("insert into user_workspace(workspace_id,username,display_name,current_branch,base_branch,shared,created_at,provisioning_status,topology_mode,source_repository_id,source_branch,relationship_type,base_commit,current_commit,last_fetched_commit,last_integrated_commit,sync_target_branch,archived,is_default) values('source-workspace','source-owner','Original work','main','main',true,?,'READY','INTERNAL_SHARED','source-repository','main','WORKING_COPY',?,?,?,?,'main',false,true)",java.sql.Timestamp.from(Instant.parse(TIME)),commit,commit,commit,commit);
        rows.update("insert into repository_membership(repository_id,username,repository_role,created_at,created_by,version) values('source-repository','source-owner','OWNER',?,'source-owner',0)",java.sql.Timestamp.from(Instant.parse(TIME)));
        rows.update("insert into sync_state(username,workspace_id,last_synced_commit_id,last_published_commit_id,sync_status,unpublished_commit_count,created_at) values('source-owner','source-workspace',?,?,'SYNCED',0,?)",commit,commit,java.sql.Timestamp.from(Instant.parse(TIME)));
        rows.update("insert into editor_workspace values(4,0,7,?,?,?,'source-repository','source-workspace','main',?)",SAVE,commit,editor,"1:"+RAW);
        rows.update("insert into editor_operation(body_version,previous_revision,semantic_revision,causation_id,command_id,correlation_id,occurred_at,fingerprint,scope_id,kind,id,actor,rationale,affected_ids,after_dsl,before_dsl) values(1,6,7,?,?,?,?,'"+"c".repeat(64)+"',?,'CHANGE',?,'source-owner','Original edit','1:[]',?,'1:Original before')",COMMAND,COMMAND,COMMAND,TIME,editor,editor+":"+COMMAND,"1:"+RAW);
        rows.update("insert into editor_checkpoint(commit_created,completed,from_revision,semantic_revision,command_id,commit_id,expected_commit,occurred_at,origin,fingerprint,scope_id,id,actor,rationale,dsl) values(false,false,0,4,?,?,?,?,'USER','"+"d".repeat(64)+"',?,?,'source-owner','Interrupted save','1:Original checkpoint')",SAVE,commit,commit,TIME,editor,editor+":"+SAVE);
        rows.update("insert into architecture_commit_index(commit_timestamp,indexed_at,commit_id,branch,repository_id,workspace_id,workspace_scope_key,message) values(?,?,?,'main','source-repository','source-workspace','source-workspace','Original indexed commit')",java.sql.Timestamp.from(Instant.parse(TIME)),java.sql.Timestamp.from(Instant.parse(TIME)),commit);
        if(centralIndex)rows.update("insert into architecture_commit_index(commit_timestamp,indexed_at,commit_id,branch,repository_id,workspace_scope_key,message) values(?,?,?,'main','source-repository','__shared__','Original central commit')",java.sql.Timestamp.from(Instant.parse(TIME)),java.sql.Timestamp.from(Instant.parse(TIME)),commit);
        rows.update("insert into context_history_record(created_at,from_commit_id,to_commit_id,username,from_context_id,to_context_id,from_branch,to_branch) values(?,?,?,'source-owner',?,?,'main','main')",java.sql.Timestamp.from(Instant.parse(TIME)),commit,commit,UUID.randomUUID().toString(),UUID.randomUUID().toString());
        if(systemOwner)rows.update("update system_repository set owner_type='SYSTEM',owner_id='unmapped-system-label'");
        if(historicalUnknownPrincipal)rows.update("update context_history_record set username='deleted-historical-navigation-label'");
        var captures=new LinkedHashMap<BackupRepositoryKey,GitHistoryCapture>();
        captures.put(central,GitHistoryCapture.capture(git.getRepository(),Set.of(commit),Map.of(),GitHistoryCapture.Limits.defaults(),()->{}));
        captures.put(workspace,GitHistoryCapture.capture(git.getRepository(),Set.of(commit),Map.of("main",new SnapshotContext.WorkingState(7,commit,4)),GitHistoryCapture.Limits.defaults(),()->{}));
        var archive=export(source,captures,commit);var target=database();RestoreSchemaMigration.bootstrap(source,target,()->{});
        var factory=RestoreSchemaMigration.openValidatedFactory(source,target,model(),()->{});var principals=Map.of(principal,new RestorePrincipalAllocation(PrincipalId.create(),"target-owner",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET));
        var generation=RestoreTargetGeneration.verified(UUID.randomUUID(),source,target,factory,allocations(),()->{});new RestoreIdentityImporter().restore(archive,generation,principals,()->{});
        var proofs=new HashMap<BackupRepositoryKey,GitRestoreImporter.RepositoryProof>();
        for(var key:captures.keySet()) {
            String archiveId=key.workspaceId()==null?"central":"workspace";var capture=captures.get(key);var allocation=allocations().get(key);
            try(var nativeTarget=Git.init().setBare(true).setDirectory(root.resolve(UUID.randomUUID()+"-target.git").toFile()).call()) {
                var original=GitHistoryRestore.restore(git.getRepository(),GitHistoryRestore.Source.captured(capture,new GitReflogProvenance.Origin(archive.archiveSha256(),key,archiveId)),nativeTarget.getRepository(),GitHistoryCapture.Limits.defaults(),UUID.randomUUID(),new PersonIdent("Restore operator","operator@example.test"),()->{});
                assertThat(nativeTarget.getRepository().resolve(commit).name()).isEqualTo(commit);
                proofs.put(key,new GitRestoreImporter.RepositoryProof(allocation.targetKey(),allocation.physicalStorageName(),original.state(),original.commitMappings(),original.repositorySha256()));
            }
        }
        return new Fixture(source,target,factory,archive,principals,new GitRestoreImporter.Result(proofs),git,commit,generation.generationId());
    }
    private VerifiedBackup export(JDBCDataSource source,Map<BackupRepositoryKey,GitHistoryCapture> captures,String commit)throws Exception {
        BackupMaintenanceLease.initialize(source);var prerequisite=BackupFeaturePrerequisites.discover();var profile=BackupProfile.INSTALLATION_FULL;
        var data=new WorkspaceBackupContributor(source,dsl->dsl);var contributor=new BackupContributor(){
            public BackupComponentId componentId(){return new BackupComponentId("workspace");}
            public int schemaVersion(){return 1;}
            public void write(SnapshotContext snapshot,ComponentSink sink)throws IOException {
                data.write(snapshot,sink);for(var key:captures.keySet())captures.get(key).write(key.workspaceId()==null?"central":"workspace",GitRepresentation.BUNDLE,profile,sink);
                var refs=new ArrayList<CommitReference>();
                var r=new SourceRecordId("workspace.repository","source-repository");for(var field:List.of(Field.REPOSITORY_FORK_POINT,Field.REPOSITORY_LAST_FETCH))refs.add(new CommitReference(r,field,central,commit));
                var w=new SourceRecordId("workspace.workspace","source-workspace");for(var field:List.of(Field.WORKSPACE_BASE,Field.WORKSPACE_CURRENT,Field.WORKSPACE_LAST_FETCHED,Field.WORKSPACE_LAST_INTEGRATED))refs.add(new CommitReference(w,field,workspace,commit));
                var rows=new JdbcTemplate(source);var sync=new SourceRecordId("workspace.sync",rows.queryForObject("select id from sync_state",String.class));for(var field:List.of(Field.SYNC_LAST_SYNCED,Field.SYNC_LAST_PUBLISHED))refs.add(new CommitReference(sync,field,workspace,commit));
                refs.add(new CommitReference(new SourceRecordId("workspace.editor",editor),Field.EDITOR_CHECKPOINT,workspace,commit));
                var cp=new SourceRecordId("workspace.checkpoint",editor+":"+SAVE);for(var field:List.of(Field.CHECKPOINT_EXPECTED,Field.CHECKPOINT_COMMIT))refs.add(new CommitReference(cp,field,workspace,commit));
                for(var index:rows.queryForList("select id,workspace_id from architecture_commit_index"))refs.add(new CommitReference(new SourceRecordId("workspace.commit-index",index.get("ID").toString()),Field.INDEX_COMMIT,index.get("WORKSPACE_ID")==null?central:workspace,commit));
                var context=new SourceRecordId("workspace.context-history",rows.queryForObject("select id from context_history_record",String.class));for(var field:List.of(Field.CONTEXT_FROM,Field.CONTEXT_TO))refs.add(new CommitReference(context,field,central,commit));
                dataset(sink,"git-commit-references",profile,refs);dataset(sink,"git-working-copy-proofs",profile,List.of(new WorkingCopyProof(new SourceRecordId("workspace.editor",editor),workspace,"main",new SnapshotContext.WorkingState(7,commit,4),com.taxonomy.identity.StableIdentityHash.sha256(RAW),SAVE)));
            }
        };
        var policy=new BackupAccessPolicy(){public boolean isEnabled(PrincipalId id){return true;}public boolean hasCapability(PrincipalId p,BackupCapability c,BackupScope s){return true;}public boolean canRead(PrincipalId p,BackupRepositoryKey k){return true;}public boolean canReadVersion(PrincipalId p,BackupRepositoryKey k,String c){return true;}};
        var authorization=new BackupAuthorizationService(policy,Clock.systemUTC());var request=new BackupRequest(profile,new BackupScope.Installation(),new BackupTime.History(),GitRepresentation.BUNDLE,SecretsSelection.EXCLUDE);
        var repositories=captures.entrySet().stream().map(e->new BackupManifest.Repository(e.getKey(),e.getKey().workspaceId()==null?"central":"workspace",GitRepresentation.BUNDLE,e.getValue().state(),null,null,null)).toList();
        String installation=new JdbcTemplate(source).queryForObject("select installation_id from principal_installation",String.class);
        var components=Map.of(new BackupComponentId("application"),new BackupSnapshotCoordinator.CaptureComponent(1,BackupCompleteness.COMPLETE,Set.of()),new BackupComponentId("workspace"),new BackupSnapshotCoordinator.CaptureComponent(1,BackupCompleteness.COMPLETE,Set.of(new BackupComponentId("application"))));
        var plan=new BackupSnapshotCoordinator.CapturePlan("1.5.1-SNAPSHOT","workspace-owner-fixture",installation,repositories,components,List.of(),List.of(),new SourceDatabaseMetadata(1,SourceDatabaseFamily.HSQLDB));
        var coordinator=new BackupSnapshotCoordinator(new BackupMaintenanceLease(source,Duration.ofSeconds(30)),authorization,ignored->plan,List.of(new IdentityBackupContributor(source,(snapshot,checkpoint)->Set.of(principal)),contributor),root.resolve(UUID.randomUUID()+"-spool"),BackupSnapshotCoordinator.CaptureLimits.defaults(),Clock.systemUTC(),prerequisite);
        Path output=root.resolve(UUID.randomUUID()+".taxbackup");try(var captured=coordinator.capture(authorization.authorize(principal,request))){new BackupArchiveWriter(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),prerequisite).write(captured,output);}
        return new BackupArchiveReader(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),"1.5.1-SNAPSHOT",Map.of(new BackupComponentId("application"),1,new BackupComponentId("workspace"),1,new BackupComponentId("capture-proof"),1),prerequisite).verify(output);
    }
    private static void dataset(ComponentSink sink,String kind,BackupProfile profile,List<? extends Record> records)throws IOException {
        sink.writeGenerated("data/workspace/"+kind+".ndjson",out->{out.write(PortableRows.json().writeValueAsBytes(new PortableRows.Header(1,kind,profile)));out.write('\n');for(var row:records){sink.checkpoint();out.write(PortableRows.json().writeValueAsBytes(row));out.write('\n');}});
    }
    private Map<BackupRepositoryKey,RestoreRepositoryAllocation> allocations(){return Map.of(central,new RestoreRepositoryAllocation(new BackupRepositoryKey("target-repository",null),"target-central-storage"),workspace,new RestoreRepositoryAllocation(new BackupRepositoryKey("target-repository","target-workspace"),com.taxonomy.workspace.storage.DslGitRepositoryFactory.workspaceRepositoryName("target-workspace")));}
    private static Object invoke(java.lang.reflect.Method method,Object target,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}}
    private static List<Class<?>> model()throws Exception {try(var input=WorkspaceRestoreImporterTest.class.getResourceAsStream("/db/restore/hsqldb/approved-business-model.txt")){var model=new ArrayList<Class<?>>();for(String name:new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).lines().toList())if(!name.isBlank()&&!name.startsWith("#"))model.add(Class.forName(name));return model;}}
    private static JDBCDataSource database(){var ds=new JDBCDataSource();ds.setURL("jdbc:hsqldb:mem:workspace-restore-"+UUID.randomUUID());ds.setUser("sa");return ds;}
    private record Fixture(JDBCDataSource source,JDBCDataSource target,org.hibernate.SessionFactory factory,VerifiedBackup archive,Map<PrincipalId,RestorePrincipalAllocation> principals,GitRestoreImporter.Result git,Git nativeSource,String commit,UUID generationId)implements AutoCloseable {
        RestoreTargetGeneration generation()throws IOException{return RestoreTargetGeneration.verified(generationId,source,target,factory,Map.of(new BackupRepositoryKey("source-repository",null),new RestoreRepositoryAllocation(new BackupRepositoryKey("target-repository",null),"target-central-storage"),new BackupRepositoryKey("source-repository","source-workspace"),new RestoreRepositoryAllocation(new BackupRepositoryKey("target-repository","target-workspace"),com.taxonomy.workspace.storage.DslGitRepositoryFactory.workspaceRepositoryName("target-workspace"))),()->{});}
        public void close()throws Exception{archive.close();factory.close();nativeSource.close();}
    }
}
