package com.taxonomy.interop.inventory;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity
@Table(name="inventory_assertion",indexes=@Index(name="ix_inventory_assertion_scope",columnList="scope_key"))
public class InventoryAssertionEntity {
@Id @Column(name="id",length=200) String id;
@Column(name="repository_id",nullable=false,length=320) String repositoryId;
@Column(name="scope_key",nullable=false,length=1024) String scopeKey;
@Column(name="branch_name",nullable=false,length=400) String branch;
@Column(name="candidate_id",nullable=false,length=3000) String candidateId;
@JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="assertion_json",nullable=false) String assertionJson;
protected InventoryAssertionEntity() {}
}
