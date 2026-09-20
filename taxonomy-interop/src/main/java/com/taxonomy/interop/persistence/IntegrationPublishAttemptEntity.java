package com.taxonomy.interop.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Owned exclusively by the scoped IntegrationStore transaction.
 */
@Entity
@Table(name = "interop_publish_attempt", indexes = { @Index(name = "ix_interop_publish_attempt_scope", columnList = "scope_id,connection_id") })
public class IntegrationPublishAttemptEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    String id;

    @Column(name = "scope_id", nullable = false, length = 64)
    String scopeId;

    @Column(name = "connection_id", nullable = false, length = 36)
    String connectionId;

    @Column(name = "operation_id", nullable = false, length = 36)
    String operationId;

    @Column(name = "item_id", nullable = false, length = 64)
    String itemId;

    @Column(name = "lease_epoch", nullable = false)
    long leaseEpoch;

    @Column(name = "kind", nullable = false, length = 16)
    String kind;

    @Column(name = "started_at", nullable = false, length = 40)
    String startedAt;

    @Column(name = "ended_at", length = 40)
    String endedAt;

    @Column(name = "outcome", length = 64)
    String outcome;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "receipt_json")
    String receiptJson;

    protected IntegrationPublishAttemptEntity() {
    }
}
