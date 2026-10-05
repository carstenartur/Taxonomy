package com.taxonomy.analysis.cluster;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable admitted root snapshot, loaded independently by its shard worker. */
@Entity
@Table(name = "analysis_cluster_input", indexes = @Index(name = "idx_analysis_cluster_input_run", columnList = "operation_id"))
public class ClusterAnalysisInput {
    @Id @Column(length = 64) String id;
    @Column(name = "operation_id", nullable = false, length = 128) String operationId;
    @Column(name = "root_code", nullable = false, length = 64) String root;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "input_json", nullable = false) String inputJson;
    protected ClusterAnalysisInput() { }
}
