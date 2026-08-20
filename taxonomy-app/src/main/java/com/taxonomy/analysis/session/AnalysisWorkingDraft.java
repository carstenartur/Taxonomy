package com.taxonomy.analysis.session;

import com.taxonomy.workspace.model.RepositoryTenantIdentity;
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
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Mutable, optimistic-lock protected working copy for the ad-hoc analysis page.
 *
 * <p>This entity deliberately differs from immutable portfolio requirement
 * versions and analysis snapshots. It preserves unfinished browser work across
 * reloads and devices until the user either discards it or promotes it into a
 * project requirement.</p>
 */
@Entity
@Table(name = "analysis_working_draft", indexes = {
        @Index(name = "idx_analysis_draft_workspace_user",
                columnList = "workspace_id,username"),
        @Index(name = "idx_analysis_draft_scope", columnList = "scope_key")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_analysis_draft_scope_user",
                columnNames = {"scope_key", "username"})
})
public class AnalysisWorkingDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_key", nullable = false,
            length = RepositoryTenantIdentity.MAX_SCOPE_KEY_LENGTH)
    private String scopeKey;

    @Column(name = "workspace_id", nullable = false, length = 320)
    private String workspaceId;

    @Column(nullable = false, length = 160)
    private String username;

    /**
     * Materialized long text rather than a JDBC CLOB/OID locator.
     * Frequent autosaves must replace one row value without accumulating
     * PostgreSQL large objects that require separate lifecycle management.
     */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected AnalysisWorkingDraft() {
    }

    public AnalysisWorkingDraft(String scopeKey,
                                String workspaceId,
                                String username,
                                String payloadJson,
                                Instant now) {
        this.scopeKey = scopeKey;
        this.workspaceId = workspaceId;
        this.username = username;
        this.payloadJson = payloadJson;
        this.createdAt = now;
        this.updatedAt = now;
        validate();
    }

    public void replacePayload(String payloadJson, Instant now) {
        this.payloadJson = payloadJson;
        this.updatedAt = now;
        validate();
    }

    @PrePersist
    @PreUpdate
    private void validate() {
        requireText(scopeKey, "scopeKey");
        requireText(workspaceId, "workspaceId");
        requireText(username, "username");
        requireText(payloadJson, "payloadJson");
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Draft timestamps must not be null");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }

    public Long getId() {
        return id;
    }

    public String getScopeKey() {
        return scopeKey;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getUsername() {
        return username;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getRowVersion() {
        return rowVersion;
    }
}
