package com.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

public class TaxonomyNodeDto {

    private Long id;
    private String code;
    private String uuid;
    private String nameEn;
    private String nameDe;
    private String descriptionEn;
    private String descriptionDe;
    private String parentCode;
    private String taxonomyRoot;
    private int level;
    private String dataset;
    private String externalId;
    private String source;
    private String reference;
    private Integer sortOrder;
    private String state;
    private List<TaxonomyNodeDto> children = new ArrayList<>();
    private Integer matchPercentage;
    private List<TaxonomyRelationDto> outgoingRelations = new ArrayList<>();
    private List<TaxonomyRelationDto> incomingRelations = new ArrayList<>();

    public TaxonomyNodeDto() {}

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

    public List<TaxonomyNodeDto> getChildren() { return children; }
    public void setChildren(List<TaxonomyNodeDto> children) { this.children = children; }

    public Integer getMatchPercentage() { return matchPercentage; }
    public void setMatchPercentage(Integer matchPercentage) { this.matchPercentage = matchPercentage; }

    public List<TaxonomyRelationDto> getOutgoingRelations() { return outgoingRelations; }
    public void setOutgoingRelations(List<TaxonomyRelationDto> outgoingRelations) { this.outgoingRelations = outgoingRelations; }

    public List<TaxonomyRelationDto> getIncomingRelations() { return incomingRelations; }
    public void setIncomingRelations(List<TaxonomyRelationDto> incomingRelations) { this.incomingRelations = incomingRelations; }
}
