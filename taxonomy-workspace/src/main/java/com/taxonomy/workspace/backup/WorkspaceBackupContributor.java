package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PortableRows.Query;
import com.taxonomy.workspace.model.*;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import static com.taxonomy.exchange.backup.PortableRows.*;

/** Portable routing, ownership and durable editor schemas. No source storage names, credentials or live locks. */
public final class WorkspaceBackupContributor implements BackupDataContributor {
    private final PortableRows rows;
    private final BackupDocumentProjector projector;
    public WorkspaceBackupContributor(DataSource database, BackupDocumentProjector projector) {
        rows = new PortableRows(database); this.projector = Objects.requireNonNull(projector);
    }
    @Override public BackupComponentId componentId() { return new BackupComponentId("workspace"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() {
        return Set.of("com.taxonomy.workspace.model.SystemRepository", "com.taxonomy.workspace.model.UserWorkspace",
                "com.taxonomy.workspace.model.RepositoryMembership", "com.taxonomy.workspace.model.SyncState",
                "com.taxonomy.editor.persistence.EditorWorkspace", "com.taxonomy.editor.persistence.EditorOperation",
                "com.taxonomy.editor.persistence.EditorCheckpoint", "com.taxonomy.versioning.model.ArchitectureCommitIndex",
                "com.taxonomy.versioning.model.ContextHistoryRecord");
    }
    @Override public List<String> omissions(BackupProfile profile) {
        var omissions = new ArrayList<String>();
        if (!profile.includesHistory()) omissions.add("workspace: editor operations, checkpoint bodies, commit index and context history are excluded");
        if (profile == BackupProfile.SELECTED_VERSION) omissions.add("workspace: current unversioned working copies and routing metadata are excluded; selected Git state is authoritative");
        if (profile != BackupProfile.INSTALLATION_FULL) omissions.add("workspace: cross-context navigation history is outside the selected scope");
        omissions.add("workspace: physical storage names, runtime row versions, provisioning errors and remote credentials are target-owned");
        return List.copyOf(omissions);
    }
    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        var scope = new BackupRowScope(snapshot); var profile = snapshot.authorization().request().profile();
        var repositories = scope.repositoryIds("repository_id");
        write(sink, "repositories", profile, query(scope, false, "select repository_id,slug,display_name,description,visibility,lifecycle_state,owner_type,owner_id,topology_mode,default_branch,upstream_repository_id,upstream_branch,fork_point_commit,last_fetch_at,last_push_at,last_fetch_commit,primary_repo,created_by,created_at,updated_at from system_repository", repositories, "repository_id"),
                r -> new RepositoryRecord(text(r,"repository_id"),text(r,"slug"),text(r,"display_name"),text(r,"description"),
                        enumeration(r,"visibility",RepositoryVisibility.class),enumeration(r,"lifecycle_state",RepositoryLifecycleState.class),
                        enumeration(r,"owner_type",RepositoryOwnerType.class),text(r,"owner_id"),enumeration(r,"topology_mode",RepositoryTopologyMode.class),
                        text(r,"default_branch"),text(r,"upstream_repository_id"),text(r,"upstream_branch"),text(r,"fork_point_commit"),
                        instant(r,"last_fetch_at"),instant(r,"last_push_at"),text(r,"last_fetch_commit"),r.getBoolean("primary_repo"),
                        text(r,"created_by"),instant(r,"created_at"),instant(r,"updated_at")));
        var workspaces = scope.repositories("source_repository_id", "workspace_id");
        write(sink,"workspaces",profile,query(scope,false,"select workspace_id,username,display_name,current_branch,base_branch,shared,created_at,last_accessed_at,provisioning_status,topology_mode,source_repository_id,source_branch,relationship_type,base_commit,current_commit,last_fetched_commit,last_integrated_commit,sync_target_branch,provisioned_at,description,archived,is_default from user_workspace",workspaces,"workspace_id"),
                r -> new WorkspaceRecord(text(r,"workspace_id"),text(r,"username"),text(r,"display_name"),text(r,"current_branch"),text(r,"base_branch"),r.getBoolean("shared"),
                        instant(r,"created_at"),instant(r,"last_accessed_at"),enumeration(r,"provisioning_status",WorkspaceProvisioningStatus.class),
                        enumeration(r,"topology_mode",RepositoryTopologyMode.class),text(r,"source_repository_id"),text(r,"source_branch"),
                        enumeration(r,"relationship_type",WorkspaceRelationshipType.class),text(r,"base_commit"),text(r,"current_commit"),text(r,"last_fetched_commit"),
                        text(r,"last_integrated_commit"),text(r,"sync_target_branch"),instant(r,"provisioned_at"),text(r,"description"),r.getBoolean("archived"),r.getBoolean("is_default")));
        write(sink,"memberships",profile,query(scope,false,"select id,repository_id,username,repository_role,created_at,created_by,updated_at from repository_membership",repositories,"id"),
                r -> new MembershipRecord(reference(r,"workspace.membership","id"),text(r,"repository_id"),text(r,"username"),enumeration(r,"repository_role",RepositoryRole.class),instant(r,"created_at"),text(r,"created_by"),instant(r,"updated_at")));
        var sync = scope.repositories("w.source_repository_id","w.workspace_id");
        write(sink,"synchronization",profile,query(scope,false,"select s.id,s.username,s.workspace_id,s.last_synced_commit_id,s.last_sync_timestamp,s.last_published_commit_id,s.last_publish_timestamp,s.sync_status,s.unpublished_commit_count,s.created_at,s.updated_at from sync_state s join user_workspace w on w.workspace_id=s.workspace_id",sync,"s.id"),
                r -> new SyncRecord(reference(r,"workspace.sync","id"),text(r,"username"),text(r,"workspace_id"),text(r,"last_synced_commit_id"),instant(r,"last_sync_timestamp"),
                        text(r,"last_published_commit_id"),instant(r,"last_publish_timestamp"),text(r,"sync_status"),r.getInt("unpublished_commit_count"),instant(r,"created_at"),instant(r,"updated_at")));
        var editors = scope.repositories("repository_id","workspace_id");
        write(sink,"working-states",profile,query(scope,false,"select scope_id,repository_id,workspace_id,branch,dsl,semantic_revision,checkpoint_commit,checkpoint_revision,pending_checkpoint from editor_workspace",editors,"scope_id"),
                r -> new WorkingStateRecord(reference(r,"workspace.editor","scope_id"),text(r,"repository_id"),text(r,"workspace_id"),text(r,"branch"),document(scope,unframe(text(r,"dsl"))),r.getLong("semantic_revision"),text(r,"checkpoint_commit"),r.getLong("checkpoint_revision"),text(r,"pending_checkpoint")));
        var editorHistory = scope.repositories("w.repository_id","w.workspace_id");
        write(sink,"operations",profile,query(scope,true,"select o.id,o.scope_id,o.command_id,o.actor,o.occurred_at,o.rationale,o.correlation_id,o.causation_id,o.kind,o.target_operation_id,o.previous_revision,o.semantic_revision,o.fingerprint,o.body_version,o.before_dsl,o.after_dsl,o.affected_ids from editor_operation o join editor_workspace w on w.scope_id=o.scope_id",editorHistory,"o.id"),
                r -> new OperationRecord(reference(r,"workspace.operation","id"),reference(r,"workspace.editor","scope_id"),text(r,"command_id"),text(r,"actor"),text(r,"occurred_at"),text(r,"rationale"),text(r,"correlation_id"),text(r,"causation_id"),text(r,"kind"),text(r,"target_operation_id"),r.getLong("previous_revision"),r.getLong("semantic_revision"),text(r,"fingerprint"),r.getInt("body_version"),unframe(text(r,"before_dsl")),unframe(text(r,"after_dsl")),unframe(text(r,"affected_ids"))));
        write(sink,"checkpoints",profile,query(scope,true,"select c.id,c.scope_id,c.command_id,c.actor,c.occurred_at,c.rationale,c.fingerprint,c.from_revision,c.semantic_revision,c.expected_commit,c.dsl,c.commit_id,c.completed,c.commit_created,c.failure_code,c.origin from editor_checkpoint c join editor_workspace w on w.scope_id=c.scope_id",editorHistory,"c.id"),
                r -> new CheckpointRecord(reference(r,"workspace.checkpoint","id"),reference(r,"workspace.editor","scope_id"),text(r,"command_id"),text(r,"actor"),text(r,"occurred_at"),text(r,"rationale"),text(r,"fingerprint"),r.getLong("from_revision"),r.getLong("semantic_revision"),text(r,"expected_commit"),unframe(text(r,"dsl")),text(r,"commit_id"),r.getBoolean("completed"),r.getBoolean("commit_created"),text(r,"failure_code"),text(r,"origin")));
        write(sink,"commit-index",profile,query(scope,true,"select id,repository_id,workspace_id,workspace_scope_key,commit_id,author,commit_timestamp,message,changed_files,tokenized_change_text,affected_element_ids,affected_relation_ids,branch,indexed_at from architecture_commit_index",editors,"id"),
                r -> new CommitIndexRecord(reference(r,"workspace.commit-index","id"),text(r,"repository_id"),text(r,"workspace_id"),text(r,"workspace_scope_key"),text(r,"commit_id"),text(r,"author"),instant(r,"commit_timestamp"),text(r,"message"),text(r,"changed_files"),text(r,"tokenized_change_text"),text(r,"affected_element_ids"),text(r,"affected_relation_ids"),text(r,"branch"),instant(r,"indexed_at")));
        write(sink,"context-history",profile,profile == BackupProfile.INSTALLATION_FULL ? new Query("select id,username,from_context_id,to_context_id,from_branch,to_branch,from_commit_id,to_commit_id,reason,origin_context_id,created_at from context_history_record order by id",List.of()) : null,
                r -> new ContextHistoryRecord(reference(r,"workspace.context-history","id"),text(r,"username"),text(r,"from_context_id"),text(r,"to_context_id"),text(r,"from_branch"),text(r,"to_branch"),text(r,"from_commit_id"),text(r,"to_commit_id"),text(r,"reason"),text(r,"origin_context_id"),instant(r,"created_at")));
    }
    private <T extends Record> void write(ComponentSink sink,String kind,BackupProfile profile,Query query,PortableRows.Mapper<T> mapper) throws IOException { rows.write(sink,"workspace",kind,profile,query,mapper); }
    private String document(BackupRowScope scope,String dsl) { return scope.history() ? dsl : projector.currentState(dsl); }
    private static Query query(BackupRowScope scope,boolean history,String select,Query predicate,String order) {
        return scope.selectedVersion() || history && !scope.history() ? null : new Query(select + " where " + predicate.sql() + " order by " + order,predicate.parameters());
    }
    private static <E extends Enum<E>> E enumeration(ResultSet row,String column,Class<E> type) throws SQLException,IOException {
        String value=text(row,column); return value==null?null:Enum.valueOf(type,value);
    }
    public record RepositoryRecord(String repositoryId,String slug,String displayName,String description,RepositoryVisibility visibility,RepositoryLifecycleState lifecycleState,RepositoryOwnerType ownerType,String ownerScope,RepositoryTopologyMode topologyMode,String defaultBranch,String upstreamRepositoryId,String upstreamBranch,String forkPointCommit,String lastFetchAt,String lastPushAt,String lastFetchCommit,boolean primaryRepository,String createdBy,String createdAt,String updatedAt) { }
    public record WorkspaceRecord(String workspaceId,String ownerScope,String displayName,String currentBranch,String baseBranch,boolean shared,String createdAt,String lastAccessedAt,WorkspaceProvisioningStatus provisioningStatus,RepositoryTopologyMode topologyMode,String repositoryId,String sourceBranch,WorkspaceRelationshipType relationshipType,String baseCommit,String currentCommit,String lastFetchedCommit,String lastIntegratedCommit,String syncTargetBranch,String provisionedAt,String description,boolean archived,boolean defaultWorkspace) { }
    public record MembershipRecord(SourceRecordId sourceId,String repositoryId,String principalScope,RepositoryRole role,String createdAt,String createdBy,String updatedAt) { }
    public record SyncRecord(SourceRecordId sourceId,String principalScope,String workspaceId,String lastSyncedCommit,String lastSyncAt,String lastPublishedCommit,String lastPublishAt,String syncStatus,int unpublishedCommitCount,String createdAt,String updatedAt) { }
    public record WorkingStateRecord(SourceRecordId sourceId,String repositoryId,String workspaceId,String branch,String dsl,long semanticRevision,String checkpointCommit,long checkpointRevision,String interruptedCheckpoint) { }
    public record OperationRecord(SourceRecordId sourceId,SourceRecordId editor,String commandId,String actor,String occurredAt,String rationale,String correlationId,String causationId,String kind,String targetOperationId,long previousRevision,long semanticRevision,String fingerprint,int bodyVersion,String beforeDsl,String afterDsl,String affectedIds) { }
    public record CheckpointRecord(SourceRecordId sourceId,SourceRecordId editor,String commandId,String actor,String occurredAt,String rationale,String fingerprint,long fromRevision,long semanticRevision,String expectedCommit,String dsl,String commitId,boolean completed,boolean commitCreated,String failureCode,String origin) { }
    public record CommitIndexRecord(SourceRecordId sourceId,String repositoryId,String workspaceId,String workspaceScope,String commitId,String author,String committedAt,String message,String changedFiles,String changeText,String affectedElementIds,String affectedRelationIds,String branch,String indexedAt) { }
    public record ContextHistoryRecord(SourceRecordId sourceId,String principalScope,String fromContext,String toContext,String fromBranch,String toBranch,String fromCommit,String toCommit,String reason,String originContext,String createdAt) { }
}
