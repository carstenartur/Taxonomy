package com.taxonomy.backup.restore;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.VerifiedBackup;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.backup.WorkspaceBackupContributor.*;
import com.taxonomy.workspace.backup.WorkspaceGitHistoryReferences.*;
import com.taxonomy.workspace.model.RepositoryOwnerType;
import com.taxonomy.workspace.service.RepositoryContext;
import org.eclipse.jgit.lib.Repository;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Imports compiled workspace history DTOs into one isolated generation. No activation, ACL or dispatch. */
public final class WorkspaceRestoreImporter {
    private static final BackupComponentId OWNER=new BackupComponentId("workspace");
    private static final int MAX_ROWS=100_000,MAX_BYTES=32*1024*1024;
    private static final JsonMapper JSON=JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final List<String> TABLES=List.of("system_repository","user_workspace","repository_membership","sync_state","editor_workspace","editor_operation","editor_checkpoint","architecture_commit_index","context_history_record");
    public record Result(Map<SourceRecordId,String> editorScopes,int targetRows,int evidenceRows) {
        public Result {editorScopes=Map.copyOf(editorScopes);}
    }
    private record Evidence(String kind,String key,String json,String classification) { }
    private record TargetRow(String kind,String sourceKey,String table,String keyColumn,Object key,Map<String,Object> columns) { }
    private record RepoProof(BackupRepositoryKey source,RestoreRepositoryAllocation allocation,BackupRepositoryKey target,String storage,
            Map<String,String> refs,String head,Map<String,SnapshotContext.WorkingState> working,List<String> required,Map<String,String> mappings,String digest) { }

    public Result restore(VerifiedBackup archive,RestoreTargetGeneration generation,
                          Map<PrincipalId,RestorePrincipalAllocation> principalAllocations,GitRestoreImporter.Result git,
                          BackupCheckpoint checkpoint)throws IOException {
        try {
            principalAllocations=Map.copyOf(principalAllocations);
            var identity=new RestoreIdentityImporter().preview(archive,principalAllocations,checkpoint);
            var model=inspect(archive,generation,identity,principalAllocations,git,checkpoint);
            generation.requireUnchanged(checkpoint);
            try(var connection=generation.dataSource().getConnection()) {
                generation.requireConnection(connection,checkpoint);connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                try(var rollback=new Rollback(connection)) {
                    requireIdentity(connection,archive,generation,principalAllocations,checkpoint);
                    var owner=owner(connection,checkpoint);
                    if(owner!=null) {
                        if(!owner.equals(List.of(generation.generationId().toString(),archive.archiveSha256(),model.planHash)))throw new IOException("Workspace owner belongs to another restore plan");
                        verify(connection,model,checkpoint);generation.requireConnection(connection,checkpoint);return model.result();
                    }
                    requireEmpty(connection,checkpoint);
                    for(var row:model.targets.values()) {
                        checkpoint.check();insertTarget(connection,row,generation,checkpoint);
                        update(connection,generation,checkpoint,"insert into restore_workspace_mapping(record_kind,record_key,target_table,target_key,target_sha256) values(?,?,?,?,?)",
                                row.kind(),row.sourceKey(),row.table(),row.key().toString(),hash(row.columns()));
                        checkpoint.check();
                    }
                    for(var row:model.evidence.values()) {
                        checkpoint.check();update(connection,generation,checkpoint,"insert into restore_workspace_evidence(record_kind,record_key,record_json,sha256,classification) values(?,?,?,?,?)",
                                row.kind(),row.key(),row.json(),StableIdentityHash.sha256(row.json()),row.classification());
                    }
                    update(connection,generation,checkpoint,"insert into restore_workspace_owner(owner_id,generation_id,archive_sha256,plan_sha256) values('workspace',?,?,?)",generation.generationId().toString(),archive.archiveSha256(),model.planHash);
                    verify(connection,model,checkpoint);generation.requireConnection(connection,checkpoint);connection.commit();rollback.committed=true;checkpoint.check();return model.result();
                }
            }
        } catch(InterruptedIOException cancelled){Thread.currentThread().interrupt();throw cancelled;}
        catch(SQLException|RuntimeException failure){throw new IOException("Staged workspace owner import failed");}
    }

