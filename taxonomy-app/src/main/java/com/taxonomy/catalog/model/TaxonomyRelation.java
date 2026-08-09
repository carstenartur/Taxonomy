package com.taxonomy.catalog.model;

import com.taxonomy.model.RelationType;
import com.taxonomy.search.RelationEmbeddingBinder;
import com.taxonomy.shared.model.FloatArrayConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Nationalized;
import org.hibernate.search.mapper.pojo.bridge.mapping.annotation.TypeBinderRef;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.IndexedEmbedded;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.TypeBinding;

@Entity
@Table(name = "taxonomy_relation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_taxonomy_relation_scope",
                columnNames = {
                        "repository_id",
                        "source_node_id",
                        "target_node_id",
                        "relation_type",
                        "workspace_scope_key"
                }),
        indexes = {
                @Index(name = "idx_rel_repository", columnList = "repository_id"),
                @Index(
                        name = "idx_rel_repository_workspace",
                        columnList = "repository_id, workspace_id"),
                @Index(name = "idx_rel_owner", columnList = "owner_username")
        })
@Indexed
@TypeBinding(binder = @TypeBinderRef(type = RelationEmbeddingBinder.class))
public class TaxonomyRelation {

    public static final String SHARED_SCOPE_KEY = "__shared__";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mandatory logical tenant key. */
    @Column(name = "repository_id", nullable = false, length = 255)
    @KeywordField
    private String repositoryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_node_id", nullable = false)
    @IndexedEmbedded(includePaths = {"code", "nameEn"})
    private TaxonomyNode sourceNode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_node_id", nullable = false)
    @IndexedEmbedded(includePaths = {"code", "nameEn"})
    private TaxonomyNode targetNode;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false)
    @KeywordField
    private RelationType relationType;

    @Column(name = "workspace_id")
    @KeywordField
    private String workspaceId;

    /** Non-null uniqueness key for both central and personal workspace rows. */
    @Column(name = "workspace_scope_key", nullable = false, length = 255)
    @KeywordField
    private String workspaceScopeKey = SHARED_SCOPE_KEY;

    @Column(name = "owner_username")
    @KeywordField
    private String ownerUsername;

    @Nationalized
    @Column(length = 2000)
    @FullTextField(analyzer = "english")
    private String description;

    @Nationalized
    private String provenance;

    private Integer weight;

    private boolean bidirectional = false;

    @Lob
    @Convert(converter = FloatArrayConverter.class)
    @Column(name = "semantic_embedding")
    private float[] semanticEmbedding;

    @GenericField
    @Column(name = "has_embedding")
    private boolean hasEmbedding;

    @PrePersist
    @PreUpdate
    void synchronizeTenantKeys() {
        repositoryId = requireText(repositoryId, "repositoryId");
        workspaceId = normalizeOptional(workspaceId);
        workspaceScopeKey = scopeKeyFor(workspaceId);
    }

    public static String scopeKeyFor(String workspaceId) {
        String normalized = normalizeOptional(workspaceId);
        return normalized == null ? SHARED_SCOPE_KEY : normalized;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = requireText(repositoryId, "repositoryId");
    }

    public TaxonomyNode getSourceNode() { return sourceNode; }
    public void setSourceNode(TaxonomyNode sourceNode) { this.sourceNode = sourceNode; }

    public TaxonomyNode getTargetNode() { return targetNode; }
    public void setTargetNode(TaxonomyNode targetNode) { this.targetNode = targetNode; }

    public RelationType getRelationType() { return relationType; }
    public void setRelationType(RelationType relationType) { this.relationType = relationType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getProvenance() { return provenance; }
    public void setProvenance(String provenance) { this.provenance = provenance; }

    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }

    public boolean isBidirectional() { return bidirectional; }
    public void setBidirectional(boolean bidirectional) { this.bidirectional = bidirectional; }

    public float[] getSemanticEmbedding() { return semanticEmbedding; }
    public void setSemanticEmbedding(float[] semanticEmbedding) {
        this.semanticEmbedding = semanticEmbedding;
        this.hasEmbedding = semanticEmbedding != null;
    }

    public boolean isHasEmbedding() { return hasEmbedding; }
    private void setHasEmbedding(boolean hasEmbedding) { this.hasEmbedding = hasEmbedding; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = normalizeOptional(workspaceId);
        this.workspaceScopeKey = scopeKeyFor(this.workspaceId);
    }

    public String getWorkspaceScopeKey() { return workspaceScopeKey; }

    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = normalizeOptional(ownerUsername);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
