package com.taxonomy.interop.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Mutations are owned by IntegrationStore under an exact connection/tenant lock. */
@Entity
@Table(name = "interop_connection", indexes = {@Index(name="ix_interop_connection_scope", columnList="scope_id")})
public class IntegrationConnectionEntity {
    @Id @Column(name="id", length=36) String id;
    @Column(name="scope_id", nullable=false, length=64) String scopeId;
    @Column(name="repository_id", nullable=false, length=320) String repositoryId;
    @Column(name="organization_id", nullable=false, length=400) String organizationId;
    @Column(name="display_name", nullable=false, length=160) String displayName;
    @Column(name="connector_id", nullable=false, length=64) String connectorId;
    @Column(name="profile_version", nullable=false, length=32) String profileVersion;
    @Column(name="authority_mode", nullable=false, length=32) String authorityMode;
    @Column(name="remote_profile", length=100) String remoteProfile;
    @Column(name="project_id") Long projectId;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="external_scope", nullable=false) String externalScope;
    @Column(name="created_by", nullable=false, length=160) String createdBy;
    @Column(name="created_at", nullable=false, length=40) String createdAt;
    @Column(name="checkpoint_id", length=36) String checkpointId;
    @Column(name="active_operation_id", length=36) String activeOperationId;
    @Column(name="connection_revision", nullable=false) long revision;
    @Version @Column(name="row_version", nullable=false) long rowVersion;
    protected IntegrationConnectionEntity() {}
}