    private static Model inspect(VerifiedBackup archive,RestoreTargetGeneration generation,RestoreIdentityImporter.Result identities,
            Map<PrincipalId,RestorePrincipalAllocation> principals,GitRestoreImporter.Result git,BackupCheckpoint checkpoint)throws IOException {
        if(!archive.manifest().request().profile().includesHistory()||archive.manifest().components().stream().noneMatch(c->c.id().equals(OWNER)&&c.version()==1&&c.completeness()==BackupCompleteness.COMPLETE))throw invalid();
        var model=new Model(generation.generationId());
        var repositories=read(archive,"repositories",RepositoryRecord.class,model,r->new SourceRecordId("workspace.repository",r.repositoryId()),checkpoint);
        var workspaces=read(archive,"workspaces",WorkspaceRecord.class,model,r->new SourceRecordId("workspace.workspace",r.workspaceId()),checkpoint);
        var memberships=read(archive,"memberships",MembershipRecord.class,model,MembershipRecord::sourceId,checkpoint);
        var sync=read(archive,"synchronization",SyncRecord.class,model,SyncRecord::sourceId,checkpoint);
        var editors=read(archive,"working-states",WorkingStateRecord.class,model,WorkingStateRecord::sourceId,checkpoint);
        var operations=read(archive,"operations",OperationRecord.class,model,OperationRecord::sourceId,checkpoint);
        var checkpoints=read(archive,"checkpoints",CheckpointRecord.class,model,CheckpointRecord::sourceId,checkpoint);
        var index=read(archive,"commit-index",CommitIndexRecord.class,model,CommitIndexRecord::sourceId,checkpoint);
        var context=read(archive,"context-history",ContextHistoryRecord.class,model,ContextHistoryRecord::sourceId,checkpoint);
        var references=read(archive,"git-commit-references",CommitReference.class,model,r->new SourceRecordId("workspace.git-reference",r.sourceId().kind()+":"+r.sourceId().value()+":"+r.field()),checkpoint);
        var working=read(archive,"git-working-copy-proofs",WorkingCopyProof.class,model,WorkingCopyProof::sourceId,checkpoint);
        var capturedWorking=new HashMap<BackupRepositoryKey,Map<String,SnapshotContext.WorkingState>>();
        for(var saved:working) {
            checkpoint.check();if(capturedWorking.computeIfAbsent(saved.repository(),ignored->new HashMap<>()).putIfAbsent(saved.branch(),saved.state())!=null)throw invalid();
        }
        var sourceKeys=new HashSet<BackupRepositoryKey>();
        for(var repository:archive.manifest().repositories()) {
            checkpoint.check();if(repository.representation()==GitRepresentation.NONE)throw invalid();sourceKeys.add(repository.id());
            var proof=git.repositories().get(repository.id());
            if(proof==null||!proof.restoredState().requiredCommits().equals(repository.captured().requiredCommits())
                    ||!proof.commitMappings().keySet().equals(repository.captured().requiredCommits())
                    ||!Objects.equals(proof.restoredState().symbolicHead(),repository.captured().symbolicHead()))throw invalid();
            for(var mapping:proof.commitMappings().entrySet()){checkpoint.check();if(!mapping.getKey().equals(mapping.getValue()))throw invalid();}
            for(var captured:repository.captured().refs().entrySet()){checkpoint.check();if(!Objects.equals(captured.getValue(),proof.restoredState().refs().get(captured.getKey())))throw invalid();}
            for(var restored:proof.restoredState().refs().entrySet()) {
                checkpoint.check();if(!repository.captured().refs().containsKey(restored.getKey())
                        &&!restored.getKey().equals("refs/taxonomy-backup/required/"+restored.getValue()))throw invalid();
            }
            if(!capturedWorking.getOrDefault(repository.id(),Map.of()).equals(repository.captured().workingStates()))throw invalid();
        }
        var rows=new WorkspaceRestoreReferences.Rows(repositories,workspaces,memberships,sync,editors,operations,checkpoints,index,context);
        var commits=WorkspaceRestoreReferences.resolve(rows,references,working,sourceKeys,generation.allocations(),git,checkpoint);
        var privateScopes=new HashSet<String>();for(var allocation:principals.values())if(allocation.disposition()==RestorePrincipalAllocation.Disposition.QUARANTINE)privateScopes.add(allocation.targetScope());
        var activeRepositories=new HashSet<String>();var activeWorkspaces=new HashSet<String>();var workspaceById=new HashMap<String,WorkspaceRecord>();
        var sourceEditorById=new HashMap<SourceRecordId,WorkingStateRecord>();var operationIds=new HashSet<String>();
        // Personal journal access compares exact actor scopes. Unknown historical actors cannot
        // become a target user's authority through an accidental matching display/scope string.
        // Keep the whole editor history together when any actor lacks an explicit usable mapping.
        var privateEditors=new HashSet<SourceRecordId>();
        for(var row:operations) {
            checkpoint.check();String mapped=identities.sourceScopes().get(row.actor());
            if(mapped==null||privateScopes.contains(mapped))privateEditors.add(row.editor());
        }
        for(var row:checkpoints) {
            checkpoint.check();String mapped=identities.sourceScopes().get(row.actor());
            if(mapped==null||privateScopes.contains(mapped))privateEditors.add(row.editor());
        }
        for(var row:repositories) {
            checkpoint.check();required(row.repositoryId(),255);required(row.defaultBranch(),255);branch(row.defaultBranch());required(row.createdAt(),40);instant(row.createdAt());
            if(row.ownerType()!=RepositoryOwnerType.USER) {model.classify("repositories",row.repositoryId(),"PRIVATE_QUARANTINE");continue;}
            String scope=scope(identities,row.ownerScope());var target=generation.allocations().get(new BackupRepositoryKey(row.repositoryId(),null));
            if(target==null||privateScopes.contains(scope)){model.classify("repositories",row.repositoryId(),"PRIVATE_QUARANTINE");continue;}
            activeRepositories.add(row.repositoryId());var id=new SourceRecordId("workspace.repository",row.repositoryId());
            String upstream=null;if(row.upstreamRepositoryId()!=null){var allocation=generation.allocations().get(new BackupRepositoryKey(row.upstreamRepositoryId(),null));if(allocation==null)throw invalid();upstream=allocation.targetKey().repositoryId();}
            model.target("repositories",row.repositoryId(),"system_repository","repository_id",target.targetKey().repositoryId(),columns(
                    "id",model.numeric(id),"repository_id",target.targetKey().repositoryId(),"storage_repository_name",target.physicalStorageName(),"slug","restored-"+StableIdentityHash.sha256(target.targetKey().repositoryId()).substring(0,24),
                    "display_name",row.displayName(),"description",row.description(),"visibility","PRIVATE","lifecycle_state","ACTIVE","owner_type","USER","owner_id",scope,
                    "topology_mode","INTERNAL_SHARED","default_branch",row.defaultBranch(),"external_url",null,"external_auth_token",null,"upstream_repository_id",upstream,"upstream_branch",row.upstreamBranch(),
                    "fork_point_commit",commit(commits,id,Field.REPOSITORY_FORK_POINT,row.forkPointCommit()),"last_fetch_at",timestamp(row.lastFetchAt()),"last_push_at",timestamp(row.lastPushAt()),
                    "last_fetch_commit",commit(commits,id,Field.REPOSITORY_LAST_FETCH,row.lastFetchCommit()),"primary_repo",false,"created_by",row.createdBy(),"created_at",timestamp(row.createdAt()),"updated_at",timestamp(row.updatedAt()),"version",0L,"provisioning_error",null));
        }
        for(var row:workspaces) {
            checkpoint.check();if(workspaceById.putIfAbsent(row.workspaceId(),row)!=null)throw invalid();required(row.ownerScope(),255);branch(row.currentBranch());branch(row.baseBranch());branch(row.sourceBranch());branch(row.syncTargetBranch());instant(row.createdAt());
            String scope=scope(identities,row.ownerScope());if(!activeRepositories.contains(row.repositoryId())||privateScopes.contains(scope)){model.classify("workspaces",row.workspaceId(),"PRIVATE_QUARANTINE");continue;}
            var target=generation.allocations().get(new BackupRepositoryKey(row.repositoryId(),row.workspaceId()));if(target==null)throw invalid();activeWorkspaces.add(row.workspaceId());
            var id=new SourceRecordId("workspace.workspace",row.workspaceId());model.target("workspaces",row.workspaceId(),"user_workspace","workspace_id",target.targetKey().workspaceId(),columns(
                    "id",model.numeric(id),"workspace_id",target.targetKey().workspaceId(),"username",scope,"display_name",row.displayName(),"current_branch",row.currentBranch(),"base_branch",row.baseBranch(),"shared",false,
                    "created_at",timestamp(row.createdAt()),"last_accessed_at",timestamp(row.lastAccessedAt()),"provisioning_status","READY","topology_mode","INTERNAL_SHARED","source_repository_id",target.targetKey().repositoryId(),
                    "source_branch",row.sourceBranch(),"relationship_type",row.relationshipType()==null?null:row.relationshipType().name(),"base_commit",commit(commits,id,Field.WORKSPACE_BASE,row.baseCommit()),"current_commit",commit(commits,id,Field.WORKSPACE_CURRENT,row.currentCommit()),
                    "last_fetched_commit",commit(commits,id,Field.WORKSPACE_LAST_FETCHED,row.lastFetchedCommit()),"last_integrated_commit",commit(commits,id,Field.WORKSPACE_LAST_INTEGRATED,row.lastIntegratedCommit()),"sync_target_branch",row.syncTargetBranch(),
                    "provisioned_at",timestamp(row.provisionedAt()),"description",row.description(),"archived",row.archived(),"is_default",false,"provisioning_error",null));
        }
        for(var row:memberships) {checkpoint.check();id(row.sourceId(),"workspace.membership");if(!sourceKeys.stream().anyMatch(key->key.repositoryId().equals(row.repositoryId()))||row.role()==null)throw invalid();scope(identities,row.principalScope());instant(row.createdAt());model.classify("memberships",row.sourceId().value(),"INERT_SOURCE_AUTHORITY");}
        for(var row:sync) {
            checkpoint.check();var parent=workspaceById.get(row.workspaceId());if(parent==null||!Objects.equals(parent.ownerScope(),row.principalScope()))throw invalid();String scope=scope(identities,row.principalScope());
            if(!activeWorkspaces.contains(row.workspaceId())){model.classify("synchronization",row.sourceId().value(),"PRIVATE_QUARANTINE");continue;}
            var target=generation.allocations().get(new BackupRepositoryKey(parent.repositoryId(),row.workspaceId()));required(row.syncStatus(),255);if(row.unpublishedCommitCount()<0)throw invalid();
            model.target("synchronization",row.sourceId().value(),"sync_state","workspace_id",target.targetKey().workspaceId(),columns("id",model.numeric(row.sourceId()),"username",scope,"workspace_id",target.targetKey().workspaceId(),
                    "last_synced_commit_id",commit(commits,row.sourceId(),Field.SYNC_LAST_SYNCED,row.lastSyncedCommit()),"last_sync_timestamp",timestamp(row.lastSyncAt()),"last_published_commit_id",commit(commits,row.sourceId(),Field.SYNC_LAST_PUBLISHED,row.lastPublishedCommit()),
                    "last_publish_timestamp",timestamp(row.lastPublishAt()),"sync_status","RESTORED_REVIEW_REQUIRED","unpublished_commit_count",row.unpublishedCommitCount(),"created_at",timestamp(row.createdAt()),"updated_at",timestamp(row.updatedAt())));
        }
        for(var row:editors) {
            checkpoint.check();sourceEditorById.put(row.sourceId(),row);if(row.semanticRevision()<0||row.checkpointRevision()<0||row.checkpointRevision()>row.semanticRevision())throw invalid();utf8(row.dsl());canonicalUuidOptional(row.interruptedCheckpoint());
            if(!activeWorkspaces.contains(row.workspaceId())||privateEditors.contains(row.sourceId())){model.classify("working-states",row.sourceId().value(),"PRIVATE_QUARANTINE");continue;}
            var target=generation.allocations().get(new BackupRepositoryKey(row.repositoryId(),row.workspaceId()));var parent=workspaceById.get(row.workspaceId());String scope=scope(identities,parent.ownerScope());
            String targetEditor=RepositoryContext.workspace(target.targetKey().repositoryId(),target.targetKey().workspaceId(),row.branch(),scope).repositoryWorkspaceScopeKey();model.editorScopes.put(row.sourceId(),targetEditor);
            model.target("working-states",row.sourceId().value(),"editor_workspace","scope_id",targetEditor,columns("scope_id",targetEditor,"repository_id",target.targetKey().repositoryId(),"workspace_id",target.targetKey().workspaceId(),"branch",row.branch(),
                    "dsl","1:"+row.dsl(),"semantic_revision",row.semanticRevision(),"checkpoint_commit",commit(commits,row.sourceId(),Field.EDITOR_CHECKPOINT,row.checkpointCommit()),"checkpoint_revision",row.checkpointRevision(),"row_version",0L,"pending_checkpoint",null));
        }
        for(var row:operations) {
            checkpoint.check();id(row.sourceId(),"workspace.operation");id(row.editor(),"workspace.editor");var parent=sourceEditorById.get(row.editor());if(parent==null||!row.sourceId().value().equals(row.editor().value()+":"+row.commandId()))throw invalid();
            canonicalUuid(row.commandId());canonicalUuid(row.correlationId());canonicalUuid(row.causationId());canonicalUuidOptional(row.targetOperationId());required(row.kind(),80);required(row.actor(),320);required(row.rationale(),1000);instant(row.occurredAt());sha(row.fingerprint());
            if(row.previousRevision()<0||row.semanticRevision()!=row.previousRevision()+1||row.semanticRevision()>parent.semanticRevision()||row.bodyVersion()!=1||!operationIds.add(row.sourceId().value()))throw invalid();utf8(row.beforeDsl());utf8(row.afterDsl());utf8(row.affectedIds());
            String targetEditor=model.editorScopes.get(row.editor());if(targetEditor==null){model.classify("operations",row.sourceId().value(),"PRIVATE_QUARANTINE");continue;}
            String targetId=targetEditor+":"+row.commandId();model.target("operations",row.sourceId().value(),"editor_operation","id",targetId,columns("id",targetId,"scope_id",targetEditor,"command_id",row.commandId(),"actor",scope(identities,row.actor()),"occurred_at",row.occurredAt(),"rationale",row.rationale(),
                    "correlation_id",row.correlationId(),"causation_id",row.causationId(),"kind",row.kind(),"target_operation_id",row.targetOperationId(),"previous_revision",row.previousRevision(),"semantic_revision",row.semanticRevision(),"fingerprint",row.fingerprint(),"body_version",row.bodyVersion(),
                    "before_dsl","1:"+row.beforeDsl(),"after_dsl","1:"+row.afterDsl(),"affected_ids","1:"+row.affectedIds()));
        }
        for(var row:checkpoints) {
            checkpoint.check();var parent=sourceEditorById.get(row.editor());if(parent==null||!row.sourceId().value().equals(row.editor().value()+":"+row.commandId()))throw invalid();canonicalUuid(row.commandId());required(row.actor(),320);required(row.rationale(),1000);instant(row.occurredAt());sha(row.fingerprint());required(row.origin(),40);utf8(row.dsl());
            if(row.fromRevision()<0||row.semanticRevision()<row.fromRevision()||row.semanticRevision()>parent.semanticRevision()||row.commitCreated()&&row.commitId()==null)throw invalid();
            String targetEditor=model.editorScopes.get(row.editor());if(targetEditor==null){model.classify("checkpoints",row.sourceId().value(),"PRIVATE_QUARANTINE");continue;}
            String targetId=targetEditor+":"+row.commandId();model.target("checkpoints",row.sourceId().value(),"editor_checkpoint","id",targetId,columns("id",targetId,"scope_id",targetEditor,"command_id",row.commandId(),"actor",scope(identities,row.actor()),"occurred_at",row.occurredAt(),"rationale",row.rationale(),"fingerprint",row.fingerprint(),
                    "from_revision",row.fromRevision(),"semantic_revision",row.semanticRevision(),"expected_commit",commit(commits,row.sourceId(),Field.CHECKPOINT_EXPECTED,row.expectedCommit()),"dsl","1:"+row.dsl(),"commit_id",commit(commits,row.sourceId(),Field.CHECKPOINT_COMMIT,row.commitId()),
                    "completed",true,"commit_created",row.commitCreated(),"failure_code",row.completed()?row.failureCode():"RESTORED_INTERRUPTED","origin",row.origin()));
        }
        for(var row:index) {
            checkpoint.check();var parent=workspaceById.get(row.workspaceId());
            if(row.workspaceId()!=null&&(parent==null||!Objects.equals(parent.repositoryId(),row.repositoryId()))
                    ||!Objects.equals(com.taxonomy.versioning.model.ArchitectureCommitIndex.scopeKeyFor(row.workspaceId()),row.workspaceScope()))throw invalid();branch(row.branch());
            if(row.workspaceId()==null?!activeRepositories.contains(row.repositoryId()):!activeWorkspaces.contains(row.workspaceId())){model.classify("commit-index",row.sourceId().value(),"PRIVATE_QUARANTINE");continue;}
            var target=generation.allocations().get(new BackupRepositoryKey(row.repositoryId(),row.workspaceId()));var targetId=model.numeric(row.sourceId());model.target("commit-index",row.sourceId().value(),"architecture_commit_index","id",targetId,columns(
                    "id",targetId,"repository_id",target.targetKey().repositoryId(),"workspace_id",target.targetKey().workspaceId(),"workspace_scope_key",com.taxonomy.versioning.model.ArchitectureCommitIndex.scopeKeyFor(target.targetKey().workspaceId()),"commit_id",commit(commits,row.sourceId(),Field.INDEX_COMMIT,row.commitId()),"branch",row.branch(),
                    "author",row.author(),"commit_timestamp",timestamp(row.committedAt()),"message",row.message(),"changed_files",row.changedFiles(),"tokenized_change_text",row.changeText(),"affected_element_ids",row.affectedElementIds(),"affected_relation_ids",row.affectedRelationIds(),"indexed_at",timestamp(row.indexedAt())));
        }
        for(var row:context) {checkpoint.check();required(row.principalScope(),255);canonicalUuidOptional(row.fromContext());canonicalUuidOptional(row.toContext());canonicalUuidOptional(row.originContext());instant(row.createdAt());model.classify("context-history",row.sourceId().value(),"PRIVATE_HISTORICAL_SOURCE");}
        var principalsSorted=new TreeMap<String,RestorePrincipalAllocation>();principals.forEach((id,allocation)->principalsSorted.put(id.value().toString(),allocation));
        model.planHash=hash(List.of(gitBinding(git,generation.allocations()),principalsSorted,model.targets.values(),model.evidence.values()));checkpoint.check();return model;
    }

