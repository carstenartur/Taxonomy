package com.taxonomy.interop.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Mutations are owned by IntegrationStore under an exact connection/tenant lock. */
@Entity
@Table(name = "interop_checkpoint", indexes = {@Index(name="ix_interop_checkpoint_scope", columnList="scope_id,connection_id")})
@org.hibernate.annotations.Immutable
public class IntegrationCheckpointEntity {
    @Id @Column(name="id", length=36) String id;
    @Column(name="scope_id", nullable=false, length=64) String scopeId;
    @Column(name="connection_id", nullable=false, length=36) String connectionId;
    @Column(name="operation_id", nullable=false, length=36) String operationId;
    @Column(name="git_commit", length=40) String gitCommit;
    @Column(name="external_version", length=2048) String externalVersion;
    @Column(name="fingerprint", nullable=false, length=64) String fingerprint;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="context_json", nullable=false) String contextJson;
    @Column(name="created_at", nullable=false, length=40) String createdAt;
    protected IntegrationCheckpointEntity() {}
}
