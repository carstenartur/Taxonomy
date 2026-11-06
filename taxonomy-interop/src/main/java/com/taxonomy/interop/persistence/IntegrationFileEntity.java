package com.taxonomy.interop.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable binary evidence; lifecycle and authorization remain owned by IntegrationStore. */
@Entity
@Table(name = "interop_file", indexes = {@Index(name="ix_interop_file_scope", columnList="scope_id,connection_id,actor,kind")})
public class IntegrationFileEntity {
    @Id @Column(name="id", length=36) String id;
    @Column(name="scope_id", nullable=false, length=64) String scopeId;
    @Column(name="connection_id", nullable=false, length=36) String connectionId;
    @Column(name="operation_id", nullable=false, length=36) String operationId;
    @Column(name="actor", nullable=false, length=160) String actor;
    @Column(name="kind", nullable=false, length=16) String kind;
    @Column(name="profile", nullable=false, length=64) String profile;
    @Column(name="profile_version", nullable=false, length=32) String profileVersion;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="binding_json", nullable=false) String bindingJson;
    @Column(name="sha256", nullable=false, length=64) String sha256;
    @Column(name="byte_count", nullable=false) long byteCount;
    @Column(name="media_type", nullable=false, length=200) String mediaType;
    @Column(name="filename", nullable=false, length=240) String filename;
    @Column(name="model_identifier", length=2048) String modelIdentifier;
    @Column(name="review_fingerprint", length=64) String reviewFingerprint;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="losses_json", nullable=false) String lossesJson;
    @JdbcTypeCode(SqlTypes.LONG32VARBINARY) @Column(name="content", nullable=false) byte[] content;
    @Column(name="created_at", nullable=false, length=40) String createdAt;
    protected IntegrationFileEntity() {}
}
