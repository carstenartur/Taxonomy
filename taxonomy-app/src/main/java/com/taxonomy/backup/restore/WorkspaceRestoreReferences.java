package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.backup.WorkspaceBackupContributor.*;
import com.taxonomy.workspace.backup.WorkspaceGitHistoryReferences.*;
import com.taxonomy.workspace.service.RepositoryContext;
import org.eclipse.jgit.lib.Repository;
import java.io.IOException;
import java.util.*;

/** History-only source-owned commit and raw saved-state closure. No routing or identity inference. */
final class WorkspaceRestoreReferences {
    record Rows(List<RepositoryRecord> repositories,List<WorkspaceRecord> workspaces,List<MembershipRecord> memberships,
                List<SyncRecord> synchronization,List<WorkingStateRecord> workingStates,List<OperationRecord> operations,
                List<CheckpointRecord> checkpoints,List<CommitIndexRecord> commitIndex,List<ContextHistoryRecord> contextHistory) {
        Rows {
            repositories=List.copyOf(repositories);workspaces=List.copyOf(workspaces);memberships=List.copyOf(memberships);
            synchronization=List.copyOf(synchronization);workingStates=List.copyOf(workingStates);operations=List.copyOf(operations);
            checkpoints=List.copyOf(checkpoints);commitIndex=List.copyOf(commitIndex);contextHistory=List.copyOf(contextHistory);
            long total=(long)repositories.size()+workspaces.size()+memberships.size()+synchronization.size()+workingStates.size()
                    +operations.size()+checkpoints.size()+commitIndex.size()+contextHistory.size();
            if(total>100_000)throw new IllegalArgumentException("Workspace owner row budget exceeded");
        }
    }
    record Slot(SourceRecordId sourceId,Field field) { }
    private record Expected(String commit,Set<BackupRepositoryKey> repositories) { }
    static Map<Slot,String> resolve(Rows rows,List<CommitReference> references,List<WorkingCopyProof> working,
            Set<BackupRepositoryKey> sourceKeys,Map<BackupRepositoryKey,RestoreRepositoryAllocation> allocations,
            GitRestoreImporter.Result imported,BackupCheckpoint checkpoint)throws IOException {
        sourceKeys=Set.copyOf(sourceKeys);
        checkpoint.check();if(references.size()>100_000||working.size()>BackupLimits.MAX_ITEMS
                ||!sourceKeys.equals(allocations.keySet())||!sourceKeys.equals(imported.repositories().keySet()))throw reference();
        var targetRepositories=new HashMap<String,String>();var sourceRepositories=new HashMap<String,String>();var storageNames=new HashSet<String>();
        for(var key:sourceKeys) {
            checkpoint.check();var allocation=allocations.get(key);var proof=imported.repositories().get(key);
            if(!allocation.targetKey().equals(proof.targetKey())||!allocation.physicalStorageName().equals(proof.physicalStorageName())
                    ||(key.workspaceId()==null)!=(allocation.targetKey().workspaceId()==null))throw reference();
            String previous=targetRepositories.putIfAbsent(key.repositoryId(),allocation.targetKey().repositoryId());
            String source=sourceRepositories.putIfAbsent(allocation.targetKey().repositoryId(),key.repositoryId());
            if(previous!=null&&!previous.equals(allocation.targetKey().repositoryId())||source!=null&&!source.equals(key.repositoryId())
                    ||!storageNames.add(allocation.physicalStorageName().toLowerCase(Locale.ROOT))
                    ||key.workspaceId()!=null&&!com.taxonomy.workspace.storage.DslGitRepositoryFactory.workspaceRepositoryName(allocation.targetKey().workspaceId()).equals(allocation.physicalStorageName()))throw reference();
        }
        var repositoryIds=new HashSet<String>();for(var key:sourceKeys)repositoryIds.add(key.repositoryId());
        var expected=new LinkedHashMap<Slot,Expected>();var repositories=new HashMap<String,RepositoryRecord>();
        for(var row:rows.repositories()) {
            checkpoint.check();if(row.repositoryId()==null||repositories.putIfAbsent(row.repositoryId(),row)!=null
                    ||!repositoryIds.contains(row.repositoryId()))throw reference();
            var allowed=new HashSet<BackupRepositoryKey>();allowed.add(new BackupRepositoryKey(row.repositoryId(),null));
            if(row.upstreamRepositoryId()!=null)allowed.add(new BackupRepositoryKey(row.upstreamRepositoryId(),null));allowed.retainAll(sourceKeys);
            var id=new SourceRecordId("workspace.repository",row.repositoryId());
            add(expected,id,Field.REPOSITORY_FORK_POINT,row.forkPointCommit(),allowed);
            add(expected,id,Field.REPOSITORY_LAST_FETCH,row.lastFetchCommit(),allowed);
        }
        var workspaces=new HashMap<String,WorkspaceRecord>();
        for(var row:rows.workspaces()) {
            checkpoint.check();var key=key(row.repositoryId(),row.workspaceId(),sourceKeys);if(!repositories.containsKey(row.repositoryId())||workspaces.putIfAbsent(row.workspaceId(),row)!=null)throw reference();
            var id=new SourceRecordId("workspace.workspace",row.workspaceId());var related=related(key,sourceKeys);
            add(expected,id,Field.WORKSPACE_BASE,row.baseCommit(),related);add(expected,id,Field.WORKSPACE_CURRENT,row.currentCommit(),Set.of(key));
            add(expected,id,Field.WORKSPACE_LAST_FETCHED,row.lastFetchedCommit(),related);add(expected,id,Field.WORKSPACE_LAST_INTEGRATED,row.lastIntegratedCommit(),related);
        }
        for(var row:rows.synchronization()) {
            checkpoint.check();id(row.sourceId(),"workspace.sync");var parent=workspaces.get(row.workspaceId());if(parent==null)throw reference();
            var related=related(key(parent.repositoryId(),parent.workspaceId(),sourceKeys),sourceKeys);
            add(expected,row.sourceId(),Field.SYNC_LAST_SYNCED,row.lastSyncedCommit(),related);add(expected,row.sourceId(),Field.SYNC_LAST_PUBLISHED,row.lastPublishedCommit(),related);
        }
        var editors=new HashMap<SourceRecordId,WorkingStateRecord>();
        for(var row:rows.workingStates()) {
            checkpoint.check();id(row.sourceId(),"workspace.editor");var key=key(row.repositoryId(),row.workspaceId(),sourceKeys);
            if(!workspaces.containsKey(row.workspaceId())||!workspaces.get(row.workspaceId()).repositoryId().equals(row.repositoryId())
                    ||row.branch()==null||!Repository.isValidRefName("refs/heads/"+row.branch())
                    ||!RepositoryContext.workspace(row.repositoryId(),row.workspaceId(),row.branch(),"restore-source-check").repositoryWorkspaceScopeKey().equals(row.sourceId().value())
                    ||editors.putIfAbsent(row.sourceId(),row)!=null)throw working();
            add(expected,row.sourceId(),Field.EDITOR_CHECKPOINT,row.checkpointCommit(),Set.of(key));
        }
        for(var row:rows.checkpoints()) {
            checkpoint.check();id(row.sourceId(),"workspace.checkpoint");id(row.editor(),"workspace.editor");var parent=editors.get(row.editor());if(parent==null)throw reference();
            var key=key(parent.repositoryId(),parent.workspaceId(),sourceKeys);
            add(expected,row.sourceId(),Field.CHECKPOINT_EXPECTED,row.expectedCommit(),Set.of(key));add(expected,row.sourceId(),Field.CHECKPOINT_COMMIT,row.commitId(),Set.of(key));
        }
        for(var row:rows.commitIndex()) {
            checkpoint.check();id(row.sourceId(),"workspace.commit-index");var key=key(row.repositoryId(),row.workspaceId(),sourceKeys);
            add(expected,row.sourceId(),Field.INDEX_COMMIT,row.commitId(),Set.of(key));
        }
        for(var row:rows.contextHistory()) {
            checkpoint.check();id(row.sourceId(),"workspace.context-history");
            // These UUIDs carry no repository contract. Only the capture's explicit keyed evidence can resolve them.
            add(expected,row.sourceId(),Field.CONTEXT_FROM,row.fromCommit(),sourceKeys);add(expected,row.sourceId(),Field.CONTEXT_TO,row.toCommit(),sourceKeys);
        }
        var result=new LinkedHashMap<Slot,String>();
        for(var reference:references) {
            checkpoint.check();var slot=new Slot(reference.sourceId(),reference.field());var source=expected.get(slot);
            if(source==null||!source.commit().equals(reference.commit())||!source.repositories().contains(reference.repository())||result.containsKey(slot))throw reference();
            var proof=imported.repositories().get(reference.repository());String target=proof.commitMappings().get(reference.commit());
            if(target==null||!target.equals(reference.commit())||!proof.restoredState().requiredCommits().contains(target))throw reference();
            result.put(slot,target);
        }
        if(!result.keySet().equals(expected.keySet()))throw reference();
        // Git proves committed objects only. Editor SQL state is imported and independently read back later.
        var matched=new HashSet<SourceRecordId>();
        for(var proof:working) {
            checkpoint.check();var row=editors.get(proof.sourceId());
            if(row==null||!matched.add(proof.sourceId())||!proof.repository().equals(key(row.repositoryId(),row.workspaceId(),sourceKeys))
                    ||!proof.branch().equals(row.branch())||!proof.state().equals(new SnapshotContext.WorkingState(row.semanticRevision(),row.checkpointCommit(),row.checkpointRevision()))
                    ||row.dsl()==null||!proof.sha256().equals(StableIdentityHash.sha256(row.dsl()))||!Objects.equals(proof.interruptedCheckpoint(),row.interruptedCheckpoint()))throw working();
        }
        if(!matched.equals(editors.keySet()))throw working();checkpoint.check();return Map.copyOf(result);
    }
    private static void add(Map<Slot,Expected> expected,SourceRecordId id,Field field,String commit,Set<BackupRepositoryKey> keys)throws IOException {
        if(commit==null||commit.isBlank())return;
        if(!commit.matches("[a-f0-9]{40}")||keys.isEmpty()||expected.size()>=100_000||expected.putIfAbsent(new Slot(id,field),new Expected(commit,Set.copyOf(keys)))!=null)throw reference();
    }
    private static void id(SourceRecordId id,String kind)throws IOException {if(id==null||!kind.equals(id.kind()))throw reference();}
    private static BackupRepositoryKey key(String repository,String workspace,Set<BackupRepositoryKey> keys)throws IOException {
        if(repository==null)throw reference();var key=new BackupRepositoryKey(repository,workspace);if(!keys.contains(key))throw reference();return key;
    }
    private static Set<BackupRepositoryKey> related(BackupRepositoryKey key,Set<BackupRepositoryKey> keys) {
        var related=new HashSet<BackupRepositoryKey>();related.add(key);related.add(new BackupRepositoryKey(key.repositoryId(),null));related.retainAll(keys);return Set.copyOf(related);
    }
    private static IOException reference(){return new IOException("Workspace history reference closure failed");}
    private static IOException working(){return new IOException("Workspace raw working-copy proof failed");}
}
