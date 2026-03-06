package com.nato.taxonomy.model;

import jakarta.persistence.*;

@Entity
@Table(name = "taxonomy_relation",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"source_node_id", "target_node_id", "relation_type"}))
public class TaxonomyRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_node_id", nullable = false)
    private TaxonomyNode sourceNode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_node_id", nullable = false)
    private TaxonomyNode targetNode;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false)
    private RelationType relationType;

    @Column(length = 2000)
    private String description;

    private String provenance;

    private Integer weight;

    private boolean bidirectional = false;

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
}