    private static final class Model {
        final UUID generation;final Map<String,Evidence> evidence=new LinkedHashMap<>();final Map<String,TargetRow> targets=new LinkedHashMap<>();final Map<SourceRecordId,String> editorScopes=new HashMap<>();int bytes;String planHash;
        Model(UUID generation){this.generation=generation;}
        void evidence(String kind,String key,Record row)throws IOException {
            required(key,4096);String json=JSON.writeValueAsString(row);budget(utf8(json));if(evidence.putIfAbsent(kind+":"+key,new Evidence(kind,key,json,"PRIVATE_PRIMARY"))!=null)throw invalid();
        }
        void classify(String kind,String key,String classification)throws IOException {var old=evidence.get(kind+":"+key);if(old==null)throw invalid();evidence.put(kind+":"+key,new Evidence(kind,key,old.json(),classification));}
        void target(String kind,String source,String table,String keyColumn,Object key,Map<String,Object> values)throws IOException {
            if(!TABLES.contains(table)||!values.containsKey(keyColumn)||!Objects.equals(values.get(keyColumn),key))throw invalid();
            String unique=table+":"+key;if(targets.containsKey(unique)||targets.size()>=MAX_ROWS)throw invalid();budget(utf8(JSON.writeValueAsString(values)));targets.put(unique,new TargetRow(kind,source,table,keyColumn,key,values));
        }
        long numeric(SourceRecordId source){return Long.parseLong(StableIdentityHash.sha256(generation+":"+source.kind()+":"+source.value()).substring(0,15),16)+1;}
        void budget(int size)throws IOException {if(size>MAX_BYTES-bytes||evidence.size()>=MAX_ROWS)throw new IOException("Workspace restore model budget exceeded");bytes+=size;}
        Result result(){return new Result(editorScopes,targets.size(),evidence.size());}
    }
    @FunctionalInterface private interface SourceId<T>{SourceRecordId id(T row)throws IOException;}
    private static <T extends Record>List<T> read(VerifiedBackup archive,String kind,Class<T> type,Model model,SourceId<T> key,BackupCheckpoint checkpoint)throws IOException {
        var result=new ArrayList<T>();RestoreArchiveRecords.read(archive,OWNER,"data/workspace/"+kind+".ndjson",kind,type,MAX_ROWS,checkpoint,row->{var id=key.id(row);if(id==null)throw invalid();model.evidence(kind,id.value(),row);result.add(row);});return List.copyOf(result);
    }
    private static String scope(RestoreIdentityImporter.Result identities,String source)throws IOException {String target=identities.sourceScopes().get(source);if(target==null)throw new IOException("Workspace owner identity mapping is incomplete");return target;}
    private static String commit(Map<WorkspaceRestoreReferences.Slot,String> commits,SourceRecordId id,Field field,String source)throws IOException {if(source==null||source.isBlank())return null;String target=commits.get(new WorkspaceRestoreReferences.Slot(id,field));if(target==null)throw invalid();return target;}
    private static Map<String,Object> columns(Object...pairs){var values=new LinkedHashMap<String,Object>();for(int n=0;n<pairs.length;n+=2)if(values.put((String)pairs[n],pairs[n+1])!=null)throw new IllegalArgumentException("Duplicate target column");return Collections.unmodifiableMap(values);}
    static String gitBinding(GitRestoreImporter.Result git,Map<BackupRepositoryKey,RestoreRepositoryAllocation> allocations)throws IOException {
        var bound=new ArrayList<RepoProof>();
        for(var key:git.repositories().keySet()) {
            var proof=git.repositories().get(key);var state=proof.restoredState();
            bound.add(new RepoProof(key,allocations.get(key),proof.targetKey(),proof.physicalStorageName(),new TreeMap<>(state.refs()),state.symbolicHead(),
                    new TreeMap<>(state.workingStates()),state.requiredCommits().stream().sorted().toList(),new TreeMap<>(proof.commitMappings()),proof.repositoryDigest()));
        }
        bound.sort(Comparator.comparing(p->p.source().repositoryId()+"\0"+Objects.toString(p.source().workspaceId(),"")));return hash(bound);
    }
    private static String hash(Object value)throws IOException{return StableIdentityHash.sha256(JSON.writeValueAsString(value));}
    private static void id(SourceRecordId id,String kind)throws IOException {if(id==null||!kind.equals(id.kind()))throw invalid();}
    private static void required(String text,int limit)throws IOException{if(text==null||text.isBlank()||text.length()>limit)throw invalid();}
    private static void branch(String branch)throws IOException {if(branch!=null&&!Repository.isValidRefName("refs/heads/"+branch))throw invalid();}
    private static void sha(String value)throws IOException {if(value==null||!value.matches("[a-f0-9]{64}"))throw invalid();}
    private static void canonicalUuid(String value)throws IOException {try{if(value==null||!UUID.fromString(value).toString().equals(value))throw invalid();}catch(IllegalArgumentException failure){throw invalid();}}
    private static void canonicalUuidOptional(String value)throws IOException {if(value!=null)canonicalUuid(value);}
    private static void instant(String text)throws IOException {try{if(text==null)throw invalid();Instant.parse(text);}catch(RuntimeException failure){throw invalid();}}
    private static Timestamp timestamp(String text)throws IOException {if(text==null)return null;instant(text);return Timestamp.from(Instant.parse(text));}
    private static int utf8(String value)throws IOException {if(value==null)throw invalid();try{return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining();}catch(CharacterCodingException failure){throw invalid();}}
    private static IOException invalid(){return new IOException("Invalid typed workspace history evidence");}
    private static PreparedStatement statement(Connection connection,String sql)throws SQLException {var statement=connection.prepareStatement(sql);statement.setMaxRows(MAX_ROWS+1);statement.setQueryTimeout(60);return statement;}
    private static void update(Connection connection,RestoreTargetGeneration generation,BackupCheckpoint checkpoint,String sql,Object...values)throws SQLException,IOException {
        generation.requireConnection(connection,checkpoint);
        try(var statement=statement(connection,sql)) {
            for(int n=0;n<values.length;n++)statement.setObject(n+1,values[n]);
            // Mutable routing can change during prepareStatement; prove the held connection immediately before execution.
            generation.requireConnection(connection,checkpoint);
            if(statement.executeUpdate()!=1)throw new SQLException("Unexpected workspace restore row count");
            generation.requireConnection(connection,checkpoint);
        }
    }
    private static void insertTarget(Connection connection,TargetRow row,RestoreTargetGeneration generation,BackupCheckpoint checkpoint)throws SQLException,IOException {
        String sql="insert into "+row.table()+"("+String.join(",",row.columns().keySet())+") values("+String.join(",",Collections.nCopies(row.columns().size(),"?"))+")";
        update(connection,generation,checkpoint,sql,row.columns().values().toArray());
    }
    private static long count(Connection connection,String table)throws SQLException {try(var statement=statement(connection,"select count(*) from "+table);var rows=statement.executeQuery()){rows.next();return rows.getLong(1);}}
    private static List<String> owner(Connection connection,BackupCheckpoint checkpoint)throws SQLException,IOException {
        if(count(connection,"restore_workspace_owner")>1)throw new IOException("Workspace owner inventory proof failed");
        try(var statement=statement(connection,"select owner_id,generation_id,archive_sha256,plan_sha256 from restore_workspace_owner");var rows=statement.executeQuery()) {checkpoint.check();if(!rows.next())return null;if(!"workspace".equals(rows.getString(1)))throw new IOException("Workspace owner inventory proof failed");return List.of(rows.getString(2),rows.getString(3),rows.getString(4));}
    }
    private static void requireEmpty(Connection connection,BackupCheckpoint checkpoint)throws SQLException,IOException {
        var tables=new ArrayList<>(TABLES);tables.addAll(List.of("restore_workspace_owner","restore_workspace_mapping","restore_workspace_evidence"));for(var table:tables){checkpoint.check();if(count(connection,table)!=0)throw new IOException("Unjournaled workspace target is not empty");}
    }
    private static void requireIdentity(Connection connection,VerifiedBackup archive,RestoreTargetGeneration generation,Map<PrincipalId,RestorePrincipalAllocation> allocations,BackupCheckpoint checkpoint)throws SQLException,IOException {
        try(var statement=statement(connection,"select owner_id,generation_id,archive_sha256 from restore_identity_owner");var rows=statement.executeQuery()) {checkpoint.check();if(!rows.next()||!"identities".equals(rows.getString(1))||!generation.generationId().toString().equals(rows.getString(2))||!archive.archiveSha256().equals(rows.getString(3))||rows.next())throw new IOException("Workspace disabled identity owner proof failed");}
        if(count(connection,"restore_identity_mapping")!=allocations.size()||count(connection,"app_principal")!=allocations.size())throw new IOException("Workspace disabled identity inventory proof failed");
        for(var entry:allocations.entrySet()) {
            checkpoint.check();var expected=entry.getValue();try(var statement=statement(connection,"select m.target_principal,m.target_scope,m.disposition,p.scope_key,p.enabled from restore_identity_mapping m join app_principal p on p.principal_id=m.target_principal where m.source_principal=?")) {
                statement.setString(1,entry.getKey().value().toString());try(var rows=statement.executeQuery()){if(!rows.next()||!expected.targetPrincipal().value().toString().equals(rows.getString(1))||!expected.targetScope().equals(rows.getString(2))||!expected.disposition().name().equals(rows.getString(3))||!expected.targetScope().equals(rows.getString(4))||rows.getInt(5)!=0||rows.next())throw new IOException("Workspace disabled identity mapping proof failed");}
            }
        }
    }
    private static void verify(Connection connection,Model model,BackupCheckpoint checkpoint)throws SQLException,IOException {
        var counts=new HashMap<String,Long>();for(var row:model.targets.values())counts.merge(row.table(),1L,Long::sum);
        for(var table:TABLES){checkpoint.check();if(count(connection,table)!=counts.getOrDefault(table,0L))throw new IOException("Workspace target inventory proof failed");}
        if(count(connection,"restore_workspace_mapping")!=model.targets.size()||count(connection,"restore_workspace_evidence")!=model.evidence.size())throw new IOException("Workspace private evidence inventory proof failed");
        for(var row:model.targets.values()) {
            checkpoint.check();try(var statement=statement(connection,"select "+String.join(",",row.columns().keySet())+" from "+row.table()+" where "+row.keyColumn()+"=?")) {
                statement.setObject(1,row.key());try(var rows=statement.executeQuery()) {if(!rows.next())throw proof();int n=1;for(var expected:row.columns().values()){checkpoint.check();field(rows,n++,expected,checkpoint);}if(rows.next())throw proof();}
            }
            try(var statement=statement(connection,"select target_table,target_key,target_sha256 from restore_workspace_mapping where record_kind=? and record_key=?")) {statement.setString(1,row.kind());statement.setString(2,row.sourceKey());try(var rows=statement.executeQuery()){if(!rows.next()||!row.table().equals(rows.getString(1))||!row.key().toString().equals(rows.getString(2))||!hash(row.columns()).equals(rows.getString(3))||rows.next())throw proof();}}
        }
        for(var expected:model.evidence.values()) {
            checkpoint.check();try(var statement=statement(connection,"select record_json,sha256,classification from restore_workspace_evidence where record_kind=? and record_key=?")) {statement.setString(1,expected.kind());statement.setString(2,expected.key());try(var rows=statement.executeQuery()) {if(!rows.next())throw proof();try(var reader=rows.getCharacterStream(1)){same(reader,expected.json(),checkpoint);}if(!StableIdentityHash.sha256(expected.json()).equals(rows.getString(2))||!expected.classification().equals(rows.getString(3))||rows.next())throw proof();}}
        }
    }
    private static void field(ResultSet rows,int column,Object expected,BackupCheckpoint checkpoint)throws SQLException,IOException {
        if(expected instanceof String text){try(var reader=rows.getCharacterStream(column)){same(reader,text,checkpoint);}return;}
        Object actual=expected instanceof Timestamp?rows.getTimestamp(column):rows.getObject(column);
        if(expected instanceof Number number){if(!(actual instanceof Number value)||number.longValue()!=value.longValue())throw proof();}
        else if(!Objects.equals(expected,actual))throw proof();
    }
    private static void same(Reader reader,String expected,BackupCheckpoint checkpoint)throws IOException {
        if(reader==null)throw proof();char[] buffer=new char[4096];int offset=0;
        while(offset<expected.length()){checkpoint.check();int count=reader.read(buffer,0,Math.min(buffer.length,expected.length()-offset));checkpoint.check();if(count<=0)throw proof();for(int n=0;n<count;n++)if(buffer[n]!=expected.charAt(offset+n))throw proof();offset+=count;}
        checkpoint.check();if(reader.read()!=-1)throw proof();checkpoint.check();
    }
    private static IOException proof(){return new IOException("Workspace target or private evidence proof failed");}
    private static final class Rollback implements AutoCloseable {final Connection connection;boolean committed;Rollback(Connection connection){this.connection=connection;}public void close()throws SQLException{if(!committed)connection.rollback();}}
}
