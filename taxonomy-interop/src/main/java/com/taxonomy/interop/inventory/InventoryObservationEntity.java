package com.taxonomy.interop.inventory;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity
@Table(name="inventory_observation",indexes=@Index(name="ix_inventory_observation_scope",columnList="scope_key"))
public class InventoryObservationEntity {
@Id @Column(name="id",length=200) String id;
@Column(name="repository_id",nullable=false,length=320) String repositoryId;
@Column(name="scope_key",nullable=false,length=1024) String scopeKey;
@Column(name="branch_name",nullable=false,length=400) String branch;
@Column(name="profile_id",nullable=false,length=100) String profileId;
@Column(name="profile_revision",nullable=false,length=100) String profileRevision;
@Column(name="actor",nullable=false,length=160) String actor;
@Column(name="organization_id",nullable=false,length=400) String organization;
@Column(name="status",nullable=false,length=30) String status;
@Column(name="failure_code",length=80) String failureCode;
@Column(name="created_at",nullable=false,length=40) String createdAt;
@JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="observation_json") String observationJson;
protected InventoryObservationEntity() {}
}
