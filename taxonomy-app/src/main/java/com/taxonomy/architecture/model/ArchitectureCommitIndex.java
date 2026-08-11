package com.taxonomy.architecture.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Nationalized;
import org.hibernate.search.engine.backend.types.Sortable;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;

import java.time.Instant;

/**
 * Repository-scoped index entry for one DSL commit on one branch.
 *
 * <p>The same Git commit may legitimately occur in independent central
 * repositories, personal workspaces and multiple branches. The relational and
 * Lucene identity is therefore tenant/branch-local rather than globally keyed by
 * {@code commitId}. Full-text queries must always filter {@code repositoryId}
 * and {@code workspaceScopeKey} before applying content predicates.</p>
 */
@Entity
@Indexed
@Table(name = "architecture_commit_index",
       indexes = {
           @Index(name = "idx_commit_index_repository", columnList = "repository_id"),
           @Index(name = "idx_commit_index_repository_workspace",
                   columnList = "repository_id, workspace_id"),
           @Index(name = "idx_commit_index_scope_branch",
                   columnList = "repository_id, workspace_scope_key, branch")
       },
       uniqueConstraints = @UniqueConstraint(
               name = "uq_commit_index_repository_workspace_branch_commit",
               columnNames = {
                       "repository_id",
                       "workspace_scope_key",
                       "branch",
                       "commit_id"
               }))
public class ArchitectureCommitIndex {

    public static final String CENTRAL_SCOPE_KEY = "__shared__";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_id", nullable = false, length = 255)
    @KeywordField
    private String repositoryId;

    @Column(name = "workspace_id", length = 255)
    @KeywordField
    private String workspaceId;

    /** Non-null key used for central/workspace uniqueness and search filters. */
    @Column(name = "workspace_scope_key", nullable = false, length = 255)
    @KeywordField
    private String workspaceScopeKey = CENTRAL_SCOPE_KEY;

    @Nationalized
    @Column(name = "commit_id", nullable = false, length = 40)
    @KeywordField
    private String commitId;

    @Nationalized
    @Column
    @KeywordField
    private String author;

    @Column(name = "commit_timestamp", nullable = false)
    @GenericField(sortable = Sortable.YES)
    private Instant commitTimestamp;

    @Nationalized
    @Column(length = 500)
    @FullTextField(analyzer = "english")
    private String message;

    @Nationalized
    @Column(name = "changed_files", length = 2000)
    private String changedFiles;

    @Nationalized
    @Column(name = "tokenized_change_text", length = 10000)
    @FullTextField(analyzer = "dsl")
    private String tokenizedChangeText;

    @Nationalized
    @Column(name = "affected_element_ids", length = 2000)
    @FullTextField(analyzer = "csv-keyword")
    private String affectedElementIds;

    @Nationalized
    @Column(name = "affected_relation_ids", length = 2000)
    @FullTextField(analyzer = "csv-keyword")
    private String affectedRelationIds;

    @Nationalized
    @Column(nullable = false, length = 255)
    @KeywordField
    private String branch;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;

    @PrePersist
    protected void onCreate() {
        if (indexedAt == null) {
            indexedAt = Instant.now();
        }
        synchronizeTenantKeys();
    }

    @PreUpdate
    protected void onUpdate() {
        synchronizeTenantKeys();
    }

    private void synchronizeTenantKeys() {
        repositoryId = requireText(repositoryId, "repositoryId");
        workspaceId = normalizeOptional(workspaceId);
        workspaceScopeKey = scopeKeyFor(workspaceId);
        commitId = requireText(commitId, "commitId");
        branch = requireText(branch, "branch");
        author = normalizeOptional(author);
    }

    public static String scopeKeyFor(String workspaceId) {
        String normalized = normalizeOptional(workspaceId);
        return normalized == null ? CENTRAL_SCOPE_KEY : normalized;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = requireText(repositoryId, "repositoryId");
    }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = normalizeOptional(workspaceId);
        this.workspaceScopeKey = scopeKeyFor(this.workspaceId);
    }

    public String getWorkspaceScopeKey() { return workspaceScopeKey; }

    public String getCommitId() { return commitId; }
    public void setCommitId(String commitId) {
        this.commitId = requireText(commitId, "commitId");
    }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = normalizeOptional(author); }

    public Instant getCommitTimestamp() { return commitTimestamp; }
    public void setCommitTimestamp(Instant commitTimestamp) {
        this.commitTimestamp = commitTimestamp;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getChangedFiles() { return changedFiles; }
    public void setChangedFiles(String changedFiles) { this.changedFiles = changedFiles; }

    public String getTokenizedChangeText() { return tokenizedChangeText; }
    public void setTokenizedChangeText(String tokenizedChangeText) {
        this.tokenizedChangeText = tokenizedChangeText;
    }

    public String getAffectedElementIds() { return affectedElementIds; }
    public void setAffectedElementIds(String affectedElementIds) {
        this.affectedElementIds = affectedElementIds;
    }

    public String getAffectedRelationIds() { return affectedRelationIds; }
    public void setAffectedRelationIds(String affectedRelationIds) {
        this.affectedRelationIds = affectedRelationIds;
    }

    public String getBranch() { return branch; }
    public void setBranch(String branch) {
        this.branch = requireText(branch, "branch");
    }

    public Instant getIndexedAt() { return indexedAt; }
    public void setIndexedAt(Instant indexedAt) { this.indexedAt = indexedAt; }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
