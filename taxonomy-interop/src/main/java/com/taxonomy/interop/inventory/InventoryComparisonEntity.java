package com.taxonomy.interop.inventory;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity
@Table(name="inventory_comparison",indexes=@Index(name="ix_inventory_comparison_scope",columnList="scope_key"),uniqueConstraints=@UniqueConstraint(name="uq_inventory_scope_guard",columnNames="scope_guard_key"))
public class InventoryComparisonEntity {
@Id @Column(name="id",length=200) String id;
@Version @Column(name="row_version",nullable=false) @org.hibernate.annotations.ColumnDefault("0") long rowVersion;
@Column(name="scope_guard_key",nullable=false,length=240) String scopeGuardKey;
@Column(name="repository_id",nullable=false,length=320) String repositoryId;
@Column(name="scope_key",nullable=false,length=1024) String scopeKey;
@Column(name="branch_name",nullable=false,length=400) String branch;
@Column(name="project_id",nullable=false) Long projectId;
@Column(name="snapshot_id",nullable=false,length=100) String snapshotId;
@Column(name="actor",nullable=false,length=160) String actor;
@Column(name="fingerprint",nullable=false,length=128) String fingerprint;
@JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="comparison_json",nullable=false) String comparisonJson;
protected InventoryComparisonEntity() {}
}
