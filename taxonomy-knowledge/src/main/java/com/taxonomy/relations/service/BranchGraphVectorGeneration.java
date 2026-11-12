package com.taxonomy.relations.service;

import com.taxonomy.relations.model.RelationDecisionProjectionCheckpoint;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

/** The projection checkpoint already owns the collision-safe repository/workspace/branch identity. */
@Entity
@Table(name = "branch_graph_vector_generation")
class BranchGraphVectorGeneration {
    @Id @Column(name = "checkpoint_id") Long checkpointId;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checkpoint_id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    RelationDecisionProjectionCheckpoint checkpoint;
    @Column(name = "generation_id", nullable = false, length = 36) String generation;
    @Column(name = "started_at", nullable = false) java.time.Instant startedAt;
    @Column(name = "started_by", nullable = false, length = 255) String startedBy;
    @Column(name = "published_at") java.time.Instant publishedAt;
    @Column(name = "state", nullable = false, length = 16) String state;
    @Column(name = "source_commit", nullable = false, length = 40) String commit;
    @Column(name = "source_key", nullable = false, length = 64) String sourceKey;
    @Column(name = "catalogue_revision", nullable = false, length = 255) String catalogueRevision;
    @Column(name = "definition_key", nullable = false, length = 64) String definitionKey;
    @Column(name = "model_key", nullable = false, length = 64) String modelKey;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "model_identity", nullable = false) String modelIdentity;
    @Column(name = "text_format", nullable = false, length = 255) String textFormat;
    @Column(name = "index_format", nullable = false, length = 64) String indexFormat;
    @Column(name = "relations_key", nullable = false, length = 64) String relationsKey;
    @Column(name = "node_count", nullable = false) int nodes;
    @Column(name = "relation_count", nullable = false) int relations;
    @Column(name = "index_sha256", length = 64) String indexSha256;
    @JdbcTypeCode(SqlTypes.LONG32VARBINARY) @Column(name = "index_bytes") byte[] bytes;
    protected BranchGraphVectorGeneration() { }
}
