package com.taxonomy.interop.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Mutations are owned by IntegrationStore under an exact connection/tenant lock. */
@Entity
@Table(name = "interop_event", indexes = {@Index(name="ix_interop_event_operation", columnList="scope_id,operation_id,occurred_at")})
@org.hibernate.annotations.Immutable
public class IntegrationEventEntity {
    @Id @Column(name="id", length=36) String id;
    @Column(name="scope_id", nullable=false, length=64) String scopeId;
    @Column(name="connection_id", nullable=false, length=36) String connectionId;
    @Column(name="operation_id", nullable=false, length=36) String operationId;
    @Column(name="event_type", nullable=false, length=40) String eventType;
    @Column(name="actor", nullable=false, length=160) String actor;
    @Column(name="occurred_at", nullable=false, length=40) String occurredAt;
    @Column(name="rationale", length=1000) String rationale;
    @Column(name="failure_code", length=64) String failureCode;
    protected IntegrationEventEntity() {}
}
