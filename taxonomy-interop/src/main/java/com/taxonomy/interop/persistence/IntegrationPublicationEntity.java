package com.taxonomy.interop.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Owned exclusively by the scoped IntegrationStore transaction.
 */
@Entity
@Table(name = "interop_publication", indexes = { @Index(name = "ix_interop_publication_scope", columnList = "scope_id,connection_id") })
public class IntegrationPublicationEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    String id;

    @Column(name = "scope_id", nullable = false, length = 64)
    String scopeId;

    @Column(name = "connection_id", nullable = false, length = 36)
    String connectionId;

    @Column(name = "schema_version", nullable = false)
    int schemaVersion;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "request_json", nullable = false)
    String requestJson;

    @Column(name = "config_fingerprint", nullable = false, length = 64)
    String configFingerprint;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "preview_json")
    String previewJson;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "plan_json")
    String planJson;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "review_json")
    String reviewJson;

    @Column(name = "phase", nullable = false, length = 40)
    String phase;

    @Column(name = "predecessor_id", length = 36)
    String predecessorId;

    @Column(name = "common_checkpoint_id", length = 36)
    String commonCheckpointId;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "local_state_json")
    String localStateJson;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "bindings_json")
    String bindingsJson;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "completion_json")
    String completionJson;

    @Column(name = "failure_code", length = 64)
    String failureCode;

    @Column(name = "reserved_revision", nullable = false)
    long reservedRevision;

    @Column(name = "lease_owner", length = 36)
    String leaseOwner;

    @Column(name = "lease_until", length = 40)
    String leaseUntil;

    @Column(name = "lease_epoch", nullable = false)
    long leaseEpoch;

    @Column(name = "created_at", nullable = false, length = 40)
    String createdAt;

    @Column(name = "updated_at", nullable = false, length = 40)
    String updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    long rowVersion;

    protected IntegrationPublicationEntity() {
    }
}
