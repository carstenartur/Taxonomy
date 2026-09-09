package com.taxonomy.interop.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Mutations are owned by IntegrationStore under an exact connection/tenant lock. */
@Entity
@Table(name = "interop_identity", indexes = {@Index(name="ix_interop_identity_scope", columnList="scope_id,connection_id")})
public class ExternalIdentityMappingEntity {
    @Id @Column(name="id", length=64) String id;
    @Column(name="scope_id", nullable=false, length=64) String scopeId;
    @Column(name="connection_id", nullable=false, length=36) String connectionId;
    @Column(name="external_id", nullable=false, length=2048) String externalId;
    @Column(name="business_identity", nullable=false, length=256) String businessIdentity;
    @Column(name="requirement_id") Long requirementId;
    @Column(name="external_version", length=2048) String externalVersion;
    @Column(name="fingerprint", nullable=false, length=64) String fingerprint;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="external_json", nullable=false) String externalJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="internal_json", nullable=false) String internalJson;
    @Column(name="operation_id", nullable=false, length=36) String operationId;
    @Column(name="removed", nullable=false) boolean removed;
    @Version @Column(name="row_version", nullable=false) long rowVersion;
    protected ExternalIdentityMappingEntity() {}
}
