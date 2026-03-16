package com.taxonomy.catalog.model;

import com.taxonomy.search.RelationEmbeddingBinder;
import jakarta.persistence.*;
import org.hibernate.annotations.Nationalized;
import org.hibernate.search.mapper.pojo.bridge.mapping.annotation.TypeBinderRef;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.*;
import com.taxonomy.model.RelationType;
import com.taxonomy.shared.model.FloatArrayConverter;

@Entity
@Table(name = "taxonomy_relation",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"source_node_id", "target_node_id", "relation_type"}))
@Indexed
@TypeBinding(binder = @TypeBinderRef(type = RelationEmbeddingBinder.class))
public class TaxonomyRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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
        this.hasEmbedding = (semanticEmbedding != null);
    }

    public boolean isHasEmbedding() { return hasEmbedding; }
    private void setHasEmbedding(boolean hasEmbedding) { this.hasEmbedding = hasEmbedding; }
}
