package com.taxonomy.workspace.model;

import com.taxonomy.identity.StableIdentityHash;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Desired protection for one branch in one physical architecture repository. */
@Entity
@Table(name = "repository_ref_rule")
public class RepositoryRefRule {
    @Id @Column(length = 64)
    private String id;
    @Column(name = "storage_name", nullable = false)
    private String storageName;
    @Column(name = "repository_id", nullable = false)
    private String repositoryId;
    @Column(name = "workspace_id")
    private String workspaceId;
    @Column(name = "branch_name", nullable = false)
    private String branch;
    @Column(nullable = false)
    private long revision;
    @Column(name = "prevent_updates", nullable = false)
    private boolean preventUpdates;
    @Column(name = "prevent_force", nullable = false)
    private boolean preventForce;
    @Column(name = "prevent_delete", nullable = false)
    private boolean preventDelete;

    protected RepositoryRefRule() { }

    public RepositoryRefRule(String storageName, String repositoryId, String workspaceId, String branch) {
        this.id = key(storageName, branch);
        this.storageName = storageName;
        this.repositoryId = repositoryId;
        this.workspaceId = workspaceId == null || workspaceId.isBlank() ? null : workspaceId;
        this.branch = branch;
    }

    public static String key(String storageName, String branch) {
        return StableIdentityHash.sha256(storageName.length() + ":" + storageName + branch.length() + ":" + branch);
    }

    public record View(String repositoryId, String workspaceId, String branch, long revision,
                       boolean preventUpdates, boolean preventForce, boolean preventDelete) { }

    public View view() {
        return new View(repositoryId, workspaceId, branch, revision, preventUpdates, preventForce, preventDelete);
    }

    public void change(boolean updates, boolean force, boolean deletion) {
        preventUpdates = updates;
        preventForce = force;
        preventDelete = deletion;
        revision++;
    }
}
