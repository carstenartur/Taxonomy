package com.taxonomy.interop.inventory;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity
@Table(name="inventory_review",indexes=@Index(name="ix_inventory_review_scope",columnList="scope_key"))
public class InventoryReviewEntity {
@Id @Column(name="id",length=200) String id;
@Column(name="repository_id",nullable=false,length=320) String repositoryId;
@Column(name="scope_key",nullable=false,length=1024) String scopeKey;
@Column(name="branch_name",nullable=false,length=400) String branch;
@Column(name="comparison_id",nullable=false,length=36) String comparisonId;
@Column(name="review_fingerprint",nullable=false,length=128) String reviewFingerprint;
@JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="review_json",nullable=false) String reviewJson;
protected InventoryReviewEntity() {}
}
