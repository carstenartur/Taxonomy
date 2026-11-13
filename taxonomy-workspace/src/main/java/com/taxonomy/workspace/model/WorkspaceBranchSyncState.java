package com.taxonomy.workspace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** Branch-owned progress. Older rows in sync_state retain their original, unproven branch. */
@Entity
@Table(name = "workspace_branch_sync")
public class WorkspaceBranchSyncState extends WorkspaceSyncProgress {
    @Id
    @Column(name = "scope_key", length = 64)
    private String scopeKey;
    @Column(nullable = false)
    private String username;
    @Column(name = "repository_id", nullable = false)
    private String repositoryId;
    @Column(name = "workspace_id")
    private String workspaceId;

    // A physical foreign key also fences a delayed first writer after its private working copy was removed.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", referencedColumnName = "workspace_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_ws_branch_sync_workspace"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserWorkspace workspace;

    @Column(name = "sync_branch", nullable = false)
    private String branch;

    protected WorkspaceBranchSyncState() { }

    public WorkspaceBranchSyncState(WorkspaceSyncScope scope) {
        scopeKey = scope.key();
        username = scope.username();
        repositoryId = scope.repositoryId();
        workspaceId = scope.workspaceId();
        branch = scope.branch();
    }

    public SyncState view() {
        var view = new SyncState();
        view.setUsername(username);
        view.setWorkspaceId(workspaceId);
        view.setObservationScope(new WorkspaceSyncScope(username, repositoryId, workspaceId, branch));
        copyProgressTo(view);
        return view;
    }
}
