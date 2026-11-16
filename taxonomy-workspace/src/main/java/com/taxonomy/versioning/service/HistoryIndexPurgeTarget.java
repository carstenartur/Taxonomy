package com.taxonomy.versioning.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;

/** The immutable identity of one projected row in an explicitly requested purge. */
@Entity
@IdClass(HistoryIndexPurgeTarget.Key.class)
@Table(name = "history_index_purge_target")
public class HistoryIndexPurgeTarget {
    public record Key(String operationId, Long projectionId) implements Serializable {}

    @Id @Column(name = "operation_id", length = 64, nullable = false)
    String operationId;

    @Id @Column(name = "projection_id", nullable = false)
    Long projectionId;
}
