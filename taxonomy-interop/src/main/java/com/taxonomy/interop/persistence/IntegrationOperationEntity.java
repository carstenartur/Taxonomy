package com.taxonomy.interop.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Mutations are owned by IntegrationStore under an exact connection/tenant lock. */
@Entity
@Table(name = "interop_operation", indexes = {@Index(name="ix_interop_operation_scope", columnList="scope_id,connection_id,created_at")})
public class IntegrationOperationEntity {
    @Id @Column(name="id", length=36) String id;
    @Column(name="scope_id", nullable=false, length=64) String scopeId;
    @Column(name="connection_id", nullable=false, length=36) String connectionId;
    @Column(name="actor", nullable=false, length=160) String actor;
    @Column(name="status", nullable=false, length=32) String status;
    @Column(name="direction", nullable=false, length=16) String direction;
    @Column(name="fingerprint", nullable=false, length=64) String fingerprint;
    @Column(name="review_fingerprint", length=64) String reviewFingerprint;
    @Column(name="connection_revision", nullable=false) long connectionRevision;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="context_json", nullable=false) String contextJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="document_json", nullable=false) String documentJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="changes_json", nullable=false) String changesJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="review_json") String reviewJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="result_json") String resultJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="result_state_json") String resultStateJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="result_file_json") String resultFileJson;
    @Column(name="result_commit", length=40) String resultCommit;
    @Column(name="result_revision") Long resultRevision;
    @Column(name="failure_code", length=64) String failureCode;
    @Column(name="created_at", nullable=false, length=40) String createdAt;
    @Column(name="updated_at", nullable=false, length=40) String updatedAt;
    @Version @Column(name="row_version", nullable=false) long rowVersion;
    protected IntegrationOperationEntity() {}
}
