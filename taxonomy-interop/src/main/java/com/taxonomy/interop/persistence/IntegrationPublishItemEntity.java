package com.taxonomy.interop.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Owned exclusively by the scoped IntegrationStore transaction.
 */
@Entity
@Table(name = "interop_publish_item", indexes = { @Index(name = "ix_interop_publish_item_scope", columnList = "scope_id,connection_id") }, uniqueConstraints = { @UniqueConstraint(name = "uq_publish_item_key", columnNames = { "operation_id", "item_key" }), @UniqueConstraint(name = "uq_publish_item_ordinal", columnNames = { "operation_id", "item_ordinal" }), @UniqueConstraint(name = "uq_publish_item_idempotency", columnNames = { "connection_id", "idempotency_key" }) })
public class IntegrationPublishItemEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    String id;

    @Column(name = "scope_id", nullable = false, length = 64)
    String scopeId;

    @Column(name = "connection_id", nullable = false, length = 36)
    String connectionId;

    @Column(name = "operation_id", nullable = false, length = 36)
    String operationId;

    @Column(name = "item_key", nullable = false, length = 64)
    String itemKey;

    @Column(name = "item_ordinal", nullable = false)
    int ordinal;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    String idempotencyKey;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "intent_json", nullable = false)
    String intentJson;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "request_json")
    String requestJson;

    @Column(name = "request_fingerprint", length = 64)
    String requestFingerprint;

    @Column(name = "state", nullable = false, length = 32)
    String state;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "receipt_json")
    String receiptJson;

    @Column(name = "attempt_count", nullable = false)
    int attemptCount;

    @Column(name = "resubmit_allowed", nullable = false)
    boolean resubmitAllowed;

    @Column(name = "failure_code", length = 64)
    String failureCode;

    @Version
    @Column(name = "row_version", nullable = false)
    long rowVersion;

    protected IntegrationPublishItemEntity() {
    }
}
