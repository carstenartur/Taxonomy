package com.taxonomy.model;

import com.taxonomy.search.NodeEmbeddingBinder;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Nationalized;
import org.hibernate.search.mapper.pojo.bridge.mapping.annotation.TypeBinderRef;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "taxonomy_node")
@Indexed
@TypeBinding(binder = @TypeBinderRef(type = NodeEmbeddingBinder.class))
public class TaxonomyNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Nationalized
    @Column(unique = true, nullable = false)
    @KeywordField
    private String code;

    @Nationalized
    @Column(nullable = true)
    @KeywordField
    private String uuid;

    @Nationalized
    @Column(nullable = false)
    @FullTextField(analyzer = "english")
    private String nameEn;

    @Nationalized
    @Column(nullable = true)
    @FullTextField(analyzer = "german")
    private String nameDe;

    @Nationalized
    @Lob
    @Column(length = 5000)
    @FullTextField(analyzer = "english")
    private String descriptionEn;

    @Nationalized
    @Lob
    @Column(length = 5000)
    @FullTextField(analyzer = "german")
    private String descriptionDe;

    @Nationalized
    @Column(name = "parent_code")
    @GenericField
    private String parentCode;

    @Nationalized
    @Column(name = "taxonomy_root")
    @KeywordField
    private String taxonomyRoot;

    @GenericField
    @Column(name = "node_level")
    private int level;

    @Nationalized
    private String dataset;

    @Nationalized
    @Column(name = "external_id")
    @KeywordField
    private String externalId;

    @Nationalized
    private String source;

    @Nationalized
    @Lob
    @Column(length = 5000)
    private String reference;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Nationalized
    private String state;

    @Lob
    @Convert(converter = FloatArrayConverter.class)
    @Column(name = "semantic_embedding")
    private float[] semanticEmbedding;

    @GenericField
    @Column(name = "has_embedding")
    private boolean hasEmbedding;

    // ── Derived graph metadata ───────────────────────────────────────

    @GenericField(sortable = org.hibernate.search.engine.backend.types.Sortable.YES)
    @Column(name = "incoming_relation_count")
    private int incomingRelationCount;

    @GenericField(sortable = org.hibernate.search.engine.backend.types.Sortable.YES)
    @Column(name = "outgoing_relation_count")
    private int outgoingRelationCount;

    @GenericField(sortable = org.hibernate.search.engine.backend.types.Sortable.YES)
    @Column(name = "requirement_coverage_count")
    private int requirementCoverageCount;

    @KeywordField
    @Column(name = "graph_role")
    private String graphRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private TaxonomyNode parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
    @OrderBy("nameEn ASC")
    @BatchSize(size = 50)
    private List<TaxonomyNode> children = new ArrayList<>();

    @OneToMany(mappedBy = "sourceNode", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)
    private List<TaxonomyRelation> outgoingRelations = new ArrayList<>();

    @OneToMany(mappedBy = "targetNode", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)
    private List<TaxonomyRelation> incomingRelations = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }

    public String getNameDe() { return nameDe; }
    public void setNameDe(String nameDe) { this.nameDe = nameDe; }

    /** Backward-compatible getter; returns the English name. */
    public String getName() { return nameEn; }

    public String getDescriptionEn() { return descriptionEn; }
    public void setDescriptionEn(String descriptionEn) { this.descriptionEn = descriptionEn; }

    public String getDescriptionDe() { return descriptionDe; }
    public void setDescriptionDe(String descriptionDe) { this.descriptionDe = descriptionDe; }

    /** Backward-compatible getter; returns the English description. */
    public String getDescription() { return descriptionEn; }

    public String getParentCode() { return parentCode; }
    public void setParentCode(String parentCode) { this.parentCode = parentCode; }

    public String getTaxonomyRoot() { return taxonomyRoot; }
    public void setTaxonomyRoot(String taxonomyRoot) { this.taxonomyRoot = taxonomyRoot; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public String getDataset() { return dataset; }
    public void setDataset(String dataset) { this.dataset = dataset; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public float[] getSemanticEmbedding() { return semanticEmbedding; }
    public void setSemanticEmbedding(float[] semanticEmbedding) {
        this.semanticEmbedding = semanticEmbedding;
        this.hasEmbedding = (semanticEmbedding != null);
    }

    public boolean isHasEmbedding() { return hasEmbedding; }
    private void setHasEmbedding(boolean hasEmbedding) { this.hasEmbedding = hasEmbedding; }

    public int getIncomingRelationCount() { return incomingRelationCount; }
    public void setIncomingRelationCount(int incomingRelationCount) { this.incomingRelationCount = incomingRelationCount; }

    public int getOutgoingRelationCount() { return outgoingRelationCount; }
    public void setOutgoingRelationCount(int outgoingRelationCount) { this.outgoingRelationCount = outgoingRelationCount; }

    public int getRequirementCoverageCount() { return requirementCoverageCount; }
    public void setRequirementCoverageCount(int requirementCoverageCount) { this.requirementCoverageCount = requirementCoverageCount; }

    public String getGraphRole() { return graphRole; }
    public void setGraphRole(String graphRole) { this.graphRole = graphRole; }

    /** Total number of relations (incoming + outgoing). */
    public int getTotalRelationCount() { return incomingRelationCount + outgoingRelationCount; }

    public TaxonomyNode getParent() { return parent; }
    public void setParent(TaxonomyNode parent) { this.parent = parent; }

    public List<TaxonomyNode> getChildren() { return children; }
    public void setChildren(List<TaxonomyNode> children) { this.children = children; }

    public List<TaxonomyRelation> getOutgoingRelations() { return outgoingRelations; }
    public void setOutgoingRelations(List<TaxonomyRelation> outgoingRelations) { this.outgoingRelations = outgoingRelations; }

    public List<TaxonomyRelation> getIncomingRelations() { return incomingRelations; }
    public void setIncomingRelations(List<TaxonomyRelation> incomingRelations) { this.incomingRelations = incomingRelations; }
}
