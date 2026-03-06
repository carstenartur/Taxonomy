package com.nato.taxonomy.model;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "taxonomy_node")
public class TaxonomyNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String code;

    @Column(nullable = true)
    private String uuid;

    @Column(nullable = false)
    private String nameEn;

    @Column(nullable = true)
    private String nameDe;

    @Column(length = 5000)
    private String descriptionEn;

    @Column(length = 5000)
    private String descriptionDe;

    @Column(name = "parent_code")
    private String parentCode;

    @Column(name = "taxonomy_root")
    private String taxonomyRoot;

    private int level;

    private String dataset;

    @Column(name = "external_id")
    private String externalId;

    private String source;

    @Column(length = 5000)
    private String reference;

    @Column(name = "sort_order")
    private Integer sortOrder;

    private String state;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private TaxonomyNode parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
    @OrderBy("nameEn ASC")
    private List<TaxonomyNode> children = new ArrayList<>();

    @OneToMany(mappedBy = "sourceNode", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TaxonomyRelation> outgoingRelations = new ArrayList<>();

    @OneToMany(mappedBy = "targetNode", cascade = CascadeType.ALL, orphanRemoval = true)
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

    public TaxonomyNode getParent() { return parent; }
    public void setParent(TaxonomyNode parent) { this.parent = parent; }

    public List<TaxonomyNode> getChildren() { return children; }
    public void setChildren(List<TaxonomyNode> children) { this.children = children; }

    public List<TaxonomyRelation> getOutgoingRelations() { return outgoingRelations; }
    public void setOutgoingRelations(List<TaxonomyRelation> outgoingRelations) { this.outgoingRelations = outgoingRelations; }

    public List<TaxonomyRelation> getIncomingRelations() { return incomingRelations; }
    public void setIncomingRelations(List<TaxonomyRelation> incomingRelations) { this.incomingRelations = incomingRelations; }
}
