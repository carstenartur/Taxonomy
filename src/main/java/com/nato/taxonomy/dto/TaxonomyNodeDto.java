package com.nato.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

public class TaxonomyNodeDto {

    private Long id;
    private String code;
    private String name;
    private String description;
    private String parentCode;
    private String taxonomyRoot;
    private int level;
    private List<TaxonomyNodeDto> children = new ArrayList<>();
    private Integer matchPercentage;

    public TaxonomyNodeDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getParentCode() { return parentCode; }
    public void setParentCode(String parentCode) { this.parentCode = parentCode; }

    public String getTaxonomyRoot() { return taxonomyRoot; }
    public void setTaxonomyRoot(String taxonomyRoot) { this.taxonomyRoot = taxonomyRoot; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public List<TaxonomyNodeDto> getChildren() { return children; }
    public void setChildren(List<TaxonomyNodeDto> children) { this.children = children; }

    public Integer getMatchPercentage() { return matchPercentage; }
    public void setMatchPercentage(Integer matchPercentage) { this.matchPercentage = matchPercentage; }
}
