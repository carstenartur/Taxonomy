package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.workspace.backup.WorkspaceBackupContributor.*;
import com.taxonomy.workspace.backup.WorkspaceGitHistoryReferences.*;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.identity.StableIdentityHash;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Unit-level owner closure; these fixtures do not claim that Git objects have been imported. */
class WorkspaceRestoreReferencesTest {
    private static final String COMMIT="a".repeat(40), OTHER="b".repeat(40), RAW="Raw saved work\nλ\n";
    private final BackupRepositoryKey central=new BackupRepositoryKey("source-repository",null), workspace=new BackupRepositoryKey("source-repository","source-workspace");
    private final String editor=RepositoryContext.workspace(workspace.repositoryId(),workspace.workspaceId(),"main","source-owner").repositoryWorkspaceScopeKey();
    private final String interrupted="00000000-0000-0000-0000-000000000019";

    @Test void allFourteenTypedFieldsRequireExactSourceRecordValueAndAllowedRepository()throws Exception {
        var rows=rows();var references=references();var resolved=WorkspaceRestoreReferences.resolve(rows,references,working(),Set.of(central,workspace),allocations(),proof(),()->{});
        assertThat(resolved).hasSize(14);assertThat(resolved.values()).containsOnly(COMMIT);
        assertThat(resolved).containsEntry(new WorkspaceRestoreReferences.Slot(new SourceRecordId("workspace.editor",editor),Field.EDITOR_CHECKPOINT),COMMIT);
    }
    @Test void wrongKindValueRepositoryMissingDuplicateAndSurplusReferenceFailClosed() {
        var good=references();var first=good.getFirst();
        var invalid=new ArrayList<List<CommitReference>>();
        var kind=new ArrayList<>(good);kind.set(0,new CommitReference(new SourceRecordId("workspace.workspace",first.sourceId().value()),first.field(),first.repository(),first.commit()));invalid.add(kind);
        var value=new ArrayList<>(good);value.set(0,new CommitReference(first.sourceId(),first.field(),first.repository(),OTHER));invalid.add(value);
        var scope=new ArrayList<>(good);scope.set(0,new CommitReference(first.sourceId(),first.field(),workspace,first.commit()));invalid.add(scope);
        var missing=new ArrayList<>(good);missing.removeFirst();invalid.add(missing);
        var duplicate=new ArrayList<>(good);duplicate.add(first);invalid.add(duplicate);
        var surplus=new ArrayList<>(good);surplus.add(new CommitReference(new SourceRecordId("workspace.repository","foreign-repository"),Field.REPOSITORY_LAST_FETCH,central,COMMIT));invalid.add(surplus);
        for(var references:invalid)assertThatThrownBy(()->WorkspaceRestoreReferences.resolve(rows(),references,working(),Set.of(central,workspace),allocations(),proof(),()->{})).isInstanceOf(IOException.class).hasMessageContaining("reference");
    }
    @Test void rawSavedStateAndInterruptedMarkerNeedIndependentExactWorkingCopyProof() {
        var valid=working().getFirst();
        for(var wrong:List.of(
                new WorkingCopyProof(valid.sourceId(),valid.repository(),valid.branch(),valid.state(),"b".repeat(64),valid.interruptedCheckpoint()),
                new WorkingCopyProof(valid.sourceId(),valid.repository(),valid.branch(),valid.state(),valid.sha256(),null),
                new WorkingCopyProof(valid.sourceId(),valid.repository(),"other",valid.state(),valid.sha256(),valid.interruptedCheckpoint())))
            assertThatThrownBy(()->WorkspaceRestoreReferences.resolve(rows(),references(),List.of(wrong),Set.of(central,workspace),allocations(),proof(),()->{})).isInstanceOf(IOException.class).hasMessageContaining("working");
    }
    @Test void historyProofCannotSubstituteSyntheticCommitsOrChangeApprovedPhysicalAllocation() {
        var good=proof().repositories();var synthetic=new HashMap<>(good);var original=good.get(workspace);
        synthetic.put(workspace,new GitRestoreImporter.RepositoryProof(original.targetKey(),original.physicalStorageName(),
                new SnapshotContext.RepositoryState(Map.of("refs/heads/main",OTHER),"refs/heads/main",original.restoredState().workingStates(),Set.of(OTHER)),Map.of(COMMIT,OTHER),original.repositoryDigest()));
        assertThatThrownBy(()->WorkspaceRestoreReferences.resolve(rows(),references(),working(),Set.of(central,workspace),allocations(),new GitRestoreImporter.Result(synthetic),()->{})).isInstanceOf(IOException.class).hasMessageContaining("reference");
        var wrongStorage=new HashMap<>(good);wrongStorage.put(workspace,new GitRestoreImporter.RepositoryProof(original.targetKey(),"unapproved-storage",original.restoredState(),original.commitMappings(),original.repositoryDigest()));
        assertThatThrownBy(()->WorkspaceRestoreReferences.resolve(rows(),references(),working(),Set.of(central,workspace),allocations(),new GitRestoreImporter.Result(wrongStorage),()->{})).isInstanceOf(IOException.class).hasMessageContaining("reference");
    }
    @Test void sourceSavedStateDoesNotRequireEditorRowsToExistBeforeWorkspaceImport()throws Exception {
        var repositories=new HashMap<BackupRepositoryKey,GitRestoreImporter.RepositoryProof>();
        proof().repositories().forEach((key,old)->repositories.put(key,new GitRestoreImporter.RepositoryProof(old.targetKey(),old.physicalStorageName(),
                new SnapshotContext.RepositoryState(old.restoredState().refs(),old.restoredState().symbolicHead(),Map.of(),old.restoredState().requiredCommits()),old.commitMappings(),old.repositoryDigest())));
        assertThat(WorkspaceRestoreReferences.resolve(rows(),references(),working(),Set.of(central,workspace),allocations(),new GitRestoreImporter.Result(repositories),()->{})).hasSize(14);
    }
    private WorkspaceRestoreReferences.Rows rows() {
        var repository=new RepositoryRecord(central.repositoryId(),"source-slug","Source",null,RepositoryVisibility.PRIVATE,RepositoryLifecycleState.ACTIVE,RepositoryOwnerType.USER,"source-owner",RepositoryTopologyMode.INTERNAL_SHARED,"main",null,null,COMMIT,null,null,COMMIT,false,"historical-author","2026-10-10T00:00:00Z",null);
        var work=new WorkspaceRecord(workspace.workspaceId(),"source-owner","Private work","main","main",false,"2026-10-10T00:00:00Z",null,WorkspaceProvisioningStatus.READY,RepositoryTopologyMode.INTERNAL_SHARED,central.repositoryId(),"main",WorkspaceRelationshipType.WORKING_COPY,COMMIT,COMMIT,COMMIT,COMMIT,"main",null,null,false,false);
        var sync=new SyncRecord(new SourceRecordId("workspace.sync","1"),"source-owner",workspace.workspaceId(),COMMIT,null,COMMIT,null,"SYNCED",0,"2026-10-10T00:00:00Z",null);
        var state=new WorkingStateRecord(new SourceRecordId("workspace.editor",editor),central.repositoryId(),workspace.workspaceId(),"main",RAW,7,COMMIT,4,interrupted);
        var checkpoint=new CheckpointRecord(new SourceRecordId("workspace.checkpoint",editor+":"+interrupted),state.sourceId(),interrupted,"historical-author","2026-10-10T00:00:00Z","Historical rationale","c".repeat(64),0,4,COMMIT,"Checkpoint body",COMMIT,true,true,null,"USER");
        var index=new CommitIndexRecord(new SourceRecordId("workspace.commit-index","2"),central.repositoryId(),workspace.workspaceId(),"source-owner",COMMIT,"historical-author","2026-10-10T00:00:00Z","Original commit",null,null,null,null,"main","2026-10-10T00:00:00Z");
        var context=new ContextHistoryRecord(new SourceRecordId("workspace.context-history","3"),"source-owner",UUID.randomUUID().toString(),UUID.randomUUID().toString(),"main","main",COMMIT,COMMIT,"Historical navigation",null,"2026-10-10T00:00:00Z");
        return new WorkspaceRestoreReferences.Rows(List.of(repository),List.of(work),List.of(),List.of(sync),List.of(state),List.of(),List.of(checkpoint),List.of(index),List.of(context));
    }
    private List<CommitReference> references() {
        var rows=rows();var result=new ArrayList<CommitReference>();
        var repository=new SourceRecordId("workspace.repository",central.repositoryId());
        for(var field:List.of(Field.REPOSITORY_FORK_POINT,Field.REPOSITORY_LAST_FETCH))result.add(new CommitReference(repository,field,central,COMMIT));
        for(var field:List.of(Field.WORKSPACE_BASE,Field.WORKSPACE_CURRENT,Field.WORKSPACE_LAST_FETCHED,Field.WORKSPACE_LAST_INTEGRATED))result.add(new CommitReference(new SourceRecordId("workspace.workspace",workspace.workspaceId()),field,workspace,COMMIT));
        for(var field:List.of(Field.SYNC_LAST_SYNCED,Field.SYNC_LAST_PUBLISHED))result.add(new CommitReference(rows.synchronization().getFirst().sourceId(),field,workspace,COMMIT));
        result.add(new CommitReference(rows.workingStates().getFirst().sourceId(),Field.EDITOR_CHECKPOINT,workspace,COMMIT));
        for(var field:List.of(Field.CHECKPOINT_EXPECTED,Field.CHECKPOINT_COMMIT))result.add(new CommitReference(rows.checkpoints().getFirst().sourceId(),field,workspace,COMMIT));
        result.add(new CommitReference(rows.commitIndex().getFirst().sourceId(),Field.INDEX_COMMIT,workspace,COMMIT));
        for(var field:List.of(Field.CONTEXT_FROM,Field.CONTEXT_TO))result.add(new CommitReference(rows.contextHistory().getFirst().sourceId(),field,central,COMMIT));
        return result;
    }
    private List<WorkingCopyProof> working(){return List.of(new WorkingCopyProof(new SourceRecordId("workspace.editor",editor),workspace,"main",new SnapshotContext.WorkingState(7,COMMIT,4),StableIdentityHash.sha256(RAW),interrupted));}
    private Map<BackupRepositoryKey,RestoreRepositoryAllocation> allocations(){return Map.of(central,new RestoreRepositoryAllocation(new BackupRepositoryKey("target-repository",null),"target-central-storage"),workspace,new RestoreRepositoryAllocation(new BackupRepositoryKey("target-repository","target-workspace"),com.taxonomy.workspace.storage.DslGitRepositoryFactory.workspaceRepositoryName("target-workspace")));}
    private GitRestoreImporter.Result proof() {
        var result=new HashMap<BackupRepositoryKey,GitRestoreImporter.RepositoryProof>();
        allocations().forEach((key,allocation)->result.put(key,new GitRestoreImporter.RepositoryProof(allocation.targetKey(),allocation.physicalStorageName(),new SnapshotContext.RepositoryState(Map.of("refs/heads/main",COMMIT,"HEAD",COMMIT),"refs/heads/main",key.workspaceId()==null?Map.of():Map.of("main",new SnapshotContext.WorkingState(7,COMMIT,4)),Set.of(COMMIT)),Map.of(COMMIT,COMMIT),"d".repeat(64))));
        return new GitRestoreImporter.Result(result);
    }
}
